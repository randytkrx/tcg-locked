/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Active card names and local-cache image identities for the Shared Cards window. */
@Singleton
final class TcgSharedCardCatalog
{
	private static final String ITEM_RESOURCE = "item-cards.txt";
	private static final String NPC_IMAGE_RESOURCE = "npc-card-images.tsv";
	private static final String BETA_NPC_IMAGE_RESOURCE = "beta-npc-card-images.tsv";

	enum Category
	{
		ITEM("Item"), NPC("NPC");

		final String label;

		Category(String label)
		{
			this.label = label;
		}
	}

	static final class Card
	{
		final String name;
		final String key;
		final Category category;
		final String imageUrl;
		String legacyImageUrl;

		private Card(String name, Category category, String imageUrl)
		{
			this(name, TcgItemNameNormalizer.normalize(name), category, imageUrl);
		}

		private Card(String name, String key, Category category, String imageUrl)
		{
			this.name = name;
			this.key = key;
			this.category = category;
			this.imageUrl = imageUrl;
		}
	}

	private final List<Card> bundledCards;
	private volatile List<Card> cards;

	@Inject
	TcgSharedCardCatalog()
	{
		Map<String, Card> loaded = new LinkedHashMap<>();
		loadItems(loaded);
		loadNpcs(loaded);
		bundledCards = Collections.unmodifiableList(new ArrayList<>(loaded.values()));
		cards = bundledCards;
	}

	List<Card> cards()
	{
		return cards;
	}

	void activate(TcgLiveCatalogSnapshot snapshot)
	{
		if (snapshot == null)
		{
			cards = bundledCards;
			return;
		}
		Map<String, Card> loaded = new LinkedHashMap<>();
		for (TcgLiveCatalogSnapshot.Entry entry : snapshot.entries)
		{
			Category category = entry.npc ? Category.NPC : Category.ITEM;
			Card card = new Card(entry.name, entry.key, category, entry.npc ? entry.imageUrl : null);
			loaded.put(category.name() + '|' + entry.key, card);
		}
		// Historical cards remain visible independently. Do not retain stale v1 artwork or IDs.
		read("beta-item-cards.txt", line -> add(loaded, line, Category.ITEM, null));
		loadNpcResource(loaded, BETA_NPC_IMAGE_RESOURCE, true);
		cards = Collections.unmodifiableList(new ArrayList<>(loaded.values()));
	}

	private void loadItems(Map<String, Card> loaded)
	{
		read(ITEM_RESOURCE, line -> add(loaded, line, Category.ITEM, null));
	}

	private void loadNpcs(Map<String, Card> loaded)
	{
		loadNpcResource(loaded, NPC_IMAGE_RESOURCE, false);
		// Keep beta-only cards visible; current v1 artwork wins when both catalogs contain a card.
		loadNpcResource(loaded, BETA_NPC_IMAGE_RESOURCE, true);
	}

	private void loadNpcResource(Map<String, Card> loaded, String resource, boolean legacy)
	{
		read(resource, line ->
		{
			String[] fields = line.split("\\t", 2);
			if (fields.length != 2)
			{
				throw new IllegalStateException("Malformed NPC image catalog line");
			}
			String name = unescape(fields[0]);
			String imageUrl = unescape(fields[1]);
			String key = Category.NPC.name() + '|' + TcgItemNameNormalizer.normalize(name);
			Card existing = loaded.get(key);
			if (legacy && existing != null)
			{
				existing.legacyImageUrl = imageUrl;
			}
			else
			{
				add(loaded, name, Category.NPC, imageUrl);
				Card added = loaded.get(key);
				if (legacy && added != null)
				{
					added.legacyImageUrl = imageUrl;
				}
			}
		});
	}

	private static void add(Map<String, Card> loaded, String rawName, Category category, String imageUrl)
	{
		String name = rawName == null ? "" : rawName.trim();
		String key = TcgItemNameNormalizer.normalize(name);
		if (!name.isEmpty() && !key.isEmpty())
		{
			loaded.putIfAbsent(category.name() + '|' + key, new Card(name, category, imageUrl));
		}
	}

	private static String unescape(String value)
	{
		return value.replace("\\t", "\t").replace("\\r", "\r")
			.replace("\\n", "\n").replace("\\\\", "\\");
	}

	private void read(String resource, java.util.function.Consumer<String> consumer)
	{
		try (InputStream in = getClass().getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing " + resource);
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (!line.isEmpty() && line.charAt(0) != '#')
					{
						consumer.accept(line);
					}
				}
			}
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Unable to load " + resource, ex);
		}
	}

	static List<Card> filter(List<Card> cards, TcgSharedCatalogSnapshot snapshot, String owner,
		Category category, boolean sharedOnly, String search)
	{
		String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
		List<Card> filtered = new ArrayList<>();
		for (Card card : cards)
		{
			boolean shared = owner == null ? snapshot.isShared(card.key) : snapshot.keysFor(owner).contains(card.key);
			if ((sharedOnly && !shared) || (category != null && card.category != category)
				|| (!query.isEmpty() && !card.name.toLowerCase(Locale.ROOT).contains(query)))
			{
				continue;
			}
			filtered.add(card);
		}
		return Collections.unmodifiableList(filtered);
	}
}
