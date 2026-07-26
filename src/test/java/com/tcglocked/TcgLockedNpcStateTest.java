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

public class TcgLockedNpcStateTest
{
	private final TcgCardCatalog catalog = new TcgCardCatalog();

	private TcgLockedNpcState resolve(
		boolean collectionKnown,
		String npcName,
		Set<String> owned,
		Set<String> eligible)
	{
		return TcgLockedNpcState.resolve(
			collectionKnown,
			npcName,
			catalog,
			eligible::contains,
			owned::contains);
	}

	@Test
	public void ownedCardedNpcIsUnlocked()
	{
		assertEquals(
			TcgLockedNpcState.UNLOCKED,
			resolve(true, "Abyssal demon", Set.of("abyssal demon"), Set.of("abyssal demon")));
	}

	@Test
	public void unownedCardedNpcIsLocked()
	{
		assertEquals(
			TcgLockedNpcState.LOCKED,
			resolve(true, "Abyssal demon", Collections.emptySet(), Set.of("abyssal demon")));
	}

	@Test
	public void anyMatchingAliasCardUnlocksNpc()
	{
		Set<String> eligible = Set.of("archer (ardougne)", "archer (burthorpe)");
		assertEquals(
			TcgLockedNpcState.UNLOCKED,
			resolve(true, "Archer", Set.of("archer (burthorpe)"), eligible));
	}

	@Test
	public void unknownCollectionAndUncardedNpcHaveNoUnlockMatch()
	{
		assertEquals(
			TcgLockedNpcState.UNTRACKED,
			resolve(false, "Abyssal demon", Set.of("abyssal demon"), Set.of("abyssal demon")));
		assertEquals(
			TcgLockedNpcState.UNTRACKED,
			resolve(true, "Bob the Cat's imaginary friend", Collections.emptySet(), Collections.emptySet()));
	}

	@Test
	public void npcOutsideCurrentContentModeIsNotHighlighted()
	{
		assertEquals(
			TcgLockedNpcState.UNTRACKED,
			resolve(true, "Abyssal demon", Collections.emptySet(), Collections.emptySet()));
	}
}
