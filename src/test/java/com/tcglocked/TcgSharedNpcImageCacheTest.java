/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TcgSharedNpcImageCacheTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void cacheKeyMatchesOsrsTcgSha256Convention()
	{
		String url = "https://oldschool.runescape.wiki/images/thumb/"
			+ "Abyssal_demon.png/130px-Abyssal_demon.png";
		assertEquals(url, TcgSharedNpcImageCache.normalizeUrl("  " + url + "  "));
		assertEquals("f239f957f9ec12a97a33d9d0c28bff02629ef01d3ca24165a56ec1f678e008c8",
			TcgSharedNpcImageCache.sha256Hex(url));
	}

	@Test
	public void missingCachedPortraitReturnsPlaceholderWithoutNetwork() throws InterruptedException
	{
		TcgSharedNpcImageCache cache = new TcgSharedNpcImageCache(temporaryFolder.getRoot().toPath());
		CountDownLatch completed = new CountDownLatch(1);
		AtomicReference<BufferedImage> result = new AtomicReference<>();
		cache.start();
		cache.start();
		try
		{
			cache.get("cache-only://" + UUID.randomUUID(), image ->
			{
				result.set(image);
				completed.countDown();
			});
			assertTrue("cache miss callback did not complete", completed.await(5, TimeUnit.SECONDS));
			assertNull(result.get());
		}
		finally
		{
			cache.dispose();
		}
	}

	@Test
	public void portraitAddedByOsrsTcgAfterMissIsPickedUp() throws IOException, InterruptedException
	{
		TcgSharedNpcImageCache cache = new TcgSharedNpcImageCache(temporaryFolder.getRoot().toPath());
		String url = "cache-only://" + UUID.randomUUID();
		cache.start();
		try
		{
			assertNull(await(cache, url));
			Files.createDirectories(cache.cacheFile(url).getParent());
			ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png",
				cache.cacheFile(url).toFile());
			assertEquals(2, await(cache, url).getWidth());
		}
		finally
		{
			cache.dispose();
		}
	}

	private static BufferedImage await(TcgSharedNpcImageCache cache, String url) throws InterruptedException
	{
		CountDownLatch completed = new CountDownLatch(1);
		AtomicReference<BufferedImage> result = new AtomicReference<>();
		cache.get(url, image ->
		{
			result.set(image);
			completed.countDown();
		});
		assertTrue("cache callback did not complete", completed.await(5, TimeUnit.SECONDS));
		return result.get();
	}
}
