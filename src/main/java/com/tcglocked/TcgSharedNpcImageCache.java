/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 *
 * Disk-cache compatibility is derived from OSRS TCG's WikiImageCacheService,
 * Copyright (c) 2026, Azderi, BSD 2-Clause. See THIRD-PARTY-NOTICES.md.
 */
package com.tcglocked;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/** Reads OSRS TCG's persistent NPC-art cache without making network requests. */
@Slf4j
@Singleton
final class TcgSharedNpcImageCache
{
	private static final int MEMORY_ENTRIES = 128;
	private static final int MAX_EDGE = 130;

	private final Map<String, BufferedImage> memory = new LinkedHashMap<String, BufferedImage>(
		MEMORY_ENTRIES + 1, .75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
		{
			return size() > MEMORY_ENTRIES;
		}
	};
	private final Map<String, List<Consumer<BufferedImage>>> pending = new LinkedHashMap<>();
	private ExecutorService executor;
	private int generation;

	@Inject
	TcgSharedNpcImageCache()
	{
	}

	synchronized void start()
	{
		generation++;
		if (executor == null)
		{
			executor = Executors.newFixedThreadPool(4, runnable ->
			{
				Thread thread = new Thread(runnable, "tcg-shared-npc-image");
				thread.setDaemon(true);
				return thread;
			});
		}
	}

	void get(String rawUrl, Consumer<BufferedImage> consumer)
	{
		String url = normalizeUrl(rawUrl);
		if (consumer == null || url.isEmpty())
		{
			return;
		}
		final BufferedImage cached;
		final ExecutorService currentExecutor;
		final int requestGeneration;
		final boolean first;
		synchronized (this)
		{
			cached = memory.get(url);
			if (cached != null)
			{
				currentExecutor = null;
				requestGeneration = generation;
				first = false;
			}
			else
			{
				currentExecutor = executor;
				requestGeneration = generation;
				List<Consumer<BufferedImage>> consumers = pending.computeIfAbsent(url, key -> new ArrayList<>());
				first = consumers.isEmpty();
				consumers.add(consumer);
			}
		}
		if (cached != null)
		{
			SwingUtilities.invokeLater(() -> consumer.accept(cached));
		}
		else if (first && currentExecutor != null)
		{
			currentExecutor.execute(() -> complete(url, requestGeneration, load(url)));
		}
	}

	private void complete(String url, int requestGeneration, BufferedImage image)
	{
		final List<Consumer<BufferedImage>> consumers;
		synchronized (this)
		{
			consumers = pending.remove(url);
			if (requestGeneration != generation || consumers == null)
			{
				return;
			}
			if (image != null)
			{
				memory.put(url, image);
			}
		}
		SwingUtilities.invokeLater(() ->
		{
			for (Consumer<BufferedImage> consumer : consumers)
			{
				consumer.accept(image);
			}
		});
	}

	private BufferedImage load(String url)
	{
		return readDisk(url);
	}

	private BufferedImage readDisk(String url)
	{
		Path file = cacheFile(url);
		if (!Files.isRegularFile(file))
		{
			return null;
		}
		try (InputStream in = Files.newInputStream(file))
		{
			return scale(ImageIO.read(in));
		}
		catch (IOException ex)
		{
			log.debug("TCG Locked: unable to read OSRS TCG image cache", ex);
			return null;
		}
	}

	private static BufferedImage scale(BufferedImage source)
	{
		if (source == null)
		{
			return null;
		}
		int edge = Math.max(source.getWidth(), source.getHeight());
		if (edge <= MAX_EDGE)
		{
			return source;
		}
		double factor = MAX_EDGE / (double) edge;
		int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
		int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = scaled.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, 0, 0, width, height, null);
		}
		finally
		{
			graphics.dispose();
		}
		return scaled;
	}

	private static Path cacheFile(String normalizedUrl)
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "images-v2",
			sha256Hex(normalizedUrl) + ".png");
	}

	static String normalizeUrl(String rawUrl)
	{
		return rawUrl == null ? "" : rawUrl.trim();
	}

	static String sha256Hex(String value)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				out.append(String.format("%02x", b & 0xff));
			}
			return out.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	synchronized void dispose()
	{
		generation++;
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
		pending.clear();
		memory.clear();
	}
}
