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
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), server.url("/"));
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
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), server.url("/"));
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
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.GroupCollection> callback = new CapturingCallback<>();
			client.fetchCollection("EK8FKG", callback);

			assertTrue(callback.await());
			assertNotNull(callback.result);
			RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
			assertEquals("/api/v1/groups/EK8FKG/collection", request.getPath());
		}
	}

	@Test
	public void oversizedResponseIsRejected() throws Exception
	{
		try (MockWebServer server = new MockWebServer())
		{
			server.enqueue(new MockResponse().setBody(new String(new char[2 * 1024 * 1024 + 1])));
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), server.url("/"));
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
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), server.url("/"));
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
			TcgGroupCollectionClient client = new TcgGroupCollectionClient(new OkHttpClient(), server.url("/"));
			CapturingCallback<TcgGroupCollectionClient.Meta> callback = new CapturingCallback<>();
			assertNull(client.fetchMeta("not-a-code", callback));
			assertTrue(callback.failed);
			assertFalse(callback.result != null);
		}
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
}
