/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 */
package com.tcglocked;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Read-only view of the "OSRS TCG" plugin's owned-card collection, with two ways in:
 *
 * <p>1. Preferred: OSRS TCG's {@link net.runelite.client.events.PluginMessage} API
 * (its {@code OwnedCardNamesApiService}). The plugin feeds "owned-names" payloads here via
 * {@link #onApiOwnedNames}; once one arrives it is authoritative and push-updated, until
 * {@link #invalidate()} (profile switch) re-arms the fallback.</p>
 *
 * <p>2. Fallback, for OSRS TCG versions that predate the API: its persisted config state, stored per
 * RuneScape profile in config group {@code osrstcg} key {@code state} as
 * {@code RLTCG_v2:}&lt;base64(xor(gzip(json)))&gt;, with an unkeyed SHA-256 of the stored blob in key
 * {@code hash}. We decode the same format and pull out the owned card names. We never write to that
 * group — the TCG plugin remains the sole owner of its state.</p>
 */
@Singleton
@Slf4j
class TcgLockedCollectionReader
{
	private static final String TCG_GROUP = "osrstcg";
	private static final String STATE_KEY = "state";
	private static final String HASH_KEY = "hash";
	private static final String STORAGE_PREFIX = "RLTCG_v2:";

	// Mirrors com.osrstcg.persist.TcgStateStorageEncoding#XOR_SALT ("RLTCG|osrs-tcg!"). Public in the open-source
	// plugin; this is obfuscation only, so duplicating it here is fine and carries no secret.
	private static final byte[] XOR_SALT = {
		0x52, 0x4c, 0x54, 0x43, 0x47, 0x7c, 0x6f, 0x73, 0x72, 0x73, 0x2d, 0x74, 0x63, 0x67, 0x21,
	};

	private final ConfigManager configManager;
	private final Gson gson;

	/** Null until the first PluginMessage payload lands; non-null means the API path is live. */
	private Set<String> apiOwnedLower;

	@Inject
	TcgLockedCollectionReader(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/**
	 * Feed in an "ownedNames" payload from OSRS TCG's PluginMessage API. Elements are validated
	 * individually — the data map is untyped, and a malformed payload should degrade to the
	 * config fallback rather than throw.
	 */
	synchronized void onApiOwnedNames(List<?> names)
	{
		if (names == null)
		{
			return;
		}
		Set<String> normalized = new HashSet<>();
		for (Object name : names)
		{
			if (name instanceof String)
			{
				String trimmed = ((String) name).trim();
				if (!trimmed.isEmpty())
				{
					normalized.add(trimmed.toLowerCase(Locale.ROOT));
				}
			}
		}
		apiOwnedLower = Collections.unmodifiableSet(normalized);
	}

	/** True once an API payload has arrived; the plugin stops re-querying at that point. */
	synchronized boolean hasApiData()
	{
		return apiOwnedLower != null;
	}

	/**
	 * Call on profile switches so one account's collection never lingers for another. Drops API
	 * data too — it described the previous profile — so the config fallback serves until the
	 * re-query for the new profile is answered.
	 */
	synchronized void invalidate()
	{
		apiOwnedLower = null;
	}

	/**
	 * @return lower-cased, trimmed owned card names for the active profile: the PluginMessage API's
	 * payload when one has arrived, else the decoded legacy config state (empty if the TCG plugin has
	 * no state, the integrity hash fails, or decoding fails).
	 */
	synchronized Set<String> readOwnedCardNamesLower()
	{
		if (apiOwnedLower != null)
		{
			return apiOwnedLower;
		}
		String stored = readProfileScoped(STATE_KEY);
		if (stored == null || stored.isEmpty())
		{
			return Collections.emptySet();
		}

		String expectedHash = readProfileScoped(HASH_KEY);
		if (expectedHash != null && !expectedHash.isEmpty())
		{
			String actual = sha256Hex(stored);
			if (actual != null && !actual.equalsIgnoreCase(expectedHash.trim()))
			{
				log.debug("TCG Locked: osrstcg state hash mismatch; treating collection as empty.");
				return Collections.emptySet();
			}
		}

		String json = decode(stored);
		if (json.isEmpty())
		{
			return Collections.emptySet();
		}

		try
		{
			StateDto dto = gson.fromJson(json, StateDto.class);
			if (dto == null || dto.cardInstances == null)
			{
				return Collections.emptySet();
			}
			Set<String> owned = new HashSet<>();
			for (InstanceDto row : dto.cardInstances)
			{
				if (row == null || row.cardName == null)
				{
					continue;
				}
				String name = row.cardName.trim().toLowerCase(Locale.ROOT);
				if (!name.isEmpty())
				{
					owned.add(name);
				}
			}
			return owned;
		}
		catch (JsonSyntaxException ex)
		{
			log.debug("TCG Locked: failed to parse osrstcg state json", ex);
			return Collections.emptySet();
		}
	}

	private String readProfileScoped(String key)
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null || profileKey.isEmpty())
		{
			return configManager.getConfiguration(TCG_GROUP, key);
		}
		return configManager.getConfiguration(TCG_GROUP, profileKey, key);
	}

	private static String decode(String stored)
	{
		if (stored.length() <= STORAGE_PREFIX.length() || !stored.startsWith(STORAGE_PREFIX))
		{
			return "";
		}
		try
		{
			byte[] blob = Base64.getDecoder().decode(stored.substring(STORAGE_PREFIX.length()));
			for (int i = 0; i < blob.length; i++)
			{
				blob[i] ^= XOR_SALT[i % XOR_SALT.length];
			}
			try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(blob)))
			{
				return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		catch (Exception ex)
		{
			log.debug("TCG Locked: failed to decode osrstcg state blob", ex);
			return "";
		}
	}

	private static String sha256Hex(String s)
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				sb.append(String.format("%02x", b & 0xff));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			return null;
		}
	}

	/** Minimal mirror of the fields we need from com.osrstcg.persist.TcgStateCodec's serialized form. */
	private static final class StateDto
	{
		private List<InstanceDto> cardInstances;
	}

	private static final class InstanceDto
	{
		private String cardName;
	}
}
