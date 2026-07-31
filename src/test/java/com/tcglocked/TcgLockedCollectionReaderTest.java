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
}
