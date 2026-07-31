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

import com.google.gson.Gson;
import java.io.StringReader;
import java.util.Set;
import java.util.function.Predicate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TcgInteractionCatalogTest
{
	private static final Gson GSON = new Gson();

	private static TcgInteractionCatalog of(String json)
	{
		return new TcgInteractionCatalog(GSON, new StringReader(json));
	}

	private static Predicate<String> owning(String... normalizedCards)
	{
		Set<String> owned = Set.of(normalizedCards);
		return owned::contains;
	}

	@Test
	public void bundledCatalogLoads()
	{
		// The shipped file must parse, or every activity silently stops being gated.
		TcgInteractionCatalog catalog = new TcgInteractionCatalog(GSON);
		assertTrue("bundled resource-nodes.json parsed no targets", catalog.size() > 300);
	}

	@Test
	public void bundledCatalogGatesAKnownNode()
	{
		TcgInteractionCatalog catalog = new TcgInteractionCatalog(GSON);
		TcgInteractionCatalog.Rule rule = catalog.find(
			TcgInteractionCatalog.KIND_INTERFACE, "Anchovies", "cook");
		assertNotNull("expected the Anchovies cooking rule from the bundled catalog", rule);
		assertEquals(2, rule.groups.size());
	}

	@Test
	public void groupIsSatisfiedByAnyOneAlternative()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":[{\"kind\":\"object\",\"name\":\"Yew tree\","
			+ "\"options\":[\"chop down\"],\"requiredCardGroups\":[[\"Yew logs\",\"Yew\"]]}]}");
		TcgInteractionCatalog.Rule rule = catalog.find("object", "Yew tree", "Chop down");
		assertNotNull(rule);
		assertTrue(rule.groups.get(0).isSatisfied(owning("yew")));
		assertTrue(rule.groups.get(0).isSatisfied(owning("yew logs")));
		assertTrue(!rule.groups.get(0).isSatisfied(owning("oak logs")));
	}

	@Test
	public void optionWildcardMatchesAnything()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":[{\"kind\":\"npc\",\"name\":\"Man\","
			+ "\"options\":[\"*\"],\"requiredCardGroups\":[[\"Coins\"]]}]}");
		assertNotNull(catalog.find("npc", "Man", "Pickpocket"));
		assertNotNull(catalog.find("npc", "Man", "Talk-to"));
	}

	@Test
	public void nonMatchingOptionIsNotGated()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":[{\"kind\":\"object\",\"name\":\"Yew tree\","
			+ "\"options\":[\"chop down\"],\"requiredCardGroups\":[[\"Yew logs\"]]}]}");
		assertNull(catalog.find("object", "Yew tree", "Examine"));
	}

	@Test
	public void legacyRequireAllSplitsCardsIntoSeparateGroups()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":[{\"kind\":\"object\",\"name\":\"Range\","
			+ "\"options\":[\"*\"],\"requiredCards\":[\"Raw shrimps\",\"Shrimps\"],\"requireAll\":true}]}");
		TcgInteractionCatalog.Rule rule = catalog.find("object", "Range", "cook");
		assertNotNull(rule);
		assertEquals("requireAll should make each card its own group", 2, rule.groups.size());
	}

	@Test
	public void legacyRequireAnyCollapsesCardsIntoOneGroup()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":[{\"kind\":\"object\",\"name\":\"Range\","
			+ "\"options\":[\"*\"],\"requiredCards\":[\"Raw shrimps\",\"Shrimps\"],\"requireAll\":false}]}");
		TcgInteractionCatalog.Rule rule = catalog.find("object", "Range", "cook");
		assertNotNull(rule);
		assertEquals(1, rule.groups.size());
		assertTrue(rule.groups.get(0).isSatisfied(owning("shrimps")));
	}

	@Test
	public void malformedJsonLeavesCatalogEmptyRatherThanThrowing()
	{
		// Never lock on data we could not read.
		assertEquals(0, of("{ this is not json").size());
	}

	@Test
	public void nodesWithNoRequirementsAreSkipped()
	{
		assertEquals(0, of("{\"nodes\":[{\"kind\":\"object\",\"name\":\"Tree\",\"options\":[\"*\"]}]}").size());
	}

	@Test
	public void groupLabelIsPreferredOverCardNameWhenDescribing()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":[{\"kind\":\"object\",\"name\":\"Range\","
			+ "\"options\":[\"*\"],\"requiredCardGroups\":[[\"Raw shrimps\"]],\"groupLabels\":[\"the raw fish\"]}]}");
		TcgInteractionCatalog.Rule rule = catalog.find("object", "Range", "cook");
		assertNotNull(rule);
		assertEquals("the raw fish", rule.groups.get(0).describe());
	}

	@Test
	public void targetIdSelectsTheMatchingDuplicateRule()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":["
			+ "{\"kind\":\"object\",\"name\":\"Young tree\",\"options\":[\"set-trap\"],"
			+ "\"objectIds\":[1],\"requiredCards\":[\"Swamp lizard\"]},"
			+ "{\"kind\":\"object\",\"name\":\"Young tree\",\"options\":[\"set-trap\"],"
			+ "\"objectIds\":[2],\"requiredCards\":[\"Orange salamander\"]}]}");
		TcgInteractionCatalog.Rule rule = catalog.find("object", "Young tree", "Set-trap", 2);
		assertNotNull(rule);
		assertTrue(rule.groups.get(0).isSatisfied(owning("orange salamander")));
	}

	@Test
	public void conflictingDuplicateWithoutMatchingIdFailsOpen()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":["
			+ "{\"kind\":\"object\",\"name\":\"Young tree\",\"options\":[\"set-trap\"],"
			+ "\"objectIds\":[1],\"requiredCards\":[\"Swamp lizard\"]},"
			+ "{\"kind\":\"object\",\"name\":\"Young tree\",\"options\":[\"set-trap\"],"
			+ "\"objectIds\":[2],\"requiredCards\":[\"Orange salamander\"]}]}");
		assertNull(catalog.find("object", "Young tree", "Set-trap", 99));
	}

	@Test
	public void exactOptionTakesPriorityOverWildcard()
	{
		TcgInteractionCatalog catalog = of("{\"nodes\":["
			+ "{\"kind\":\"npc\",\"name\":\"Man\",\"options\":[\"*\"],\"requiredCards\":[\"Coins\"]},"
			+ "{\"kind\":\"npc\",\"name\":\"Man\",\"options\":[\"pickpocket\"],"
			+ "\"requiredCards\":[\"Man\"]}]}");
		TcgInteractionCatalog.Rule rule = catalog.find("npc", "Man", "Pickpocket");
		assertTrue(rule.groups.get(0).isSatisfied(owning("man")));
	}
}
