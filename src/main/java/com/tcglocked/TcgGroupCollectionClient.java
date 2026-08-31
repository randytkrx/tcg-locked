/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Reads the public OSRS TCG group revision and combined collection. */
@Singleton
final class TcgGroupCollectionClient
{
	private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
	private static final int MAX_CARDS = 15_000;
	private static final int MAX_CARD_NAME_LENGTH = 128;
	private static final HttpUrl DEFAULT_BASE = HttpUrl.parse("https://osrs-tcg.net/");

	interface ResultCallback<T>
	{
		void onSuccess(T result);

		void onFailure();
	}

	static final class Meta
	{
		final int revision;
		final int memberCount;

		private Meta(int revision, int memberCount)
		{
			this.revision = revision;
			this.memberCount = memberCount;
		}
	}

	static final class GroupCollection
	{
		final int revision;
		final Set<String> cardKeys;

		private GroupCollection(int revision, Set<String> cardKeys)
		{
			this.revision = revision;
			this.cardKeys = cardKeys;
		}
	}

	private final OkHttpClient httpClient;
	private final HttpUrl baseUrl;

	@Inject
	TcgGroupCollectionClient(OkHttpClient httpClient)
	{
		this(httpClient, DEFAULT_BASE);
	}

	TcgGroupCollectionClient(OkHttpClient httpClient, HttpUrl baseUrl)
	{
		this.httpClient = httpClient;
		this.baseUrl = baseUrl;
	}

	Call fetchMeta(String rawGroupKey, ResultCallback<Meta> callback)
	{
		String groupKey = normalizeGroupKey(rawGroupKey);
		if (groupKey == null)
		{
			callback.onFailure();
			return null;
		}
		return enqueue(url(groupKey, "meta"), callback, TcgGroupCollectionClient::parseMeta);
	}

	Call fetchCollection(String rawGroupKey, ResultCallback<GroupCollection> callback)
	{
		String groupKey = normalizeGroupKey(rawGroupKey);
		if (groupKey == null)
		{
			callback.onFailure();
			return null;
		}
		return enqueue(url(groupKey, "collection"), callback, TcgGroupCollectionClient::parseCollection);
	}

	private HttpUrl url(String groupKey, String endpoint)
	{
		return baseUrl.newBuilder()
			.addPathSegments("api/v1/groups")
			.addPathSegment(groupKey)
			.addPathSegment(endpoint)
			.build();
	}

	private <T> Call enqueue(HttpUrl url, ResultCallback<T> callback, Parser<T> parser)
	{
		Call call = httpClient.newCall(new Request.Builder().url(url).get().build());
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				callback.onFailure();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				T parsed;
				try (Response ignored = response)
				{
					if (!response.isSuccessful() || response.body() == null
						|| response.body().contentLength() > MAX_RESPONSE_BYTES)
					{
						callback.onFailure();
						return;
					}
					byte[] body = readLimited(response.body().byteStream());
					parsed = body == null ? null : parser.parse(new String(body, StandardCharsets.UTF_8));
					if (parsed == null)
					{
						callback.onFailure();
						return;
					}
				}
				catch (IOException | RuntimeException ex)
				{
					callback.onFailure();
					return;
				}
				callback.onSuccess(parsed);
			}
		});
		return call;
	}

	static Meta parseMeta(String json)
	{
		JsonObject root = object(json);
		Integer revision = integer(root, "revision");
		Integer memberCount = integer(root, "memberCount");
		if (revision == null || revision < 0 || memberCount == null || memberCount < 0)
		{
			return null;
		}
		return new Meta(revision, memberCount);
	}

	static GroupCollection parseCollection(String json)
	{
		JsonObject root = object(json);
		Integer revision = integer(root, "revision");
		JsonElement cardsElement = root == null ? null : root.get("cards");
		if (revision == null || revision < 0 || cardsElement == null || !cardsElement.isJsonArray())
		{
			return null;
		}
		JsonArray cards = cardsElement.getAsJsonArray();
		if (cards.size() > MAX_CARDS)
		{
			return null;
		}
		Set<String> keys = new HashSet<>();
		for (JsonElement element : cards)
		{
			if (!element.isJsonObject())
			{
				return null;
			}
			JsonObject card = element.getAsJsonObject();
			JsonElement nameElement = card.get("cardName");
			JsonElement ownersElement = card.get("owners");
			if (nameElement == null || !nameElement.isJsonPrimitive()
				|| !nameElement.getAsJsonPrimitive().isString()
				|| ownersElement == null || !ownersElement.isJsonArray())
			{
				return null;
			}
			String name = nameElement.getAsString().trim();
			if (name.isEmpty() || name.length() > MAX_CARD_NAME_LENGTH)
			{
				return null;
			}
			if (ownersElement.getAsJsonArray().size() > 0)
			{
				String key = TcgItemNameNormalizer.normalize(name);
				if (!key.isEmpty())
				{
					keys.add(key);
				}
			}
		}
		return new GroupCollection(revision, Collections.unmodifiableSet(keys));
	}

	private static JsonObject object(String json)
	{
		JsonElement element = new JsonParser().parse(json);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static Integer integer(JsonObject object, String key)
	{
		if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
			|| !object.getAsJsonPrimitive(key).isNumber())
		{
			return null;
		}
		try
		{
			long value = object.get(key).getAsLong();
			return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
				|| object.get(key).getAsDouble() != (double) value ? null : (int) value;
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	static String normalizeGroupKey(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String key = raw.trim().toUpperCase(Locale.ROOT);
		return key.matches("[A-Z0-9]{6}") ? key : null;
	}

	private static byte[] readLimited(InputStream input) throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int read;
		while ((read = input.read(chunk)) != -1)
		{
			if (output.size() + read > MAX_RESPONSE_BYTES)
			{
				return null;
			}
			output.write(chunk, 0, read);
		}
		return output.toByteArray();
	}

	private interface Parser<T>
	{
		T parse(String json);
	}
}
