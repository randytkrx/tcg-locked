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

import java.util.Collections;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TcgLockedHighlightStateTest
{
	private final TcgCardCatalog catalog = new TcgCardCatalog();

	@Test
	public void itemSourcesMapToOutlineStates()
	{
		assertEquals(
			TcgLockedHighlightState.LOCKED,
			TcgLockedHighlightState.fromItemSource(TcgLockedStatus.UnlockSource.LOCKED));
		assertEquals(
			TcgLockedHighlightState.NO_CARD,
			TcgLockedHighlightState.fromItemSource(TcgLockedStatus.UnlockSource.UNCARDED));
		assertEquals(
			TcgLockedHighlightState.UNLOCKED,
			TcgLockedHighlightState.fromItemSource(TcgLockedStatus.UnlockSource.OWNED));
		assertEquals(
			TcgLockedHighlightState.UNLOCKED,
			TcgLockedHighlightState.fromItemSource(TcgLockedStatus.UnlockSource.POOLED));
		assertEquals(
			TcgLockedHighlightState.UNLOCKED,
			TcgLockedHighlightState.fromItemSource(TcgLockedStatus.UnlockSource.EXEMPT));
	}

	@Test
	public void npcCardsMapToOutlineStates()
	{
		assertEquals(
			TcgLockedHighlightState.UNLOCKED,
			npcState("Abyssal demon", Set.of("abyssal demon")));
		assertEquals(
			TcgLockedHighlightState.LOCKED,
			npcState("Abyssal demon", Collections.emptySet()));
		assertEquals(
			TcgLockedHighlightState.NO_CARD,
			npcState("Bob the Cat's imaginary friend", Collections.emptySet()));
	}

	@Test
	public void anyMatchingNpcCardUnlocksTheNpc()
	{
		assertEquals(
			TcgLockedHighlightState.UNLOCKED,
			npcState("Archer", Set.of("archer (burthorpe)")));
	}

	private TcgLockedHighlightState npcState(String npcName, Set<String> owned)
	{
		return TcgLockedHighlightState.forNpc(true, npcName, catalog, owned::contains);
	}
}
