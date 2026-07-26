package com.tcglocked;

import java.util.EnumSet;
import net.runelite.api.WorldType;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContentModeTest
{
	@Test
	public void autoFollowsWorldMembership()
	{
		assertTrue(ContentMode.AUTO.isF2p(EnumSet.noneOf(WorldType.class)));
		assertFalse(ContentMode.AUTO.isF2p(EnumSet.of(WorldType.MEMBERS)));
	}

	@Test
	public void forcedModesIgnoreWorldMembership()
	{
		assertTrue(ContentMode.F2P_ONLY.isF2p(EnumSet.of(WorldType.MEMBERS)));
		assertFalse(ContentMode.ALL_CONTENT.isF2p(EnumSet.noneOf(WorldType.class)));
	}
}
