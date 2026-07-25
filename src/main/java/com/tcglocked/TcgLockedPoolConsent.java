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

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * Who you have agreed to pool unlocks with. Pooling hands your whole collection to everyone in the
 * party, and RuneLite parties are also used for raids, so it must not happen to whoever turns up —
 * a player with no cards should not inherit a finished run by joining a raid hub.
 *
 * <p>Decisions are keyed by RuneScape name and persisted per RS profile, so an alt is approved once
 * and a declined stranger never asks again. Both answers are remembered: only names never seen
 * before are {@link Decision#PENDING}, and pending blocks pooling exactly like a decline, so
 * ignoring the prompt is the safe outcome.</p>
 */
@Singleton
class TcgLockedPoolConsent
{
	enum Decision
	{
		/** Never answered for: pools nothing, and the panel asks. */
		PENDING,
		/** Cards flow both ways with this player. */
		APPROVED,
		/** Pools nothing, and the panel stops asking. */
		DECLINED
	}

	private static final String APPROVED_KEY = "pooledApproved";
	private static final String DECLINED_KEY = "pooledDeclined";

	private final ConfigManager configManager;

	private final Set<String> approved = new LinkedHashSet<>();
	private final Set<String> declined = new LinkedHashSet<>();
	private String loadedProfileKey;

	@Inject
	TcgLockedPoolConsent(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * Normalizes a display name the way {@code PartyService.getMemberByDisplayName} does, so a name
	 * that arrives with tags, a non-breaking space or different casing still matches what was saved.
	 *
	 * @return the lookup key, or an empty string for a name we should never store a decision for.
	 */
	static String key(String displayName)
	{
		if (displayName == null)
		{
			return "";
		}
		String sanitized = Text.toJagexName(Text.removeTags(displayName)).trim();
		if (sanitized.isEmpty() || "<unknown>".equalsIgnoreCase(sanitized))
		{
			return "";
		}
		return sanitized.toLowerCase(Locale.ROOT);
	}

	/** Loads this profile's decisions; a no-op once the profile is already loaded. */
	synchronized void load(String profileKey)
	{
		if (profileKey != null && profileKey.equals(loadedProfileKey))
		{
			return;
		}
		loadedProfileKey = profileKey;
		approved.clear();
		declined.clear();
		readInto(APPROVED_KEY, approved);
		readInto(DECLINED_KEY, declined);
	}

	/** Drops in-memory state so another profile's decisions never apply; re-read on next load. */
	synchronized void invalidate()
	{
		loadedProfileKey = null;
		approved.clear();
		declined.clear();
	}

	synchronized Decision decisionFor(String displayName)
	{
		String key = key(displayName);
		if (key.isEmpty())
		{
			// No usable name yet (member still loading): treat as pending so nothing pools and the
			// panel does not offer a prompt it could not save an answer for.
			return Decision.PENDING;
		}
		if (approved.contains(key))
		{
			return Decision.APPROVED;
		}
		return declined.contains(key) ? Decision.DECLINED : Decision.PENDING;
	}

	synchronized boolean isApproved(String displayName)
	{
		return decisionFor(displayName) == Decision.APPROVED;
	}

	synchronized void approve(String displayName)
	{
		move(displayName, approved, declined);
	}

	/** Also used to revoke: an approved player moves straight to declined, dropping their pool. */
	synchronized void decline(String displayName)
	{
		move(displayName, declined, approved);
	}

	private void move(String displayName, Set<String> into, Set<String> outOf)
	{
		String key = key(displayName);
		if (key.isEmpty())
		{
			return;
		}
		boolean changed = into.add(key);
		changed |= outOf.remove(key);
		if (changed)
		{
			write(APPROVED_KEY, approved);
			write(DECLINED_KEY, declined);
		}
	}

	/** @return approved names, for tests and diagnostics. */
	synchronized Set<String> approvedKeys()
	{
		return Collections.unmodifiableSet(new HashSet<>(approved));
	}

	private void readInto(String configKey, Set<String> target)
	{
		String csv = configManager.getRSProfileConfiguration(TcgLockedConfig.GROUP, configKey);
		if (csv == null || csv.isEmpty())
		{
			return;
		}
		for (String part : csv.split(","))
		{
			String key = key(part);
			if (!key.isEmpty())
			{
				target.add(key);
			}
		}
	}

	private void write(String configKey, Set<String> names)
	{
		configManager.setRSProfileConfiguration(TcgLockedConfig.GROUP, configKey, String.join(",", names));
	}
}
