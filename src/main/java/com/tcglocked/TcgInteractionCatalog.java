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
 *
 * ---
 *
 * The data file this class reads, resource-nodes.json, is derived from Bronzeman
 * TCG (https://github.com/Felmeme/bronzeman-tcg), Copyright (c) 2026, Felmeme,
 * BSD 2-Clause. The schema below is Felmeme's design and is read unchanged so the
 * two plugins stay compatible. See THIRD-PARTY-NOTICES.md for the full licence.
 */
package com.tcglocked;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Card requirements for in-game <em>interactions</em> rather than items: chopping a tree, cooking a
 * fish, pickpocketing an NPC. Where {@link TcgCardCatalog} answers "does this item have a card",
 * this answers "does this action need cards, and which".
 *
 * <p>Each entry is keyed by {@code kind} and target {@code name}, and carries one or more
 * <em>card groups</em>. A group is a set of alternatives: owning any one card in it satisfies that
 * group. An interaction is allowed only when every group is satisfied. Cooking anchovies, for
 * instance, has an input group ({@code Raw anchovies}) and an output group ({@code Anchovies}).
 *
 * <p>Several entries can share a kind and name while differing by {@code options}, which is how one
 * target gates different verbs separately. {@code "*"} matches any option.
 *
 * <p>A malformed or missing file leaves the catalog empty rather than throwing. That is deliberate
 * and matches the rest of the plugin: not knowing a requirement must never lock something.
 */
@Slf4j
@Singleton
class TcgInteractionCatalog
{
	static final String KIND_OBJECT = "object";
	static final String KIND_NPC = "npc";
	static final String KIND_FISHING_SPOT = "fishing-spot";
	static final String KIND_ITEM_ON_OBJECT = "item-on-object";
	static final String KIND_INVENTORY = "inventory";
	static final String KIND_INTERFACE = "interface";

	/** Wildcard in a node's {@code options}: the entry applies to every menu option on that target. */
	static final String ANY_OPTION = "*";

	private static final String RESOURCE = "resource-nodes.json";

	/** Keyed {@code kind|normalized-name}; the list preserves file order so ties resolve predictably. */
	private final Map<String, List<Rule>> rules;

	@Inject
	TcgInteractionCatalog(Gson gson)
	{
		this.rules = load(gson);
		log.debug("TCG Locked: interaction catalog loaded {} target(s).", rules.size());
	}

	/** Test seam: build from a reader instead of the bundled resource. */
	TcgInteractionCatalog(Gson gson, Reader reader)
	{
		this.rules = parse(gson, reader);
	}

	/**
	 * @return the entry gating this interaction, or null when the action is unrestricted. An
	 * unknown kind, name or option is unrestricted.
	 */
	Rule find(String kind, String name, String option)
	{
		if (kind == null || name == null)
		{
			return null;
		}
		List<Rule> candidates = rules.get(key(kind, TcgItemNameNormalizer.normalize(name)));
		if (candidates == null)
		{
			return null;
		}
		String wanted = option == null ? "" : option.toLowerCase(Locale.ROOT).trim();
		for (Rule rule : candidates)
		{
			if (rule.matchesOption(wanted))
			{
				return rule;
			}
		}
		return null;
	}

	/** @return how many distinct kind/name targets are gated. Used by tests and debug logging. */
	int size()
	{
		return rules.size();
	}

	private Map<String, List<Rule>> load(Gson gson)
	{
		try (InputStream in = getClass().getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("TCG Locked: {} missing; interaction gating is inactive.", RESOURCE);
				return Collections.emptyMap();
			}
			return parse(gson, new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		catch (IOException ex)
		{
			log.warn("TCG Locked: could not read {}; interaction gating is inactive.", RESOURCE, ex);
			return Collections.emptyMap();
		}
	}

	private Map<String, List<Rule>> parse(Gson gson, Reader reader)
	{
		final RootDto root;
		try
		{
			root = gson.fromJson(reader, RootDto.class);
		}
		catch (JsonParseException ex)
		{
			log.warn("TCG Locked: {} is malformed; interaction gating is inactive.", RESOURCE, ex);
			return Collections.emptyMap();
		}
		if (root == null || root.nodes == null)
		{
			return Collections.emptyMap();
		}

		Map<String, List<Rule>> built = new HashMap<>();
		for (NodeDto node : root.nodes)
		{
			if (node == null || node.kind == null || node.name == null)
			{
				continue;
			}
			String name = TcgItemNameNormalizer.normalize(node.name);
			if (name.isEmpty())
			{
				continue;
			}
			List<CardGroup> groups = toGroups(node);
			if (groups.isEmpty())
			{
				// Nothing to require means nothing to gate; skip rather than store an always-open rule.
				continue;
			}
			built.computeIfAbsent(key(node.kind, name), k -> new ArrayList<>())
				.add(new Rule(node.category, toOptions(node.options), groups));
		}
		built.replaceAll((k, v) -> Collections.unmodifiableList(v));
		return Collections.unmodifiableMap(built);
	}

	/**
	 * Builds the card groups, honouring the legacy {@code requiredCards} form: with
	 * {@code requireAll} every card becomes its own group (all needed), otherwise they collapse into
	 * one group (any one suffices).
	 */
	private static List<CardGroup> toGroups(NodeDto node)
	{
		List<CardGroup> groups = new ArrayList<>();
		if (node.requiredCardGroups != null && !node.requiredCardGroups.isEmpty())
		{
			for (int i = 0; i < node.requiredCardGroups.size(); i++)
			{
				CardGroup group = toGroup(node.requiredCardGroups.get(i), roleAt(node.groupRoles, i),
					roleAt(node.groupLabels, i));
				if (group != null)
				{
					groups.add(group);
				}
			}
			return groups;
		}
		if (node.requiredCards == null || node.requiredCards.isEmpty())
		{
			return groups;
		}
		if (node.requireAll)
		{
			for (String card : node.requiredCards)
			{
				CardGroup group = toGroup(Collections.singletonList(card), null, null);
				if (group != null)
				{
					groups.add(group);
				}
			}
			return groups;
		}
		CardGroup any = toGroup(node.requiredCards, null, null);
		if (any != null)
		{
			groups.add(any);
		}
		return groups;
	}

	private static CardGroup toGroup(List<String> cards, String role, String label)
	{
		if (cards == null || cards.isEmpty())
		{
			return null;
		}
		List<String> display = new ArrayList<>(cards.size());
		Set<String> normalized = new HashSet<>(cards.size());
		for (String card : cards)
		{
			if (card == null)
			{
				continue;
			}
			String trimmed = card.trim();
			String key = TcgItemNameNormalizer.normalize(trimmed);
			if (trimmed.isEmpty() || key.isEmpty())
			{
				continue;
			}
			display.add(trimmed);
			normalized.add(key);
		}
		return normalized.isEmpty() ? null
			: new CardGroup(Collections.unmodifiableList(display), Collections.unmodifiableSet(normalized), role, label);
	}

	private static String roleAt(List<String> values, int index)
	{
		return values != null && index < values.size() ? values.get(index) : null;
	}

	private static Set<String> toOptions(List<String> options)
	{
		if (options == null || options.isEmpty())
		{
			return Collections.singleton(ANY_OPTION);
		}
		Set<String> lower = new HashSet<>(options.size());
		for (String option : options)
		{
			if (option != null && !option.trim().isEmpty())
			{
				lower.add(option.toLowerCase(Locale.ROOT).trim());
			}
		}
		return lower.isEmpty() ? Collections.singleton(ANY_OPTION) : Collections.unmodifiableSet(lower);
	}

	private static String key(String kind, String normalizedName)
	{
		return kind.toLowerCase(Locale.ROOT).trim() + '|' + normalizedName;
	}

	/** One gated interaction: which options it covers and which card groups it demands. */
	static final class Rule
	{
		final String category;
		private final Set<String> options;
		final List<CardGroup> groups;

		Rule(String category, Set<String> options, List<CardGroup> groups)
		{
			this.category = category;
			this.options = options;
			this.groups = Collections.unmodifiableList(groups);
		}

		boolean matchesOption(String lowerOption)
		{
			return options.contains(ANY_OPTION) || options.contains(lowerOption);
		}
	}

	/** Alternatives: owning any one of these cards satisfies the group. */
	static final class CardGroup
	{
		/** Original casing, for chat messages. */
		final List<String> displayCards;
		private final Set<String> normalizedCards;
		final String role;
		final String label;

		CardGroup(List<String> displayCards, Set<String> normalizedCards, String role, String label)
		{
			this.displayCards = displayCards;
			this.normalizedCards = normalizedCards;
			this.role = role;
			this.label = label;
		}

		boolean isSatisfied(java.util.function.Predicate<String> ownsNormalizedCard)
		{
			for (String card : normalizedCards)
			{
				if (ownsNormalizedCard.test(card))
				{
					return true;
				}
			}
			return false;
		}

		/** @return the group's label if it has one, else its first card name. */
		String describe()
		{
			if (label != null && !label.trim().isEmpty())
			{
				return label.trim();
			}
			return displayCards.isEmpty() ? "" : displayCards.get(0);
		}
	}

	private static final class RootDto
	{
		List<NodeDto> nodes;
	}

	/** Mirrors Bronzeman TCG's node schema exactly; unknown fields (notes, labels) are ignored. */
	private static final class NodeDto
	{
		String category;
		String kind;
		String name;
		List<String> options;
		List<String> requiredCards;
		List<List<String>> requiredCardGroups;
		List<String> groupRoles;
		List<String> groupLabels;
		boolean requireAll;
	}
}
