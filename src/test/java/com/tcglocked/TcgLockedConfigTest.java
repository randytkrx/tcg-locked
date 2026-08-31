/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Guards the Plugin Hub rule for retrofitting networking onto a published plugin: the network access
 * must sit behind a config Boolean, that Boolean must default to false, and the warning must live on
 * the config item rather than in runelite-plugin.properties.
 */
public class TcgLockedConfigTest
{
	private static final TcgLockedConfig DEFAULTS = new TcgLockedConfig()
	{
	};

	@Test
	public void groupSyncIsOffByDefault()
	{
		assertFalse(DEFAULTS.syncGroupCollection());
	}

	@Test
	public void groupSyncToggleCarriesTheWarning() throws Exception
	{
		ConfigItem item = TcgLockedConfig.class
			.getMethod(TcgLockedConfig.SYNC_GROUP_COLLECTION)
			.getAnnotation(ConfigItem.class);

		assertNotNull("the network toggle must be a real config item", item);
		assertEquals(TcgLockedConfig.SYNC_GROUP_COLLECTION, item.keyName());
		assertFalse("the warning belongs on this toggle", item.warning().isEmpty());
	}

	/**
	 * partyShare predates the networking and also drives RuneLite party sharing, which opens no
	 * connection of its own. It stays on by default on purpose; the opt-in lives on its own key so
	 * turning the network off does not silently switch party features off too.
	 */
	@Test
	public void partyShareStaysOnAndCarriesNoWarning() throws Exception
	{
		ConfigItem item = TcgLockedConfig.class.getMethod("partyShare").getAnnotation(ConfigItem.class);

		assertEquals(true, DEFAULTS.partyShare());
		assertEquals("", item.warning());
	}

	@Test
	public void manifestCarriesNoWarning() throws IOException
	{
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(findManifest(), StandardCharsets.UTF_8))
		{
			properties.load(reader);
		}

		assertNull("the warning must be on the config item, not in the manifest",
			properties.getProperty("warning"));
	}

	private static Path findManifest()
	{
		Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
		while (directory != null)
		{
			Path candidate = directory.resolve("runelite-plugin.properties");
			if (Files.isRegularFile(candidate))
			{
				return candidate;
			}
			directory = directory.getParent();
		}
		throw new AssertionError("runelite-plugin.properties not found from "
			+ System.getProperty("user.dir"));
	}
}
