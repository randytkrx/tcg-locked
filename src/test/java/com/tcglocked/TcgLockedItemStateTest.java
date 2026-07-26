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

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TcgLockedItemStateTest
{
	@Test
	public void ownedPooledAndExemptItemsAreGreen()
	{
		assertGreen(TcgLockedStatus.UnlockSource.OWNED);
		assertGreen(TcgLockedStatus.UnlockSource.POOLED);
		assertGreen(TcgLockedStatus.UnlockSource.EXEMPT);
	}

	@Test
	public void lockedItemsAreRed()
	{
		TcgLockedItemState state = TcgLockedItemState.from(TcgLockedStatus.UnlockSource.LOCKED);
		assertEquals(TcgLockedItemState.LOCKED, state);
		assertEquals(TcgLockedHighlightColors.LOCKED, TcgLockedItemOverlay.outlineColor(state));
	}

	@Test
	public void uncardedItemsArePurple()
	{
		TcgLockedItemState state = TcgLockedItemState.from(TcgLockedStatus.UnlockSource.UNCARDED);
		assertEquals(TcgLockedItemState.UNTRACKED, state);
		assertEquals(TcgLockedHighlightColors.NO_CARD, TcgLockedItemOverlay.outlineColor(state));
	}

	@Test
	public void suspendedCollectionDoesNotClaimAnUnlock()
	{
		assertEquals(
			TcgLockedItemState.UNTRACKED,
			TcgLockedItemState.from(TcgLockedStatus.UnlockSource.SUSPENDED));
	}

	private static void assertGreen(TcgLockedStatus.UnlockSource source)
	{
		TcgLockedItemState state = TcgLockedItemState.from(source);
		assertEquals(TcgLockedItemState.UNLOCKED, state);
		assertEquals(TcgLockedHighlightColors.UNLOCKED, TcgLockedItemOverlay.outlineColor(state));
	}
}
