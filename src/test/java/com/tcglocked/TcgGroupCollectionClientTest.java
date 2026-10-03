/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TcgGroupCollectionClientTest
{
	@Test
	public void groupKeysAreValidatedAndCanonicalized()
	{
		assertEquals("AB3DEF", TcgGroupCollectionClient.normalizeGroupKey(" ab3def "));
		assertNull(TcgGroupCollectionClient.normalizeGroupKey("short"));
		assertNull(TcgGroupCollectionClient.normalizeGroupKey("AB-DEF"));
	}

	@Test
	public void metaRequiresIntegralNonNegativeValues()
	{
		TcgGroupCollectionClient.Meta meta = TcgGroupCollectionClient.parseMeta(
			"{\"name\":null,\"revision\":26,\"memberCount\":1}");
		assertEquals(26, meta.revision);
		assertEquals(1, meta.memberCount);
		assertNull(TcgGroupCollectionClient.parseMeta("{\"revision\":1.5,\"memberCount\":1}"));
		assertNull(TcgGroupCollectionClient.parseMeta("{\"revision\":-1,\"memberCount\":1}"));
	}

	@Test
	public void collectionBuildsOwnedUnionAndKeepsCollisionIdentity()
	{
		String json = "{\"revision\":26,\"cards\":["
			+ "{\"cardName\":\"Abyssal whip\",\"owners\":[{\"displayName\":\"Azu\",\"foil\":false,\"beta\":false}]},"
			+ "{\"cardName\":\"npc:15220\",\"owners\":[{\"displayName\":\"Azu\",\"foil\":true,\"beta\":false}]},"
			+ "{\"cardName\":\"Unowned\",\"owners\":[]}]}";
		TcgGroupCollectionClient.GroupCollection collection =
			TcgGroupCollectionClient.parseCollection(json);
		assertEquals(26, collection.revision);
		assertEquals(Set.of("abyssal whip", "npc:15220"), collection.cardKeys);
		assertEquals(Set.of("Azu"), collection.memberNames);
	}

	@Test
	public void betaNamesAcceptsOwnedAndEmptyCollections()
	{
		TcgGroupCollectionClient.BetaNames owned = TcgGroupCollectionClient.parseBetaNames(
			"{\"displayName\":\"Player Name\",\"revision\":123,"
				+ "\"cardNames\":[\"Logs\",\"Uncut sapphire\",\"Logs\"]}");
		assertEquals("Player Name", owned.displayName);
		assertEquals(123, owned.revision);
		assertEquals(Set.of("logs", "uncut sapphire"), owned.cardKeys);

		TcgGroupCollectionClient.BetaNames empty = TcgGroupCollectionClient.parseBetaNames(
			"{\"displayName\":\"Player Name\",\"revision\":124,\"cardNames\":[]}");
		assertTrue(empty.cardKeys.isEmpty());
	}

	@Test
	public void malformedBetaNamesAreRejected()
	{
		assertNull(TcgGroupCollectionClient.parseBetaNames(
			"{\"displayName\":\"Player\",\"revision\":1}"));
		assertNull(TcgGroupCollectionClient.parseBetaNames(
			"{\"displayName\":\"Player\",\"revision\":1.5,\"cardNames\":[]}"));
		assertNull(TcgGroupCollectionClient.parseBetaNames(
			"{\"displayName\":\"Player\",\"revision\":1,\"cardNames\":[42]}"));
	}

	@Test
	public void malformedCollectionIsRejected()
	{
		assertNull(TcgGroupCollectionClient.parseCollection("{\"revision\":26}"));
		assertNull(TcgGroupCollectionClient.parseCollection(
			"{\"revision\":26,\"cards\":[{\"cardName\":42,\"owners\":[]}]}"));
	}

	@Test
	public void metaRequestUsesPublicGroupPath() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setBody("{\"revision\":26,\"memberCount\":1}"));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.Meta> callback = new CapturingCallback<>();
			client.fetchMeta("EK8FKG", callback);

			assertTrue(callback.await());
			assertNotNull(callback.result);
			RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
			assertEquals("GET", request.getMethod());
			assertEquals("/api/v1/groups/EK8FKG/meta", request.getPath());
		}
	}

	@Test
	public void rateLimitResponseRetainsFailureSemantics() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(429));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.Meta> callback = new CapturingCallback<>();
			client.fetchMeta("EK8FKG", callback);

			assertTrue(callback.await());
			assertTrue(callback.failed);
			assertNull(callback.result);
		}
	}

	@Test
	public void collectionRequestUsesPublicGroupPath() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setBody("{\"revision\":26,\"cards\":[]}"));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.GroupCollection> callback = new CapturingCallback<>();
			client.fetchCollection("EK8FKG", callback);

			assertTrue(callback.await());
			assertNotNull(callback.result);
			RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
			assertEquals("/api/v1/groups/EK8FKG/collection", request.getPath());
		}
	}

	@Test
	public void betaRequestDoublesUnderscoresBeforeEncoding() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setBody(
				"{\"displayName\":\"TCG_Bruh Name\",\"revision\":1,\"cardNames\":[]}"));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(
				new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingBetaCallback callback = new CapturingBetaCallback();
			client.fetchBetaNames("TCG_Bruh Name", callback);

			assertTrue(callback.await());
			assertNotNull(callback.result);
			RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
			assertEquals("/api/v1/players/TCG__Bruh%20Name/beta-names", request.getPath());
		}
	}

	@Test
	public void missingBetaPlayerIsDistinctFromFailure() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(404).setBody(
				"{\"error\":{\"code\":\"player_not_found\",\"message\":\"Player not found\"}}"));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(
				new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingBetaCallback callback = new CapturingBetaCallback();
			client.fetchBetaNames("Missing", callback);

			assertTrue(callback.await());
			assertTrue(callback.notFound);
			assertFalse(callback.failed);
		}
	}

	@Test
	public void unrelatedBeta404IsFailure() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setResponseCode(404).setBody(
				"{\"error\":{\"code\":\"other\"}}"));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(
				new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingBetaCallback callback = new CapturingBetaCallback();
			client.fetchBetaNames("Missing", callback);

			assertTrue(callback.await());
			assertTrue(callback.failed);
			assertFalse(callback.notFound);
		}
	}

	@Test
	public void oversizedResponseIsRejected() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setBody(new String(new char[2 * 1024 * 1024 + 1])));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.Meta> callback = new CapturingCallback<>();
			client.fetchMeta("EK8FKG", callback);

			assertTrue(callback.await());
			assertTrue(callback.failed);
		}
	}

	@Test
	public void inFlightRequestCanBeCancelled() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.Meta> callback = new CapturingCallback<>();
			Call call = client.fetchMeta("EK8FKG", callback);
			assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
			call.cancel();

			assertTrue(callback.await());
			assertTrue(callback.failed);
			assertTrue(call.isCanceled());
		}
	}

	@Test
	public void invalidKeyDoesNotIssueARequest() throws IOException
	{
		try (MockWebServer server = new MockWebServer())
		{
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.Meta> callback = new CapturingCallback<>();
			assertNull(client.fetchMeta("not-a-code", callback));
			assertTrue(callback.failed);
			assertFalse(callback.result != null);
		}
	}

	/**
	 * The Plugin Hub requirement this guards: with the config Boolean off nothing may leave the
	 * client. The server would answer happily if a request arrived, so an empty request count is
	 * proof the gate held rather than proof the endpoint was missing.
	 */
	@Test
	public void sendsNothingWhileGroupSyncIsDisabled() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setBody("{\"revision\":26,\"memberCount\":1}"));
			server.enqueue(new MockResponse().setBody("{\"revision\":26,\"cards\":[]}"));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(
				new OkHttpClient(), syncEnabled(false), server.url("/"));

			CapturingCallback<TcgGroupCollectionClient.Meta> meta = new CapturingCallback<>();
			assertNull(client.fetchMeta("EK8FKG", meta));
			assertTrue(meta.await());
			assertTrue(meta.failed);

			CapturingCallback<TcgGroupCollectionClient.GroupCollection> collection = new CapturingCallback<>();
			assertNull(client.fetchCollection("EK8FKG", collection));
			assertTrue(collection.await());
			assertTrue(collection.failed);

			CapturingBetaCallback beta = new CapturingBetaCallback();
			assertNull(client.fetchBetaNames("Player", beta));
			assertTrue(beta.await());
			assertTrue(beta.failed);

			assertEquals("no request may reach osrs-tcg.net while the toggle is off",
				0, server.getRequestCount());
		}
	}

	/** Positive control: the gate must switch the feature off, not delete it. */
	@Test
	public void sendsTheRequestOnceGroupSyncIsEnabled() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setBody("{\"revision\":26,\"memberCount\":1}"));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(
				new OkHttpClient(), syncEnabled(true), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.Meta> callback = new CapturingCallback<>();

			assertNotNull(client.fetchMeta("EK8FKG", callback));

			assertTrue(callback.await());
			assertNotNull(callback.result);
			assertEquals(1, server.getRequestCount());
		}
	}

	private static TcgLockedConfig syncEnabled(boolean enabled)
	{
		return new TcgLockedConfig()
		{
			@Override
			public boolean syncGroupCollection()
			{
				return enabled;
			}
		};
	}

	private static final class CapturingCallback<T> implements TcgGroupCollectionClient.ResultCallback<T>
	{
		private final CountDownLatch done = new CountDownLatch(1);
		private T result;
		private boolean failed;

		@Override
		public void onSuccess(T result)
		{
			this.result = result;
			done.countDown();
		}

		@Override
		public void onFailure()
		{
			failed = true;
			done.countDown();
		}

		private boolean await() throws InterruptedException
		{
			return done.await(2, TimeUnit.SECONDS);
		}
	}

	private static final class CapturingBetaCallback implements TcgGroupCollectionClient.BetaNamesCallback
	{
		private final CountDownLatch done = new CountDownLatch(1);
		private TcgGroupCollectionClient.BetaNames result;
		private boolean failed;
		private boolean notFound;

		@Override
		public void onSuccess(TcgGroupCollectionClient.BetaNames result)
		{
			this.result = result;
			done.countDown();
		}

		@Override
		public void onPlayerNotFound()
		{
			notFound = true;
			done.countDown();
		}

		@Override
		public void onFailure()
		{
			failed = true;
			done.countDown();
		}

		private boolean await() throws InterruptedException
		{
			return done.await(2, TimeUnit.SECONDS);
		}
	}
}
