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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Which items and monsters have a card at all, from a bundled snapshot of OSRS TCG's card list.
 *
 * <p>This is the "is it part of the challenge" question, and it is deliberately answered from a
 * static resource rather than live from the TCG plugin: the card list changes only when the
 * upstream catalog does, and unlike ownership it is the same for every player. Regenerate with
 * {@code scripts/generate-card-catalogs.js} when the upstream catalog gains cards.</p>
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

	/** Leading tag on a packed collection: a raw bitmap, or one that was worth deflating. */
	private static final char PACKED_RAW = 'b';
	private static final char PACKED_DEFLATED = 'z';

	/** Every card key in a stable order, so a packed bitmap means the same thing on both sides. */
	private List<String> indexedKeys = Collections.emptyList();
	private Map<String, Integer> keyIndex = Collections.emptyMap();

	/** Normalized names of every item that has a card. */
	private Set<String> itemCardKeys = Collections.emptySet();

	/** Normalized in-game NPC name to the normalized card names that unlock it (any one suffices). */
	private Map<String, Set<String>> npcCardKeys = Collections.emptyMap();

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
		return normalizedItemKey != null && itemCardKeys.contains(normalizedItemKey);
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
		return npcCardKeys.getOrDefault(normalizedNpcKey, Collections.emptySet());
	}

	/**
	 * Packs card keys into a base64 bitmap over {@link #indexedKeys}, for storing a synced partner's
	 * collection. A full collection is thousands of names; as text that is tens of kilobytes of
	 * synced config per partner, and as a bitmap it is around a kilobyte. Keys outside the catalog
	 * are dropped, which is lossless for gating: a card with no matching item or monster cannot
	 * unlock anything.
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
		return deflated != null && deflated.length < bits.length
			? PACKED_DEFLATED + Base64.getEncoder().encodeToString(deflated)
			: PACKED_RAW + Base64.getEncoder().encodeToString(bits);
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

	private static byte[] inflate(byte[] compressed, int maxLength) throws DataFormatException
	{
		Inflater inflater = new Inflater();
		try
		{
			inflater.setInput(compressed);
			ByteArrayOutputStream out = new ByteArrayOutputStream(maxLength);
			byte[] chunk = new byte[1024];
			while (!inflater.finished() && out.size() <= maxLength)
			{
				int read = inflater.inflate(chunk);
				if (read == 0 && (inflater.needsInput() || inflater.needsDictionary()))
				{
					break;
				}
				out.write(chunk, 0, read);
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
		if (packed == null || packed.isEmpty())
		{
			return Collections.emptySet();
		}
		try
		{
			char form = packed.charAt(0);
			byte[] stored = Base64.getDecoder().decode(packed.substring(1));
			int byteLength = (indexedKeys.size() + 7) / 8;
			byte[] bits = form == PACKED_DEFLATED ? inflate(stored, byteLength) : stored;
			Set<String> keys = new HashSet<>();
			int limit = Math.min(indexedKeys.size(), bits.length * 8);
			for (int i = 0; i < limit; i++)
			{
				if ((bits[i >> 3] & (1 << (i & 7))) != 0)
				{
					keys.add(indexedKeys.get(i));
				}
			}
			return Collections.unmodifiableSet(keys);
		}
		catch (IllegalArgumentException | DataFormatException ex)
		{
			// Corrupt or from a catalog that has since changed shape: forget it rather than gate on
			// keys that now mean something else.
			log.debug("TCG Locked: could not unpack a stored partner collection", ex);
			return Collections.emptySet();
		}
	}

	/** @return every card key the catalog knows, in index order. */
	Set<String> allCardKeys()
	{
		return new java.util.LinkedHashSet<>(indexedKeys);
	}

	/** @return how many item cards loaded; 0 means the resource was missing and item gating is off. */
	int itemCardCount()
	{
		return itemCardKeys.size();
	}

	/** @return how many monsters loaded; 0 means the resource was missing and monster gating is off. */
	int npcCount()
	{
		return npcCardKeys.size();
	}

	private void load()
	{
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
		npcCardKeys = Collections.unmodifiableMap(npcs);

		// Sorted so the bit for a given card is the same on every client and across restarts. Monster
		// CARD names go in, not the in-game names they are keyed by, because the card name is what a
		// collection actually contains.
		TreeSet<String> all = new TreeSet<>(items);
		npcs.values().forEach(all::addAll);
		indexedKeys = Collections.unmodifiableList(new ArrayList<>(all));
		Map<String, Integer> index = new HashMap<>(indexedKeys.size() * 2);
		for (int i = 0; i < indexedKeys.size(); i++)
		{
			index.put(indexedKeys.get(i), i);
		}
		keyIndex = Collections.unmodifiableMap(index);
		log.debug("TCG Locked: card catalog loaded ({} item cards, {} monsters).", items.size(), npcs.size());
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
