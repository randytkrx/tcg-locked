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

import java.util.List;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Guards the widget scoping. Interface entries are named after products ("Bread", "Anchovies"),
 * and bank, shop and Grand Exchange clicks carry those very same target strings, so a widget
 * mapping that is too broad locks a player out of their own bank.
 */
public class TcgInteractionKindMappingTest
{
	private static int component(int group)
	{
		return group << 16;
	}

	@Test
	public void bankWidgetClicksAreNotGated()
	{
		// The bank is not a Make-X dialog; withdrawing or depositing "Bread" must stay untouched.
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.CC_OP, component(InterfaceID.BANKMAIN)).isEmpty());
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.CC_OP, component(InterfaceID.BANKSIDE)).isEmpty());
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.CC_OP, component(InterfaceID.BANK_DEPOSITBOX)).isEmpty());
	}

	@Test
	public void shopWidgetClicksAreNotGated()
	{
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.CC_OP, component(InterfaceID.SHOPMAIN)).isEmpty());
	}

	@Test
	public void chatboxClicksAreNotGated()
	{
		// Grand Exchange search results live in the chatbox.
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.CC_OP, component(InterfaceID.CHATBOX)).isEmpty());
	}

	@Test
	public void makeXDialogsUseInterfaceRules()
	{
		assertEquals(List.of(TcgInteractionCatalog.KIND_INTERFACE),
			TcgLockedPlugin.kindsFor(MenuAction.CC_OP, component(InterfaceID.SKILLMULTI)));
		assertEquals(List.of(TcgInteractionCatalog.KIND_INTERFACE),
			TcgLockedPlugin.kindsFor(MenuAction.CC_OP, component(InterfaceID.SMITHING)));
	}

	@Test
	public void inventoryOpsUseInventoryRules()
	{
		assertEquals(List.of(TcgInteractionCatalog.KIND_INVENTORY),
			TcgLockedPlugin.kindsFor(MenuAction.ITEM_FIRST_OPTION, component(InterfaceID.INVENTORY)));
	}

	@Test
	public void widgetActionsWithNoWidgetAreNotGated()
	{
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.CC_OP, 0).isEmpty());
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.CC_OP, -1).isEmpty());
	}

	@Test
	public void worldActionsAreUnaffectedByWidgetId()
	{
		assertEquals(List.of(TcgInteractionCatalog.KIND_OBJECT),
			TcgLockedPlugin.kindsFor(MenuAction.GAME_OBJECT_FIRST_OPTION, 0));
		assertEquals(List.of(TcgInteractionCatalog.KIND_NPC, TcgInteractionCatalog.KIND_FISHING_SPOT),
			TcgLockedPlugin.kindsFor(MenuAction.NPC_FIRST_OPTION, 0));
	}

	@Test
	public void unrelatedActionsAreNotGated()
	{
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.WALK, 0).isEmpty());
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.EXAMINE_OBJECT, 0).isEmpty());
		assertTrue(TcgLockedPlugin.kindsFor(MenuAction.CANCEL, 0).isEmpty());
	}
}
