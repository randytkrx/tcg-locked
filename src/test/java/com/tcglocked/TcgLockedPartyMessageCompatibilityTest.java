/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import com.google.gson.Gson;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class TcgLockedPartyMessageCompatibilityTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void currentProgressPayloadRoundTrips()
	{
		TcgLockedPartyProgressMessage message = new TcgLockedPartyProgressMessage();
		message.setCardsOwned(12);
		message.setUnlocked(8);
		message.setSeen(20);
		message.setOwnedKeys(Set.of("rope", "shark"));
		message.setPackedOwnedKeys("2:catalog:zpayload");
		message.setSharedWith(Set.of("alice"));

		TcgLockedPartyProgressMessage decoded = GSON.fromJson(
			GSON.toJson(message), TcgLockedPartyProgressMessage.class);
		assertEquals(12, decoded.getCardsOwned());
		assertEquals(8, decoded.getUnlocked());
		assertEquals(20, decoded.getSeen());
		assertEquals(Set.of("rope", "shark"), decoded.getOwnedKeys());
		assertEquals("2:catalog:zpayload", decoded.getPackedOwnedKeys());
		assertEquals(Set.of("alice"), decoded.getSharedWith());
	}

	@Test
	public void legacyProgressPayloadRemainsReadable()
	{
		TcgLockedPartyProgressMessage decoded = GSON.fromJson(
			"{\"cardsOwned\":2,\"unlocked\":1,\"seen\":3,\"ownedKeys\":[\"rope\"]}",
			TcgLockedPartyProgressMessage.class);
		assertEquals(Set.of("rope"), decoded.getOwnedKeys());
		assertNull(decoded.getPackedOwnedKeys());
		assertNull(decoded.getSharedWith());
	}

	@Test
	public void withdrawalTargetRoundTrips()
	{
		TcgLockedPartyWithdrawMessage message = new TcgLockedPartyWithdrawMessage();
		message.setTarget("alice");
		TcgLockedPartyWithdrawMessage decoded = GSON.fromJson(
			GSON.toJson(message), TcgLockedPartyWithdrawMessage.class);
		assertEquals("alice", decoded.getTarget());
	}
}
