/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TcgSharedNpcImageCacheTest
{
	@Test
	public void cacheKeyMatchesOsrsTcgSha256Convention()
	{
		String url = "https://oldschool.runescape.wiki/images/thumb/"
			+ "Abyssal_demon.png/130px-Abyssal_demon.png";
		assertEquals(url, TcgSharedNpcImageCache.normalizeUrl("  " + url + "  "));
		assertEquals("f239f957f9ec12a97a33d9d0c28bff02629ef01d3ca24165a56ec1f678e008c8",
			TcgSharedNpcImageCache.sha256Hex(url));
	}
}
