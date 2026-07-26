package com.tcglocked;

import java.util.Collection;
import net.runelite.api.WorldType;

public enum ContentMode
{
	AUTO("Auto (match world)"),
	F2P_ONLY("F2P only"),
	ALL_CONTENT("All content");

	private final String label;

	ContentMode(String label)
	{
		this.label = label;
	}

	boolean isF2p(Collection<WorldType> worldTypes)
	{
		if (this == F2P_ONLY)
		{
			return true;
		}
		if (this == ALL_CONTENT)
		{
			return false;
		}
		return worldTypes == null || !worldTypes.contains(WorldType.MEMBERS);
	}

	@Override
	public String toString()
	{
		return label;
	}
}
