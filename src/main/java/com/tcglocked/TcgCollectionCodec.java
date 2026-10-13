/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Self-describing additional keys; unlike a bitmap these survive different live catalog versions. */
final class TcgCollectionCodec
{
	static final int MAX_ENCODED = 65_536;
	private static final int MAX_BYTES = 2 * 1024 * 1024;
	private static final int MAX_KEYS = 15_000;

	private TcgCollectionCodec()
	{
	}

	static boolean validKeys(Set<String> keys)
	{
		if (keys == null || keys.size() > MAX_KEYS)
		{
			return false;
		}
		for (String key : keys)
		{
			if (key == null || key.isEmpty() || key.length() > 128
				|| key.chars().anyMatch(Character::isISOControl)
				|| !key.equals(TcgItemNameNormalizer.normalize(key)))
			{
				return false;
			}
		}
		return true;
	}

	static String encode(Set<String> keys)
	{
		if (!validKeys(keys))
		{
			throw new IllegalArgumentException("Invalid collection keys");
		}
		if (keys.isEmpty())
		{
			return "";
		}
		byte[] raw = new Gson().toJson(new TreeSet<>(keys)).getBytes(StandardCharsets.UTF_8);
		if (raw.length > MAX_BYTES)
		{
			throw new IllegalArgumentException("Collection keys exceed size limit");
		}
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		try
		{
			deflater.setInput(raw);
			deflater.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			while (!deflater.finished())
			{
				out.write(buffer, 0, deflater.deflate(buffer));
			}
			String encoded = Base64.getEncoder().encodeToString(out.toByteArray());
			if (encoded.length() > MAX_ENCODED)
			{
				throw new IllegalArgumentException("Encoded collection exceeds size limit");
			}
			return encoded;
		}
		finally
		{
			deflater.end();
		}
	}

	static Set<String> decode(String encoded)
	{
		if (encoded == null || encoded.length() > MAX_ENCODED)
		{
			return null;
		}
		if (encoded.isEmpty())
		{
			return Collections.emptySet();
		}
		Inflater inflater = new Inflater();
		try
		{
			inflater.setInput(Base64.getDecoder().decode(encoded));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			while (!inflater.finished())
			{
				int count = inflater.inflate(buffer);
				if (count == 0 || out.size() + count > MAX_BYTES)
				{
					return null;
				}
				out.write(buffer, 0, count);
			}
			if (inflater.getRemaining() != 0)
			{
				return null;
			}
			Set<String> keys = new HashSet<>();
			for (JsonElement value : new JsonParser().parse(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonArray())
			{
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
					|| !keys.add(value.getAsString()) || keys.size() > MAX_KEYS)
				{
					return null;
				}
			}
			return validKeys(keys) ? Collections.unmodifiableSet(keys) : null;
		}
		catch (java.util.zip.DataFormatException | RuntimeException ex)
		{
			return null;
		}
		finally
		{
			inflater.end();
		}
	}
}
