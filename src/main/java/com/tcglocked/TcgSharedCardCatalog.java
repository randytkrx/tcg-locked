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
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
final class TcgSharedCardCatalog
{
	private static final String RESOURCE = "shared-card-catalog.tsv";

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
		cards = load();
	}

	List<Card> cards()
	{
		return cards;
	}

	private List<Card> load()
	{
		List<Card> loaded = new ArrayList<>();
		try (InputStream in = getClass().getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing shared card catalog");
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (line.isEmpty() || line.charAt(0) == '#')
					{
						continue;
					}
					String[] fields = line.split("\\t", 3);
					if (fields.length != 3)
					{
						throw new IllegalStateException("Malformed shared card catalog line");
					}
					Category category;
					if ("Resource".equals(fields[1]))
					{
						category = Category.ITEM;
					}
					else if ("Monster".equals(fields[1]))
					{
						category = Category.NPC;
					}
					else
					{
						throw new IllegalStateException("Unknown shared card category");
					}
					loaded.add(new Card(unescape(fields[0]), category, unescape(fields[2])));
				}
			}
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Unable to load shared card catalog", ex);
		}
		return Collections.unmodifiableList(loaded);
	}

	private static String unescape(String value)
	{
		StringBuilder out = new StringBuilder(value.length());
		boolean escaped = false;
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if (!escaped && c == '\\')
			{
				escaped = true;
				continue;
			}
			if (escaped)
			{
				c = c == 't' ? '\t' : c == 'r' ? '\r' : c == 'n' ? '\n' : c;
				escaped = false;
			}
			out.append(c);
		}
		if (escaped)
		{
			out.append('\\');
		}
		return out.toString();
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
