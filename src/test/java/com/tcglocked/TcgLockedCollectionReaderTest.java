/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TcgLockedCollectionReaderTest
{
	@Test
	public void unknownAndKnownEmptyAreDistinct()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertNull(reader.snapshotOrNull());
		assertTrue(reader.onApiOwnedNames(Collections.emptyList()));
		assertEquals(Collections.emptySet(), reader.snapshotOrNull());
	}

	@Test
	public void validPayloadIsNormalizedAndImmutable()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiOwnedNames(Arrays.asList(" Abyssal whip ", "ABYSSAL WHIP", "Coins")));
		Set<String> snapshot = reader.snapshotOrNull();
		assertEquals(Set.of("abyssal whip", "coins"), snapshot);
		try
		{
			snapshot.add("knife");
			throw new AssertionError("snapshot should be immutable");
		}
		catch (UnsupportedOperationException expected)
		{
			// Expected.
		}
	}

	@Test
	public void malformedPayloadPreservesPreviousCollection()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiOwnedNames(Collections.singletonList("Coins")));
		assertFalse(reader.onApiOwnedNames(Arrays.asList("Abyssal whip", 42)));
		assertFalse(reader.onApiOwnedNames(Arrays.asList("Abyssal whip", " ")));
		assertEquals(Collections.singleton("coins"), reader.snapshotOrNull());
	}

	@Test
	public void v1IdsAreAppliedWithNamesAsOneSnapshot()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiSnapshot(Collections.singletonList("Manta ray"),
			Arrays.asList(391, 392L), Collections.singletonList(15220L)));
		assertTrue(reader.hasItemIds());
		assertTrue(reader.ownsItemId(391));
		assertTrue(reader.ownsItemId(392));
		assertTrue(reader.hasNpcIds());
		assertTrue(reader.ownsNpcId(15220));
		assertFalse(reader.ownsNpcId(6616));
	}

	@Test
	public void npcIdZeroIsAValidV1CatalogId()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiSnapshot(Collections.singletonList("Hans"),
			Collections.emptyList(), Collections.singletonList(0L)));
		assertTrue(reader.hasNpcIds());
		assertTrue(reader.ownsNpcId(0));
	}

	@Test
	public void betaPayloadKeepsIdLookupsUnavailable()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiOwnedNames(Collections.singletonList("Abyssal whip")));
		assertFalse(reader.hasItemIds());
		assertFalse(reader.hasNpcIds());
	}

	@Test
	public void malformedV1IdsPreserveWholePreviousSnapshot()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiSnapshot(Collections.singletonList("Coins"),
			Collections.singletonList(995L), Collections.singletonList(1L)));
		assertFalse(reader.onApiSnapshot(Collections.singletonList("Abyssal whip"),
			Arrays.asList(4151L, "bad"), Collections.emptyList()));
		assertEquals(Collections.singleton("coins"), reader.snapshotOrNull());
		assertTrue(reader.ownsItemId(995));
		assertTrue(reader.ownsNpcId(1));
	}

	@Test
	public void absentGroupKeyDoesNotOverwriteKnownGroup()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiGroupKey(true, "ab3def"));
		assertTrue(reader.isGroupKeyKnown());
		assertEquals("AB3DEF", reader.groupKeyOrNull());
		assertTrue(reader.onApiGroupKey(false, null));
		assertEquals("AB3DEF", reader.groupKeyOrNull());
	}

	@Test
	public void explicitNullGroupKeyClearsMembership()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiGroupKey(true, "AB3DEF"));
		assertTrue(reader.onApiGroupKey(true, null));
		assertTrue(reader.isGroupKeyKnown());
		assertNull(reader.groupKeyOrNull());
	}

	@Test
	public void profileInvalidationClearsEveryV1Identity()
	{
		TcgLockedCollectionReader reader = new TcgLockedCollectionReader();
		assertTrue(reader.onApiSnapshot(Collections.singletonList("Abyssal whip"),
			Collections.singletonList(4151L), Collections.singletonList(3021L)));
		assertTrue(reader.onApiGroupKey(true, "AB3DEF"));
		reader.invalidate();
		assertNull(reader.snapshotOrNull());
		assertFalse(reader.hasItemIds());
		assertFalse(reader.hasNpcIds());
		assertFalse(reader.isGroupKeyKnown());
		assertNull(reader.groupKeyOrNull());
	}
}
