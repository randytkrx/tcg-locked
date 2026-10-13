/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TcgLiveCatalogServiceTest
{
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private MockWebServer server;
	private ScheduledExecutorService executor;
	private TcgLiveCatalogService service;
	private Path cache;
	private AtomicInteger changes;
	private java.util.concurrent.atomic.AtomicLong clock;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		executor = Executors.newSingleThreadScheduledExecutor();
		cache = folder.getRoot().toPath().resolve("cache/catalog.json");
		clock = new java.util.concurrent.atomic.AtomicLong(10_000L);
		service = new TcgLiveCatalogService(new OkHttpClient(), executor, cache,
			server.url("/api/v1/catalog/cards/live"), clock::get);
		changes = new AtomicInteger();
	}

	@After
	public void tearDown() throws Exception
	{
		service.stop();
		executor.shutdownNow();
		server.shutdown();
	}

	@Test
	public void disabledMakesNoRequestAndDoesNotLoadCache() throws Exception
	{
		Files.createDirectories(cache.getParent());
		Files.writeString(cache, TcgLiveCatalogSnapshotTest.fullCatalog("Cached "));
		service.start(false, changes::incrementAndGet);
		service.refresh();
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
		assertNull(service.snapshot());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void conditionalRefreshActivatesChangesAndRetainsGoodDataAfterFailure() throws Exception
	{
		server.enqueue(ok("First ", "\"one\""));
		service.start(true, changes::incrementAndGet);
		await(() -> service.snapshot() != null && Files.exists(cache));
		RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS);
		assertEquals("/api/v1/catalog/cards/live", first.getPath());
		assertNull(first.getHeader("If-None-Match"));
		assertTrue(first.getHeader("Cache-Control").contains("no-store"));
		int before = changes.get();
		service.refreshIfDue();
		assertEquals(1, server.getRequestCount());
		server.enqueue(new MockResponse().setResponseCode(304));
		service.refresh();
		assertEquals("\"one\"", server.takeRequest(5, TimeUnit.SECONDS).getHeader("If-None-Match"));
		// snapshot() takes the same monitor as completion; wait for the callback before another refresh.
		awaitRequestComplete();
		assertEquals(before, changes.get());
		server.enqueue(ok("Second ", "\"two\""));
		clock.addAndGet(TcgLiveCatalogService.REFRESH_MILLIS);
		service.refreshIfDue();
		await(() -> service.snapshot().allKeys.contains("second"));
		assertEquals(before + 1, changes.get());
		String saved = Files.readString(cache);
		server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"items\":[],\"npcs\":[]}"));
		service.refresh();
		awaitRequestComplete();
		assertTrue(service.snapshot().allKeys.contains("second"));
		assertEquals(saved, Files.readString(cache));
		assertEquals(before + 1, changes.get());
	}

	@Test
	public void cachedSnapshotSurvivesUnavailableEndpointAndReenable() throws Exception
	{
		Files.createDirectories(cache.getParent());
		Files.writeString(cache, TcgLiveCatalogSnapshotTest.fullCatalog("Cached "), StandardCharsets.UTF_8);
		server.enqueue(new MockResponse().setResponseCode(503));
		service.start(true, changes::incrementAndGet);
		await(() -> service.snapshot() != null);
		awaitRequestComplete();
		assertTrue(service.snapshot().allKeys.contains("cached"));
		service.setEnabled(false);
		assertNull(service.snapshot());
		server.enqueue(ok("New ", "\"new\""));
		service.setEnabled(true);
		await(() -> service.snapshot() != null && service.snapshot().allKeys.contains("new"));
		assertEquals(2, server.getRequestCount());
	}

	@Test
	public void rejectsUnsolicited304AndCorruptCache() throws Exception
	{
		Files.createDirectories(cache.getParent());
		Files.writeString(cache, "invalid");
		server.enqueue(new MockResponse().setResponseCode(304));
		service.start(true, changes::incrementAndGet);
		awaitRequestComplete();
		assertNull(service.snapshot());
		assertEquals("invalid", Files.readString(cache));
	}

	@Test
	public void disableCancelsInFlightAndLateResponseCannotActivate() throws Exception
	{
		server.enqueue(ok("Late ", "\"late\"").setBodyDelay(300, TimeUnit.MILLISECONDS));
		service.start(true, changes::incrementAndGet);
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		service.setEnabled(false);
		Thread.sleep(400);
		assertNull(service.snapshot());
		assertFalse(Files.exists(cache));
	}

	@Test
	public void cacheWriteFailureDoesNotDiscardValidatedResponse() throws Exception
	{
		Files.writeString(cache.getParent(), "blocking file");
		server.enqueue(ok("Working ", "\"working\""));
		service.start(true, changes::incrementAndGet);
		await(() -> service.snapshot() != null);
		assertTrue(service.snapshot().allKeys.contains("working"));
		assertFalse(Files.exists(cache));
	}

	@Test
	public void boundedReaderRejectsOversizedBody() throws Exception
	{
		assertThrows(java.io.IOException.class, () -> TcgLiveCatalogService.readLimited(
			new java.io.ByteArrayInputStream(new byte[TcgLiveCatalogService.MAX_BYTES + 1])));
	}

	@Test
	public void retryBackoffAndStopPreventFurtherRequests() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(503));
		service.start(true, changes::incrementAndGet);
		awaitRequestComplete();
		service.refreshIfDue();
		assertEquals(1, server.getRequestCount());
		clock.addAndGet(TimeUnit.MINUTES.toMillis(1));
		server.enqueue(ok("Recovered ", "\"recovered\""));
		service.refreshIfDue();
		await(() -> service.snapshot() != null);
		service.stop();
		clock.addAndGet(TcgLiveCatalogService.REFRESH_MILLIS);
		service.refreshIfDue();
		assertEquals(2, server.getRequestCount());
		assertNull(service.snapshot());
	}

	@Test
	public void mismatched304RetainsSnapshotAndRetriesWithoutEtag() throws Exception
	{
		server.enqueue(ok("First ", "\"one\""));
		service.start(true, changes::incrementAndGet);
		awaitRequestComplete();
		server.takeRequest(5, TimeUnit.SECONDS);
		server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"other\""));
		service.refresh();
		awaitRequestComplete();
		server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull(service.snapshot());
		server.enqueue(ok("Recovered ", "\"recovered\""));
		service.refresh();
		assertNull(server.takeRequest(5, TimeUnit.SECONDS).getHeader("If-None-Match"));
		awaitRequestComplete();
	}

	private MockResponse ok(String name, String etag)
	{
		return new MockResponse().setResponseCode(200).setHeader("ETag", etag)
			.setBody(TcgLiveCatalogSnapshotTest.fullCatalog(name));
	}

	private void awaitRequestComplete() throws Exception
	{
		await(() -> service.idle());
	}

	private static void await(BooleanSupplier condition) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
		{
			Thread.sleep(10);
		}
		assertTrue("asynchronous catalog operation did not complete", condition.getAsBoolean());
	}
}
