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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Singleton;

/**
 * The player's owned-card collection, supplied by the OSRS TCG plugin's
 * {@link net.runelite.client.events.PluginMessage} API (its {@code OwnedCardNamesApiService}).
 * {@link TcgLockedPlugin} posts a query and forwards the {@code owned-names} reply plus every
 * unsolicited {@code owned-names-changed} push here, so this is push-updated with no polling.
 *
 * <p>Until a payload arrives the collection is <em>unknown</em>, which is distinct from "owns
 * nothing": callers must not lock anything while it is unknown, or a player without the TCG plugin
 * running would find every item unusable. {@link #hasCollection()} draws that line.</p>
 */
@Singleton
class TcgLockedCollectionReader
{
	/** Null until the first payload lands; non-null (possibly empty) means the collection is known. */
	private Set<String> ownedLower;

	/**
	 * Feed in an {@code ownedNames} payload from the TCG plugin's API. Elements are validated
	 * individually rather than trusting the cast — the payload map is untyped, and a malformed one
	 * should be ignored rather than throw mid-click.
	 */
	synchronized boolean onApiOwnedNames(List<?> names)
	{
		if (names == null)
		{
			return false;
		}
		Set<String> normalized = new HashSet<>();
		for (Object name : names)
		{
			if (!(name instanceof String))
			{
				return false;
			}
			String trimmed = ((String) name).trim();
			if (trimmed.isEmpty())
			{
				return false;
			}
			normalized.add(trimmed.toLowerCase(Locale.ROOT));
		}
		ownedLower = Collections.unmodifiableSet(normalized);
		return true;
	}

	/**
	 * @return true once the TCG plugin has told us the collection, even if it is empty. While this
	 * is false nothing is locked, because "we haven't been told" must not look like "you own no
	 * cards".
	 */
	synchronized boolean hasCollection()
	{
		return ownedLower != null;
	}

	/**
	 * Forgets the collection so one account's cards never linger for another. Called on profile
	 * switches; locking stays suspended until the re-query for the new profile is answered.
	 */
	synchronized void invalidate()
	{
		ownedLower = null;
	}

	/** @return one immutable atomic snapshot, or null while the collection is unknown. */
	synchronized Set<String> snapshotOrNull()
	{
		return ownedLower;
	}
}
