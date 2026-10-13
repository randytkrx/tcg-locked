/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Development launcher: runs RuneLite with this checkout loaded as a built-in plugin. */
public class TcgLockedPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TcgLockedPlugin.class);
		RuneLite.main(args);
	}
}
