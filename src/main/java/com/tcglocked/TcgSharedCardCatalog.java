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

/** Bundled card names for the Shared Cards window. No image URLs or network metadata are stored. */
@Singleton
final class TcgSharedCardCatalog
{
	private static final String ITEM_RESOURCE = "item-cards.txt";
	private static final String NPC_IMAGE_RESOURCE = "npc-card-images.tsv";

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

		private Card(String name, Category category, String imageUrl)
		{
			this.name = name;
			this.key = TcgItemNameNormalizer.normalize(name);
			this.category = category;
			this.imageUrl = imageUrl;
		}
	}

	private final List<Card> cards;

	@Inject
	TcgSharedCardCatalog()
	{
		Map<String, Card> loaded = new LinkedHashMap<>();
		loadItems(loaded);
		loadNpcs(loaded);
		cards = Collections.unmodifiableList(new ArrayList<>(loaded.values()));
	}

	List<Card> cards()
	{
		return cards;
	}

	private void loadItems(Map<String, Card> loaded)
	{
		read(ITEM_RESOURCE, line -> add(loaded, line, Category.ITEM, null));
	}

	private void loadNpcs(Map<String, Card> loaded)
	{
		read(NPC_IMAGE_RESOURCE, line ->
		{
			String[] fields = line.split("\\t", 2);
			if (fields.length != 2)
			{
				throw new IllegalStateException("Malformed NPC image catalog line");
			}
			add(loaded, unescape(fields[0]), Category.NPC, unescape(fields[1]));
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
