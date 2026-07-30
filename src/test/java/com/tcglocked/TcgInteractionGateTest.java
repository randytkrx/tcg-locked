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
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class TcgInteractionGateTest
{
	private static final Gson GSON = new Gson();

	/** Cooking anchovies: an input group and an output group, both required. */
	private static final String COOKING = "{\"nodes\":[{\"category\":\"cooking\",\"kind\":\"interface\","
		+ "\"name\":\"Anchovies\",\"options\":[\"*\"],"
		+ "\"requiredCardGroups\":[[\"Raw anchovies\"],[\"Anchovies\"]],"
		+ "\"groupRoles\":[\"input\",\"output\"]}]}";

	private static TcgInteractionGate gateOf(String json)
	{
		return new TcgInteractionGate(new TcgInteractionCatalog(GSON, new StringReader(json)));
	}

	private static Predicate<String> owning(String... normalizedCards)
	{
		Set<String> owned = Set.of(normalizedCards);
		return owned::contains;
	}

	@Test
	public void unknownInteractionIsAllowed()
	{
		assertNull(gateOf(COOKING).firstMissing("object", "Yew tree", "Chop down", owning()));
	}

	@Test
	public void reportsTheFirstUnmetGroup()
	{
		assertEquals("Raw anchovies",
			gateOf(COOKING).firstMissing("interface", "Anchovies", "Cook", owning()));
	}

	@Test
	public void owningTheInputStillBlocksOnTheOutput()
	{
		// Both groups are required, so a partial collection must not open the activity.
		assertEquals("Anchovies",
			gateOf(COOKING).firstMissing("interface", "Anchovies", "Cook", owning("raw anchovies")));
	}

	@Test
	public void owningEveryGroupAllowsIt()
	{
		assertNull(gateOf(COOKING).firstMissing("interface", "Anchovies", "Cook",
			owning("raw anchovies", "anchovies")));
	}

	@Test
	public void emptyCatalogAllowsEverything()
	{
		assertNull(gateOf("{\"nodes\":[]}").firstMissing("interface", "Anchovies", "Cook", owning()));
	}

	@Test
	public void nullTargetIsAllowed()
	{
		assertNull(gateOf(COOKING).firstMissing("interface", null, "Cook", owning()));
	}
}
