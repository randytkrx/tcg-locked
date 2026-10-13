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

	/**
	 * item-on-object entries are keyed on the item being used, with the object name in the options
	 * list. Getting this the wrong way round silently disables every rule of that kind.
	 */
	private static final String BREAD_ON_RANGE = "{\"nodes\":[{\"category\":\"cooking\","
		+ "\"kind\":\"item-on-object\",\"name\":\"Bread dough\","
		+ "\"options\":[\"cooking range\",\"range\",\"stove\"],"
		+ "\"requiredCardGroups\":[[\"Bread dough\"],[\"Bread\"]]}]}";

	@Test
	public void itemOnObjectIsKeyedOnTheItemWithTheObjectAsOption()
	{
		assertEquals("Bread dough", gateOf(BREAD_ON_RANGE)
			.firstMissing("item-on-object", "Bread dough", "Cooking range", owning()));
	}

	@Test
	public void itemOnObjectAllowsWhenBothCardsOwned()
	{
		assertNull(gateOf(BREAD_ON_RANGE)
			.firstMissing("item-on-object", "Bread dough", "Range", owning("bread dough", "bread")));
	}

	@Test
	public void itemOnObjectIgnoresAnUnlistedObject()
	{
		// Bread cannot be cooked on an open fire, so that object is not in the options list.
		assertNull(gateOf(BREAD_ON_RANGE)
			.firstMissing("item-on-object", "Bread dough", "Fire", owning()));
	}

	@Test
	public void contextOnlyRolesDoNotCreateUnknowableRequirements()
	{
		String trap = "{\"nodes\":[{\"kind\":\"inventory\",\"name\":\"Bird snare\","
			+ "\"options\":[\"lay\"],\"requiredCardGroups\":[[\"Bird snare\"],[\"Crimson swift\"]],"
			+ "\"groupRoles\":[\"\",\"creature\"]}]}";
		assertNull(gateOf(trap).firstMissing("inventory", "Bird snare", "Lay", owning("bird snare")));
	}

	@Test
	public void slayerAssignmentListsDoNotBlockTheMaster()
	{
		String slayer = "{\"nodes\":[{\"kind\":\"npc\",\"name\":\"Steve\","
			+ "\"options\":[\"talk-to\"],\"requiredCardGroups\":[[\"Steve\"],[\"Abyssal demon\"]],"
			+ "\"groupRoles\":[\"master\",\"monsters\"]}]}";
		assertNull(gateOf(slayer).firstMissing("npc", "Steve", "Talk-to", owning("steve")));
	}

	/**
	 * loot roles describe an NPC's drop table, not a prerequisite. Enforcing them makes the reward
	 * for an action its own entry price, so a pickpocket needs the whole table before the first steal.
	 */
	@Test
	public void lootRolesDoNotGateThePickpocketThatDropsThem()
	{
		String thieving = "{\"nodes\":[{\"category\":\"pickpocketing\",\"kind\":\"npc\","
			+ "\"name\":\"Steve\",\"options\":[\"pickpocket\"],"
			+ "\"requiredCardGroups\":[[\"Coins\"],[\"Coin pouch\"],[\"Ham hood\"],[\"Bronze arrow\"]],"
			+ "\"groupRoles\":[\"\",\"\",\"loot\",\"loot-ham\"]}]}";
		assertNull(gateOf(thieving)
			.firstMissing("npc", "Steve", "Pickpocket", owning("coins", "coin pouch")));
	}

	@Test
	public void lootRolesStillLeaveTheCoreRequirementsEnforced()
	{
		String thieving = "{\"nodes\":[{\"category\":\"pickpocketing\",\"kind\":\"npc\","
			+ "\"name\":\"Steve\",\"options\":[\"pickpocket\"],"
			+ "\"requiredCardGroups\":[[\"Coins\"],[\"Coin pouch\"],[\"Bronze arrow\"]],"
			+ "\"groupRoles\":[\"\",\"\",\"loot\"]}]}";
		assertEquals("Coin pouch",
			gateOf(thieving).firstMissing("npc", "Steve", "Pickpocket", owning("coins")));
	}

	/**
	 * The worst case in the bundled catalog: 39 groups, 37 of them drop-table entries. Guards the
	 * real data rather than a fixture, because the roles come from the file and not from the code.
	 */
	@Test
	public void bundledCatalogLetsYouPickpocketHamMembersWithTheCoreCards()
	{
		TcgInteractionGate gate = new TcgInteractionGate(new TcgInteractionCatalog(GSON));
		assertNull(gate.firstMissing("npc", "H.A.M. Member", "Pickpocket",
			owning("coins", "coin pouch")));
	}

	@Test
	public void bundledCatalogStillRequiresTheCoreCardsToPickpocket()
	{
		TcgInteractionGate gate = new TcgInteractionGate(new TcgInteractionCatalog(GSON));
		assertEquals("Coins", gate.firstMissing("npc", "H.A.M. Member", "Pickpocket", owning()));
	}
}
