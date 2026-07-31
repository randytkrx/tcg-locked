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

public class TcgLockedPartyUnlockMessageTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void audienceRoundTripsAndLegacyPayloadRemainsReadable()
	{
		TcgLockedPartyUnlockMessage message = new TcgLockedPartyUnlockMessage();
		message.setItemName("Abyssal whip");
		message.setSharedWith(Set.of("alice"));
		TcgLockedPartyUnlockMessage decoded = GSON.fromJson(GSON.toJson(message), TcgLockedPartyUnlockMessage.class);
		assertEquals("Abyssal whip", decoded.getItemName());
		assertEquals(Set.of("alice"), decoded.getSharedWith());
		assertNull(GSON.fromJson("{\"itemName\":\"Coins\"}", TcgLockedPartyUnlockMessage.class)
			.getSharedWith());
	}
}
