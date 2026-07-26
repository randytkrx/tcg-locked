/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import com.google.common.io.ByteStreams;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Singleton
@Slf4j
final class TcgSharedCardImageCache
{
	private static final int MEMORY_IMAGES = 192;
	private static final long DISK_BYTES = 64L * 1024L * 1024L;
	private static final int IMAGE_WIDTH = 130;
	private static final int IMAGE_HEIGHT = 150;
	private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
	private static final long MAX_SOURCE_PIXELS = 16_000_000L;
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "TCG-Locked/card-images");

	private final OkHttpClient baseClient;
	private final Map<String, ImageIcon> memory = new LinkedHashMap<String, ImageIcon>(MEMORY_IMAGES, .75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ImageIcon> eldest)
		{
			return size() > MEMORY_IMAGES;
		}
	};
	private final Map<String, PendingRequest> pending = new LinkedHashMap<>();
	private ExecutorService executor;
	private OkHttpClient client;
	private Cache diskCache;
	private int generation;

	private static final class PendingRequest
	{
		private final int generation;
		private final Call call;
		private final List<Consumer<ImageIcon>> consumers = new ArrayList<>();

		private PendingRequest(int generation, Call call, Consumer<ImageIcon> consumer)
		{
			this.generation = generation;
			this.call = call;
			consumers.add(consumer);
		}
	}

	@Inject
	TcgSharedCardImageCache(OkHttpClient baseClient)
	{
		this.baseClient = baseClient;
	}

	synchronized void start()
	{
		if (client != null)
		{
			return;
		}
		generation++;
		if (!CACHE_DIR.exists() && !CACHE_DIR.mkdirs())
		{
			log.debug("TCG Locked: unable to create card image cache directory");
		}
		executor = Executors.newFixedThreadPool(4, runnable ->
		{
			Thread thread = new Thread(runnable, "tcg-shared-card-image");
			thread.setDaemon(true);
			return thread;
		});
		Dispatcher dispatcher = new Dispatcher(executor);
		dispatcher.setMaxRequests(4);
		dispatcher.setMaxRequestsPerHost(4);
		diskCache = new Cache(CACHE_DIR, DISK_BYTES);
		client = baseClient.newBuilder().dispatcher(dispatcher).cache(diskCache).build();
	}

	void get(String url, Consumer<ImageIcon> consumer)
	{
		if (consumer == null)
		{
			return;
		}
		if (url == null || url.trim().isEmpty())
		{
			SwingUtilities.invokeLater(() -> consumer.accept(null));
			return;
		}

		final Request request;
		try
		{
			request = new Request.Builder()
				.url(url)
				.header("User-Agent", "tcg-locked (https://github.com/randytkrx/tcg-locked)")
				.build();
		}
		catch (IllegalArgumentException ex)
		{
			SwingUtilities.invokeLater(() -> consumer.accept(null));
			return;
		}

		final PendingRequest state;
		synchronized (this)
		{
			ImageIcon cached = memory.get(url);
			if (cached != null)
			{
				SwingUtilities.invokeLater(() -> consumer.accept(cached));
				return;
			}
			if (client == null)
			{
				SwingUtilities.invokeLater(() -> consumer.accept(null));
				return;
			}
			PendingRequest existing = pending.get(url);
			if (existing != null)
			{
				existing.consumers.add(consumer);
				return;
			}
			Call call = client.newCall(request);
			state = new PendingRequest(generation, call, consumer);
			pending.put(url, state);
		}

		state.call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				complete(url, state, null);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				ImageIcon icon = null;
				try (Response closed = response)
				{
					if (closed.isSuccessful() && closed.body() != null)
					{
						BufferedImage image = decodeImage(closed.body().byteStream());
						if (image != null)
						{
							double scale = Math.min((double) IMAGE_WIDTH / image.getWidth(),
								(double) IMAGE_HEIGHT / image.getHeight());
							int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
							int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
							Image scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
							icon = new ImageIcon(scaled);
						}
					}
				}
				catch (IOException | RuntimeException ex)
				{
					log.debug("TCG Locked: unable to decode card image {}", url, ex);
				}
				complete(url, state, icon);
			}
		});
	}

	static BufferedImage decodeImage(InputStream input) throws IOException
	{
		byte[] bytes = ByteStreams.toByteArray(ByteStreams.limit(input, MAX_RESPONSE_BYTES + 1L));
		if (bytes.length > MAX_RESPONSE_BYTES)
		{
			return null;
		}

		try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
		{
			if (imageInput == null)
			{
				return null;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
			if (!readers.hasNext())
			{
				return null;
			}
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(imageInput, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || (long) width * height > MAX_SOURCE_PIXELS)
				{
					return null;
				}
				return reader.read(0);
			}
			finally
			{
				reader.dispose();
			}
		}
	}

	private void complete(String url, PendingRequest state, ImageIcon icon)
	{
		List<Consumer<ImageIcon>> consumers;
		synchronized (this)
		{
			if (state.generation != generation || client == null || pending.get(url) != state)
			{
				return;
			}
			pending.remove(url);
			consumers = new ArrayList<>(state.consumers);
			if (icon != null)
			{
				memory.put(url, icon);
			}
		}
		if (!consumers.isEmpty())
		{
			SwingUtilities.invokeLater(() -> consumers.forEach(consumer -> consumer.accept(icon)));
		}
	}

	/** Cancels image work for cards no longer on the visible page. */
	synchronized void retainVisible(Set<String> urls)
	{
		Set<String> keep = urls == null ? java.util.Collections.emptySet() : urls;
		java.util.Iterator<Map.Entry<String, PendingRequest>> iterator = pending.entrySet().iterator();
		while (iterator.hasNext())
		{
			Map.Entry<String, PendingRequest> entry = iterator.next();
			if (!keep.contains(entry.getKey()))
			{
				iterator.remove();
				entry.getValue().call.cancel();
			}
		}
	}

	synchronized void dispose()
	{
		generation++;
		if (client != null)
		{
			client.dispatcher().cancelAll();
		}
		for (PendingRequest request : pending.values())
		{
			request.call.cancel();
		}
		pending.clear();
		client = null;
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
		if (diskCache != null)
		{
			try
			{
				diskCache.close();
			}
			catch (IOException ex)
			{
				log.debug("TCG Locked: unable to close card image cache", ex);
			}
			diskCache = null;
		}
		memory.clear();
	}
}
