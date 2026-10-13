/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One validated live identity snapshot. Gameplay policy and collection state are not remote data. */
final class TcgLiveCatalogSnapshot
{
	static final int MAX_CARDS = 15_000;
	final int itemCount;
	final int npcCount;
	final Map<Integer, Set<String>> itemKeysById;
	final Map<Integer, Set<String>> npcKeysById;
	final Map<String, Set<String>> itemKeysByName;
	final Map<String, Set<String>> npcKeysByName;
	final Set<String> allKeys;
	final List<Entry> entries;

	static final class Entry
	{
		final int id;
		final String name;
		final String key;
		final boolean npc;
		final String imageUrl;

		Entry(int id, String name, String key, boolean npc, String imageUrl)
		{
			this.id = id;
			this.name = name;
			this.key = key;
			this.npc = npc;
			this.imageUrl = imageUrl;
		}
	}

	private TcgLiveCatalogSnapshot(JsonArray items, JsonArray npcs)
	{
		itemCount = items.size();
		npcCount = npcs.size();
		Map<Integer, Set<String>> itemIds = new HashMap<>();
		Map<Integer, Set<String>> npcIds = new HashMap<>();
		Map<String, Set<String>> itemNames = new HashMap<>();
		Map<String, Set<String>> npcNames = new HashMap<>();
		Set<String> keys = new HashSet<>();
		List<Entry> cards = new ArrayList<>();
		Set<String> exactItemNames = new HashSet<>();
		for (JsonElement item : items)
		{
			exactItemNames.add(name(item.getAsJsonObject()));
		}
		parseCards(items, false, exactItemNames, itemIds, itemNames, keys, cards);
		parseCards(npcs, true, exactItemNames, npcIds, npcNames, keys, cards);
		itemKeysById = freeze(itemIds);
		npcKeysById = freeze(npcIds);
		itemKeysByName = freeze(itemNames);
		npcKeysByName = freeze(npcNames);
		allKeys = Collections.unmodifiableSet(keys);
		entries = Collections.unmodifiableList(cards);
	}

	static TcgLiveCatalogSnapshot parse(String json)
	{
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		JsonArray items = root.getAsJsonArray("items");
		JsonArray npcs = root.getAsJsonArray("npcs");
		if (items == null || npcs == null || items.size() == 0 || npcs.size() == 0
			|| items.size() + npcs.size() > MAX_CARDS)
		{
			throw new IllegalArgumentException("Incomplete live catalog");
		}
		return new TcgLiveCatalogSnapshot(items, npcs);
	}

	/** Prevent an error page or a truncated but valid JSON response from replacing a working catalog. */
	void validateForActivation()
	{
		if (itemCount < 1_000 || npcCount < 300)
		{
			throw new IllegalArgumentException("Live catalog is unexpectedly small");
		}
	}

	private static void parseCards(JsonArray source, boolean npc, Set<String> itemNames,
		Map<Integer, Set<String>> ids, Map<String, Set<String>> names, Set<String> keys, List<Entry> cards)
	{
		Set<Integer> parentIds = new HashSet<>();
		Set<String> parentNames = new HashSet<>();
		Map<Integer, String> owners = new HashMap<>();
		Set<Integer> ambiguousIds = new HashSet<>();
		for (JsonElement element : source)
		{
			JsonObject card = element.getAsJsonObject();
			int id = id(card.get("id"));
			String name = name(card);
			if (!parentIds.add(id) || !parentNames.add(name))
			{
				throw new IllegalArgumentException("Duplicate live parent identity");
			}
			String key = TcgItemNameNormalizer.normalize(npc && itemNames.contains(name) ? "npc:" + id : name);
			String image = imageUrl(card);
			cards.add(new Entry(id, name, key, npc, image));
			Map<Integer, String> entities = new LinkedHashMap<>();
			entities.put(id, name);
			JsonElement parent = card.get("parent");
			if (parent != null && !parent.isJsonNull())
			{
				if (parent.isJsonObject())
				{
					entities.put(id(parent.getAsJsonObject().get("id")), name(parent.getAsJsonObject()));
				}
				else
				{
					entities.put(id(parent), name);
				}
			}
			JsonObject tcg = card.getAsJsonObject("tcg");
			if (tcg != null && tcg.has("variants"))
			{
				JsonArray variants = tcg.getAsJsonArray("variants");
				if (variants == null || variants.size() > 2_000)
				{
					throw new IllegalArgumentException("Invalid live variants");
				}
				for (JsonElement variant : variants)
				{
					JsonObject v = variant.getAsJsonObject();
					entities.put(id(v.get("id")), name(v));
				}
			}
			Set<String> unlocks = new HashSet<>();
			unlocks.add(key);
			// Item variant names are also historical collection keys. NPC storage keys follow the
			// exact item-parent collision rule, matching the existing generator.
			if (!npc)
			{
				entities.values().forEach(value -> unlocks.add(TcgItemNameNormalizer.normalize(value)));
			}
			keys.addAll(unlocks);
			for (Map.Entry<Integer, String> entity : entities.entrySet())
			{
				String previous = owners.putIfAbsent(entity.getKey(), key);
				if (previous != null && !previous.equals(key))
				{
					ambiguousIds.add(entity.getKey());
				}
				ids.put(entity.getKey(), new HashSet<>(unlocks));
				String entityKey = TcgItemNameNormalizer.normalize(entity.getValue());
				names.computeIfAbsent(entityKey, unused -> new HashSet<>()).addAll(unlocks);
			}
		}
		// An ID attached to two different parents cannot safely be restricted.
		ambiguousIds.forEach(ids::remove);
	}

	private static int id(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			throw new IllegalArgumentException("Missing entity ID");
		}
		java.math.BigDecimal number = value.getAsBigDecimal();
		int id = number.intValueExact();
		if (id < 0)
		{
			throw new IllegalArgumentException("Negative entity ID");
		}
		return id;
	}

	private static String name(JsonObject object)
	{
		JsonElement value = object.get("name");
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
		{
			throw new IllegalArgumentException("Missing entity name");
		}
		String name = value.getAsString().trim();
		if (name.isEmpty() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl))
		{
			throw new IllegalArgumentException("Invalid entity name");
		}
		return name;
	}

	private static String imageUrl(JsonObject card)
	{
		JsonElement image = card.get("imagePath");
		if (image == null || image.isJsonNull())
		{
			return null;
		}
		String path = image.getAsString();
		return path.startsWith("/images/") && !path.contains("..") && path.length() <= 512
			? "https://osrs-tcg.net" + path : null;
	}

	private static <K> Map<K, Set<String>> freeze(Map<K, Set<String>> source)
	{
		Map<K, Set<String>> result = new HashMap<>();
		source.forEach((key, value) -> result.put(key, Collections.unmodifiableSet(new HashSet<>(value))));
		return Collections.unmodifiableMap(result);
	}
}
