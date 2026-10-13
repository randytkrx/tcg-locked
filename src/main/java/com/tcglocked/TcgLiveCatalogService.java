/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Public, profile-independent catalogue refresh. No collection, group or player data is sent. */
@Slf4j
@Singleton
final class TcgLiveCatalogService
{
	static final int MAX_BYTES = 4 * 1024 * 1024;
	static final long REFRESH_MILLIS = TimeUnit.MINUTES.toMillis(10);
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;
	private final Path cache;
	private final HttpUrl url;
	private final java.util.function.LongSupplier clock;
	private boolean running;
	private boolean enabled;
	private int generation;
	private Call request;
	private ScheduledFuture<?> timer;
	private TcgLiveCatalogSnapshot snapshot;
	private String etag;
	private long nextFetch;
	private int failures;
	private Runnable listener = () -> { };

	@Inject
	TcgLiveCatalogService(OkHttpClient http, ScheduledExecutorService executor)
	{
		this(http, executor, RuneLite.RUNELITE_DIR.toPath().resolve("tcg-locked/live-catalog.json"),
			HttpUrl.parse("https://osrs-tcg.net/api/v1/catalog/cards/live"));
	}

	TcgLiveCatalogService(OkHttpClient http, ScheduledExecutorService executor, Path cache, HttpUrl url)
	{
		this(http, executor, cache, url, System::currentTimeMillis);
	}

	TcgLiveCatalogService(OkHttpClient http, ScheduledExecutorService executor, Path cache, HttpUrl url,
		java.util.function.LongSupplier clock)
	{
		this.http = http;
		this.executor = executor;
		this.cache = cache;
		this.url = url;
		this.clock = clock;
	}

	synchronized void start(boolean enabled, Runnable listener)
	{
		stop();
		running = true;
		this.listener = listener;
		setEnabled(enabled);
	}

	synchronized void stop()
	{
		running = false;
		enabled = false;
		generation++;
		if (request != null)
		{
			request.cancel();
			request = null;
		}
		if (timer != null)
		{
			timer.cancel(false);
			timer = null;
		}
		snapshot = null;
		etag = null;
		listener = () -> { };
	}

	synchronized void setEnabled(boolean value)
	{
		if (!running || enabled == value)
		{
			return;
		}
		enabled = value;
		generation++;
		if (request != null)
		{
			request.cancel();
			request = null;
		}
		if (timer != null)
		{
			timer.cancel(false);
			timer = null;
		}
		if (enabled)
		{
			int current = generation;
			failures = 0;
			nextFetch = 0;
			executor.execute(() -> loadAndRefresh(current));
			timer = executor.scheduleWithFixedDelay(this::refreshIfDue, 1, 1, TimeUnit.MINUTES);
		}
		listener.run();
	}

	synchronized TcgLiveCatalogSnapshot snapshot()
	{
		return running && enabled ? snapshot : null;
	}

	synchronized boolean idle()
	{
		return request == null && nextFetch > 0;
	}

	private void loadAndRefresh(int current)
	{
		try
		{
			if (Files.isRegularFile(cache) && Files.size(cache) <= MAX_BYTES)
			{
				byte[] body;
				try (InputStream in = Files.newInputStream(cache))
				{
					body = readLimited(in);
				}
				TcgLiveCatalogSnapshot loaded = TcgLiveCatalogSnapshot.parse(new String(body, StandardCharsets.UTF_8));
				loaded.validateForActivation();
				synchronized (this)
				{
					if (!valid(current))
					{
						return;
					}
					if (snapshot == null)
					{
						snapshot = loaded;
						listener.run();
					}
					// Revalidate the public endpoint after loading disk. An ETag is used only for a
					// response this process actually holds, never trusted independently of its body.
				}
			}
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("TCG Locked: ignored unusable live catalog cache", ex);
		}
		synchronized (this)
		{
			if (valid(current))
			{
				refreshIfDue();
			}
		}
	}

	synchronized void refresh()
	{
		nextFetch = 0;
		refreshIfDue();
	}

	synchronized void refreshIfDue()
	{
		if (!running || !enabled || request != null || clock.getAsLong() < nextFetch)
		{
			return;
		}
		int current = generation;
		Request.Builder builder = new Request.Builder().url(url).get()
			.cacheControl(new CacheControl.Builder().noCache().noStore().build());
		if (snapshot != null && etag != null)
		{
			builder.header("If-None-Match", etag);
		}
		request = http.newCall(builder.build());
		request.timeout().timeout(30, TimeUnit.SECONDS);
		request.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				failed(current, call, ex);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response ignored = response)
				{
					if (response.code() == 304)
					{
						synchronized (TcgLiveCatalogService.this)
						{
							if (!valid(current) || request != call)
							{
								return;
							}
							if (snapshot == null || etag == null)
							{
								throw new IOException("304 without a validated catalog");
							}
							String returnedEtag = response.header("ETag");
							if (returnedEtag != null && !returnedEtag.equals(etag))
							{
								etag = null;
								throw new IOException("304 refers to a different catalog");
							}
							completed();
						}
						return;
					}
					if (response.code() != 200 || response.body() == null
						|| response.body().contentLength() > MAX_BYTES)
					{
						throw new IOException("Invalid catalog HTTP response " + response.code());
					}
					byte[] body = readLimited(response.body().byteStream());
					TcgLiveCatalogSnapshot parsed = TcgLiveCatalogSnapshot.parse(new String(body, StandardCharsets.UTF_8));
					parsed.validateForActivation();
					synchronized (TcgLiveCatalogService.this)
					{
						if (!valid(current) || request != call)
						{
							return;
						}
						String nextEtag = response.header("ETag");
						boolean changed = snapshot == null || nextEtag == null || !nextEtag.equals(etag);
						if (changed)
						{
							snapshot = parsed;
						}
						etag = validEtag(nextEtag) ? nextEtag : null;
						completed();
						try
						{
							writeCache(body);
						}
						catch (IOException ex)
						{
							log.debug("TCG Locked: live catalog is active but could not be cached", ex);
						}
						if (changed)
						{
							log.debug("TCG Locked: validated live catalog ({} items, {} NPCs)", parsed.itemCount, parsed.npcCount);
							listener.run();
						}
					}
				}
				catch (IOException | RuntimeException ex)
				{
					failed(current, call, ex);
				}
			}
		});
	}

	private boolean valid(int current)
	{
		return running && enabled && generation == current;
	}

	private void completed()
	{
		request = null;
		failures = 0;
		nextFetch = clock.getAsLong() + REFRESH_MILLIS;
	}

	private synchronized void failed(int current, Call call, Exception ex)
	{
		if (!valid(current) || request != call)
		{
			return;
		}
		request = null;
		failures = Math.min(failures + 1, 5);
		nextFetch = clock.getAsLong() + Math.min(REFRESH_MILLIS,
			TimeUnit.MINUTES.toMillis(1L << (failures - 1)));
		log.debug("TCG Locked: live catalog unavailable; retaining last working catalog", ex);
	}

	static byte[] readLimited(InputStream in) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int count;
		while ((count = in.read(buffer)) != -1)
		{
			if (out.size() + count > MAX_BYTES)
			{
				throw new IOException("Live catalog exceeds size limit");
			}
			out.write(buffer, 0, count);
		}
		return out.toByteArray();
	}

	private static boolean validEtag(String value)
	{
		return value != null && value.length() <= 256
			&& value.matches("(?:W/)?\"[^\"\\r\\n]+\"");
	}

	private void writeCache(byte[] body) throws IOException
	{
		Files.createDirectories(cache.getParent());
		Path temp = Files.createTempFile(cache.getParent(), "live-catalog-", ".tmp");
		try
		{
			Files.write(temp, body);
			try
			{
				Files.move(temp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException ex)
			{
				// Preserve the old cache if atomic replacement is unavailable.
				throw new IOException("Atomic catalog cache replacement unavailable", ex);
			}
		}
		finally
		{
			Files.deleteIfExists(temp);
		}
	}
}
