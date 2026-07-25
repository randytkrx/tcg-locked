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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
