/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TcgLockedPendingUnlocksTest
{
	@Test
	public void removedAndReaddedCardsCannotDuplicate()
	{
		Set<String> pending = new HashSet<>();
		assertTrue(TcgLockedPlugin.reconcilePendingUnlocks(pending, Set.of(), Set.of("a")));
		assertFalse(TcgLockedPlugin.reconcilePendingUnlocks(pending, Set.of("a"), Set.of()));
		assertTrue(pending.isEmpty());
		assertTrue(TcgLockedPlugin.reconcilePendingUnlocks(pending, Set.of(), Set.of("a")));
		assertFalse(TcgLockedPlugin.reconcilePendingUnlocks(pending, Set.of("a"), Set.of("a")));
		assertEquals(Set.of("a"), pending);
	}
}
