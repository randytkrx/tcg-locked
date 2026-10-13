/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 */
package com.tcglocked;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Which items and monsters have a card, from a validated live snapshot or the bundled fallback.
 *
 * <p>The bundled bitmap index stays fixed for saved collections and older party clients. Live
 * identity updates never change what an existing bitmap means.</p>
 *
 * <p>It matters because roughly 5,100 of the game's items have a card. Anything else — a Salve
 * amulet, a Max cape, a teleport tablet — can never have its card owned, so gating it would be a
 * permanent dead end rather than a challenge. Those items are reported as uncarded here and left
 * alone, the same rule monster gating has always used.</p>
 *
 * <p>All keys are {@link TcgItemNameNormalizer} output, so lookups compare like with like against
 * the normalized owned-card names.</p>
 */
@Singleton
@Slf4j
class TcgCardCatalog
{
	private static final String ITEM_RESOURCE = "item-cards.txt";
	private static final String NPC_RESOURCE = "npc-cards.txt";
	private static final String ITEM_ID_RESOURCE = "item-card-ids.txt";
	private static final String NPC_ID_RESOURCE = "npc-card-ids.txt";
	private static final String LEGACY_V2_RESOURCE = "legacy-v2-card-keys.txt";
	private static final String FROZEN_V3_RESOURCE = "legacy-v3-card-keys.txt";

	/** Leading tag on a packed collection: a raw bitmap, or one that was worth deflating. */
	private static final char PACKED_RAW = 'b';
	private static final char PACKED_DEFLATED = 'z';
	private static final String PACKED_VERSION = "3";
	private static final String LEGACY_PACKED_VERSION = "2";
	private static final int MAX_PACKED_LENGTH = 16_384;

	/** Every card key in a stable order, so a packed bitmap means the same thing on both sides. */
	private List<String> indexedKeys = Collections.emptyList();
	private Map<String, Integer> keyIndex = Collections.emptyMap();
	private String catalogHash = "";
	private List<String> legacyIndexedKeys = Collections.emptyList();
	private Set<String> legacyKeySet = Collections.emptySet();
	private String legacyCatalogHash = "";

	/** Normalized names of every item that has a card. */
	private Set<String> itemCardKeys = Collections.emptySet();
	private Set<Integer> itemCardIds = Collections.emptySet();

	/** Normalized in-game NPC name to the normalized card names that unlock it (any one suffices). */
	private Map<String, Set<String>> npcCardKeys = Collections.emptyMap();
	private final Map<String, Set<String>> betaNpcCardKeys = new HashMap<>();
	private Set<Integer> npcCardIds = Collections.emptySet();
	private volatile TcgLiveCatalogSnapshot live;

	@Inject
	TcgCardCatalog()
	{
		load();
	}

	/**
	 * @return true if a card exists for this item. False means the item is outside the challenge:
	 * callers must leave it unlocked, because no card could ever unlock it.
	 */
	boolean hasItemCard(String normalizedItemKey)
	{
		TcgLiveCatalogSnapshot current = live;
		return normalizedItemKey != null && (current == null ? itemCardKeys.contains(normalizedItemKey)
			: current.itemKeysByName.containsKey(normalizedItemKey));
	}

	/** @return true if this current game item ID belongs to a card or one of its variants. */
	boolean hasItemCard(int itemId)
	{
		TcgLiveCatalogSnapshot current = live;
		return current == null ? itemCardIds.contains(itemId) : current.itemKeysById.containsKey(itemId);
	}

	/** @return true if this current game NPC ID belongs to a card or one of its variants. */
	boolean hasNpcCard(int npcId)
	{
		TcgLiveCatalogSnapshot current = live;
		return current == null ? npcCardIds.contains(npcId) : current.npcKeysById.containsKey(npcId);
	}

	/**
	 * @return the normalized card names that unlock this monster, or an empty set when it has no
	 * card (untracked monsters are always interactable). Usually one card; a few in-game names are
	 * shared by several cards, e.g. "Archer" by both the Ardougne and Burthorpe cards.
	 */
	Set<String> npcCards(String normalizedNpcKey)
	{
		if (normalizedNpcKey == null)
		{
			return Collections.emptySet();
		}
		TcgLiveCatalogSnapshot current = live;
		return current == null ? npcCardKeys.getOrDefault(normalizedNpcKey, Collections.emptySet())
			: current.npcKeysByName.getOrDefault(normalizedNpcKey, Collections.emptySet());
	}

	/**
	 * Packs released card keys into the frozen v3 bitmap, for compatibility with an older partner's
	 * collection. A full collection is thousands of names; as text that is tens of kilobytes of
	 * synced config per partner, and as a bitmap it is around a kilobyte. Keys outside the catalog
	 * are carried separately by {@link #packStoredKeys} and the party-message extension.
	 */
	String packKeys(Set<String> keys)
	{
		if (keys == null || keys.isEmpty() || indexedKeys.isEmpty())
		{
			return "";
		}
		byte[] bits = new byte[(indexedKeys.size() + 7) / 8];
		for (String key : keys)
		{
			Integer index = keyIndex.get(key);
			if (index != null)
			{
				bits[index >> 3] |= (byte) (1 << (index & 7));
			}
		}
		// Deflate only pays off for sparse collections; a nearly-full one is high entropy and comes
		// back bigger. Keep whichever is smaller and tag it so unpacking knows which it was given.
		byte[] deflated = deflate(bits);
		String payload = deflated != null && deflated.length < bits.length
			? PACKED_DEFLATED + Base64.getEncoder().encodeToString(deflated)
			: PACKED_RAW + Base64.getEncoder().encodeToString(bits);
		return PACKED_VERSION + ':' + catalogHash + ':' + payload;
	}

	private static byte[] deflate(byte[] raw)
	{
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		try
		{
			deflater.setInput(raw);
			deflater.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
			byte[] chunk = new byte[1024];
			while (!deflater.finished())
			{
				out.write(chunk, 0, deflater.deflate(chunk));
			}
			return out.toByteArray();
		}
		catch (Exception ex)
		{
			return null;
		}
		finally
		{
			deflater.end();
		}
	}

	private static byte[] inflate(byte[] compressed, int expectedLength) throws DataFormatException
	{
		Inflater inflater = new Inflater();
		try
		{
			inflater.setInput(compressed);
			ByteArrayOutputStream out = new ByteArrayOutputStream(expectedLength);
			byte[] chunk = new byte[1024];
			while (!inflater.finished() && out.size() <= expectedLength)
			{
				int read = inflater.inflate(chunk);
				if (read == 0 && (inflater.needsInput() || inflater.needsDictionary()))
				{
					break;
				}
				out.write(chunk, 0, read);
			}
			if (!inflater.finished() || out.size() != expectedLength)
			{
				throw new DataFormatException("Unexpected packed collection length");
			}
			return out.toByteArray();
		}
		finally
		{
			inflater.end();
		}
	}

	/** @return the keys packed by {@link #packKeys}, or an empty set if the blob is unusable. */
	Set<String> unpackKeys(String packed)
	{
		Set<String> keys = unpackKeysOrNull(packed);
		return keys == null ? Collections.emptySet() : keys;
	}

	/** @return decoded keys, or null when the payload is malformed or from another catalog. */
	Set<String> unpackKeysOrNull(String packed)
	{
		if (packed == null)
		{
			return null;
		}
		if (packed.isEmpty())
		{
			return Collections.emptySet();
		}
		if (packed.startsWith("4:"))
		{
			if (packed.length() > TcgCollectionCodec.MAX_ENCODED + MAX_PACKED_LENGTH + 4)
			{
				return null;
			}
			int split = packed.lastIndexOf(':');
			if (split < 2)
			{
				return null;
			}
			String bitmap = packed.substring(2, split);
			// A v4 wrapper can contain only a released v2/v3 bitmap, never another wrapper.
			if (!bitmap.isEmpty() && !bitmap.startsWith("2:") && !bitmap.startsWith("3:"))
			{
				return null;
			}
			Set<String> base = unpackKeysOrNull(bitmap);
			Set<String> extra = TcgCollectionCodec.decode(packed.substring(split + 1));
			if (base == null || extra == null)
			{
				return null;
			}
			Set<String> combined = new HashSet<>(base);
			combined.addAll(extra);
			return TcgCollectionCodec.validKeys(combined) ? Collections.unmodifiableSet(combined) : null;
		}
		if (packed.length() > MAX_PACKED_LENGTH)
		{
			return null;
		}
		try
		{
			String[] header = packed.split(":", 3);
			if (header.length != 3 || header[2].length() < 2)
			{
				throw new IllegalArgumentException("Unknown packed collection format");
			}
			List<String> packedIndex;
			if (PACKED_VERSION.equals(header[0]) && catalogHash.equals(header[1]))
			{
				packedIndex = indexedKeys;
			}
			else if (LEGACY_PACKED_VERSION.equals(header[0]) && legacyCatalogHash.equals(header[1]))
			{
				packedIndex = legacyIndexedKeys;
			}
			else
			{
				throw new IllegalArgumentException("Unknown packed collection catalog");
			}
			char form = header[2].charAt(0);
			if (form != PACKED_RAW && form != PACKED_DEFLATED)
			{
				throw new IllegalArgumentException("Unknown packed collection encoding");
			}
			byte[] stored = Base64.getDecoder().decode(header[2].substring(1));
			int byteLength = (packedIndex.size() + 7) / 8;
			byte[] bits = form == PACKED_DEFLATED ? inflate(stored, byteLength) : stored;
			if (bits.length != byteLength)
			{
				throw new IllegalArgumentException("Unexpected packed collection length");
			}
			Set<String> keys = new HashSet<>();
			int limit = Math.min(packedIndex.size(), bits.length * 8);
			for (int i = 0; i < limit; i++)
			{
				if ((bits[i >> 3] & (1 << (i & 7))) != 0)
				{
					keys.add(packedIndex.get(i));
				}
			}
			return Collections.unmodifiableSet(keys);
		}
		catch (IllegalArgumentException | DataFormatException ex)
		{
			// Corrupt or from a catalog that has since changed shape: forget it rather than gate on
			// keys that now mean something else.
			log.debug("TCG Locked: could not unpack a stored partner collection", ex);
			return null;
		}
	}

	Set<String> filterKnownKeys(Set<String> keys)
	{
		if (keys == null || keys.size() > allCardKeys().size())
		{
			return null;
		}
		Set<String> known = allCardKeys();
		Set<String> filtered = new HashSet<>(keys.size());
		for (String key : keys)
		{
			if (key == null || key.length() > 128 || !known.contains(key))
			{
				return null;
			}
			filtered.add(key);
		}
		return Collections.unmodifiableSet(filtered);
	}

	/** Drops v3-only keys from the name-array fallback sent to clients that only know v2 names. */
	Set<String> filterLegacyKeys(Set<String> keys)
	{
		if (keys == null)
		{
			return Collections.emptySet();
		}
		Set<String> filtered = new HashSet<>(keys);
		filtered.retainAll(legacyKeySet);
		return Collections.unmodifiableSet(filtered);
	}

	/** Decodes a bitmap and merges the raw-name fallback used during mixed-version rollout. */
	Set<String> mergePackedAndRawKeys(String packed, Set<String> rawKeys)
	{
		Set<String> packedKeys = packed == null ? null : unpackKeysOrNull(packed);
		Set<String> knownRaw = rawKeys == null ? null : filterKnownKeys(rawKeys);
		if (packedKeys == null)
		{
			return knownRaw;
		}
		if (knownRaw == null || knownRaw.isEmpty())
		{
			return packedKeys;
		}
		Set<String> merged = new HashSet<>(packedKeys);
		merged.addAll(knownRaw);
		return Collections.unmodifiableSet(merged);
	}

	/** @return every card key the catalog knows, in index order. */
	Set<String> allCardKeys()
	{
		Set<String> keys = new java.util.LinkedHashSet<>(indexedKeys);
		keys.addAll(itemCardKeys);
		npcCardKeys.values().forEach(keys::addAll);
		TcgLiveCatalogSnapshot current = live;
		if (current != null)
		{
			keys.addAll(current.allKeys);
		}
		return keys;
	}

	/** @return how many item cards loaded; 0 means the resource was missing and item gating is off. */
	int itemCardCount()
	{
		TcgLiveCatalogSnapshot current = live;
		return current == null ? itemCardKeys.size() : current.itemKeysByName.size();
	}

	/** @return how many monsters loaded; 0 means the resource was missing and monster gating is off. */
	int npcCount()
	{
		TcgLiveCatalogSnapshot current = live;
		return current == null ? npcCardKeys.size() : current.npcKeysByName.size();
	}

	int itemIdCount()
	{
		TcgLiveCatalogSnapshot current = live;
		return current == null ? itemCardIds.size() : current.itemKeysById.size();
	}

	int npcIdCount()
	{
		TcgLiveCatalogSnapshot current = live;
		return current == null ? npcCardIds.size() : current.npcKeysById.size();
	}

	void activate(TcgLiveCatalogSnapshot snapshot)
	{
		live = snapshot;
	}

	/** ID-based parent resolution for shared ownership; bundled fallback keeps its old name path. */
	Set<String> itemCards(int id, String normalizedName)
	{
		TcgLiveCatalogSnapshot current = live;
		return current == null ? Collections.singleton(normalizedName)
			: current.itemKeysById.getOrDefault(id, Collections.emptySet());
	}

	Set<String> npcCards(int id, String normalizedName)
	{
		TcgLiveCatalogSnapshot current = live;
		if (current == null || id < 0)
		{
			return npcCards(normalizedName);
		}
		Set<String> parents = current.npcKeysById.getOrDefault(id, Collections.emptySet());
		if (parents.isEmpty())
		{
			return parents;
		}
		Set<String> compatible = new HashSet<>(parents);
		// Reviewed historical names remain independent unlocks only for an entity still tracked
		// by the live catalogue. Old bundled v1 entity IDs never leak into the active snapshot.
		compatible.addAll(betaNpcCardKeys.getOrDefault(normalizedName, Collections.emptySet()));
		return compatible;
	}


	/** Authored interaction requirements follow live parent names without adding remote gameplay rules. */
	boolean requirementSatisfied(String key, java.util.function.Predicate<String> owns)
	{
		TcgLiveCatalogSnapshot current = live;
		if (current == null)
		{
			return owns.test(key);
		}
		Set<String> items = current.itemKeysByName.getOrDefault(key, Collections.emptySet());
		Set<String> npcs = current.npcKeysByName.getOrDefault(key, Collections.emptySet());
		if (!items.isEmpty() && !npcs.isEmpty())
		{
			// The policy supplied only a name, so it cannot disambiguate the entity kind.
			return true;
		}
		Set<String> candidates = !items.isEmpty() ? items : npcs;
		if (candidates.isEmpty())
		{
			// Explicit npc:<id> storage keys are already unambiguous. A removed/unknown card
			// cannot be acquired, so it must not leave an authored interaction permanently locked.
			return current.allKeys.contains(key) ? owns.test(key) : true;
		}
		return candidates.stream().anyMatch(owns);
	}

	Set<String> extraKeys(Set<String> keys)
	{
		Set<String> extra = new HashSet<>(keys);
		extra.removeAll(keyIndex.keySet());
		return extra;
	}

	/** Existing keys retain the compact released bitmap. Only new keys need a names extension. */
	String packStoredKeys(Set<String> keys)
	{
		if (!TcgCollectionCodec.validKeys(keys))
		{
			return null;
		}
		Set<String> extra = extraKeys(keys);
		try
		{
			return extra.isEmpty() ? packKeys(keys) : "4:" + packKeys(keys) + ':' + TcgCollectionCodec.encode(extra);
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}

	Set<String> mergeExtraKeys(Set<String> base, String encoded)
	{
		if (encoded == null)
		{
			return base;
		}
		Set<String> extra = TcgCollectionCodec.decode(encoded);
		if (base == null || extra == null)
		{
			return null;
		}
		Set<String> merged = new HashSet<>(base);
		merged.addAll(extra);
		return TcgCollectionCodec.validKeys(merged) ? Collections.unmodifiableSet(merged) : null;
	}

	private void load()
	{
		forEachLine("beta-npc-cards.txt", line ->
		{
			String[] parts = line.split("\\|");
			Set<String> cards = betaNpcCardKeys.computeIfAbsent(TcgItemNameNormalizer.normalize(parts[0]),
				unused -> new HashSet<>());
			if (parts.length == 1)
			{
				cards.add(TcgItemNameNormalizer.normalize(parts[0]));
			}
			else
			{
				for (int i = 1; i < parts.length; i++)
				{
					cards.add(TcgItemNameNormalizer.normalize(parts[i]));
				}
			}
		});
		Set<Integer> itemIds = loadIds(ITEM_ID_RESOURCE);
		Set<Integer> npcIds = loadIds(NPC_ID_RESOURCE);
		Set<String> items = new HashSet<>();
		forEachLine(ITEM_RESOURCE, line ->
		{
			String key = TcgItemNameNormalizer.normalize(line);
			if (!key.isEmpty())
			{
				items.add(key);
			}
		});

		Map<String, Set<String>> npcs = new HashMap<>();
		forEachLine(NPC_RESOURCE, line ->
		{
			// "NPC name|card name[|card name...]". Older single-column lines still parse: with no
			// separator the NPC name is also the card name, which is true for all but 67 cards.
			String[] parts = line.split("\\|");
			String entity = TcgItemNameNormalizer.normalize(parts[0]);
			if (entity.isEmpty())
			{
				return;
			}
			Set<String> cards = npcs.computeIfAbsent(entity, key -> new HashSet<>());
			if (parts.length == 1)
			{
				cards.add(entity);
				return;
			}
			for (int i = 1; i < parts.length; i++)
			{
				String card = TcgItemNameNormalizer.normalize(parts[i]);
				if (!card.isEmpty())
				{
					cards.add(card);
				}
			}
		});
		npcs.replaceAll((entity, cards) -> Collections.unmodifiableSet(cards));

		itemCardKeys = Collections.unmodifiableSet(items);
		itemCardIds = Collections.unmodifiableSet(itemIds);
		npcCardKeys = Collections.unmodifiableMap(npcs);
		npcCardIds = Collections.unmodifiableSet(npcIds);

		List<String> legacy = new ArrayList<>();
		forEachLine(LEGACY_V2_RESOURCE, legacy::add);
		legacyIndexedKeys = Collections.unmodifiableList(legacy);
		legacyKeySet = Collections.unmodifiableSet(new HashSet<>(legacy));
		legacyCatalogHash = hashCatalog(legacyIndexedKeys);

		// This exact released index is independent of the active catalogue and future bundled
		// resource regeneration. Never move the bit assigned to an existing saved card.
		List<String> frozen = new ArrayList<>();
		forEachLine(FROZEN_V3_RESOURCE, frozen::add);
		indexedKeys = Collections.unmodifiableList(frozen);
		catalogHash = hashCatalog(indexedKeys);
		Map<String, Integer> index = new HashMap<>(indexedKeys.size() * 2);
		for (int i = 0; i < indexedKeys.size(); i++)
		{
			index.put(indexedKeys.get(i), i);
		}
		keyIndex = Collections.unmodifiableMap(index);
		log.debug("TCG Locked: card catalog loaded ({} item names/{} IDs, {} NPC names/{} IDs).",
			items.size(), itemIds.size(), npcs.size(), npcIds.size());
	}

	private Set<Integer> loadIds(String resource)
	{
		Set<Integer> ids = new HashSet<>();
		forEachLine(resource, line -> ids.add(Integer.parseInt(line)));
		return ids;
	}

	private static String hashCatalog(List<String> keys)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String key : keys)
			{
				digest.update(key.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
			}
			byte[] hash = digest.digest();
			StringBuilder out = new StringBuilder(16);
			for (int i = 0; i < 8; i++)
			{
				out.append(String.format("%02x", hash[i]));
			}
			return out.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	/** Reads a bundled catalog, skipping blank lines and {@code #} comments. */
	private void forEachLine(String resource, java.util.function.Consumer<String> consumer)
	{
		try (InputStream in = getClass().getResourceAsStream(resource))
		{
			if (in == null)
			{
				// Nothing to gate against: callers treat an empty catalog as "no cards exist", so
				// everything stays unlocked rather than everything being locked out.
				log.warn("TCG Locked: {} missing; that part of the catalog will not gate anything.", resource);
				return;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					String trimmed = line.trim();
					if (!trimmed.isEmpty() && !trimmed.startsWith("#"))
					{
						consumer.accept(trimmed);
					}
				}
			}
		}
		catch (Exception ex)
		{
			log.warn("TCG Locked: failed to load {}", resource, ex);
		}
	}
}
