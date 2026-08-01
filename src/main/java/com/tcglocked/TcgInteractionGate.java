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

import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Decides whether an interaction is blocked, and says what is missing when it is.
 *
 * <p>Deliberately free of plugin state. Ownership arrives as a predicate so this reuses whatever
 * {@link TcgLockedPlugin} already counts as owned — the player's own cards, the allow list, and
 * cards lent by an approved party member — instead of re-deriving any of it. That also makes the
 * whole class testable without a client.
 */
@Singleton
class TcgInteractionGate
{
	private final TcgInteractionCatalog catalog;

	@Inject
	TcgInteractionGate(TcgInteractionCatalog catalog)
	{
		this.catalog = catalog;
	}

	/**
	 * @param kind one of {@link TcgInteractionCatalog}'s KIND_ constants
	 * @param name the target's in-game name
	 * @param option the menu option being used, or null to match only wildcard entries
	 * @param ownsNormalizedCard tests a normalized card name for ownership
	 * @return the first unmet requirement's description, or null when the interaction is allowed
	 */
	String firstMissing(String kind, String name, String option, Predicate<String> ownsNormalizedCard)
	{
		return firstMissing(kind, name, option, -1, ownsNormalizedCard);
	}

	String firstMissing(String kind, String name, String option, int targetId,
		Predicate<String> ownsNormalizedCard)
	{
		TcgInteractionCatalog.Rule rule = catalog.find(kind, name, option, targetId);
		if (rule == null)
		{
			return null;
		}
		for (TcgInteractionCatalog.CardGroup group : rule.groups)
		{
			if (isContextOnlyRole(group.role))
			{
				continue;
			}
			if (!group.isSatisfied(ownsNormalizedCard))
			{
				return group.describe();
			}
		}
		return null;
	}

	private static boolean isContextOnlyRole(String role)
	{
		if (role == null)
		{
			return false;
		}
		String normalized = role.trim().toLowerCase(java.util.Locale.ROOT);
		// Drop tables: loot, loot-elf, loot-ham. These record what an action yields, so requiring
		// them would make the reward its own entry price — a H.A.M. Member needs all 37 of its
		// drops before the first steal. Matched by prefix so a new loot-* variant cannot lock a
		// skill by being added to the data alone.
		if (normalized.startsWith("loot"))
		{
			return true;
		}
		switch (normalized)
		{
			case "creature":
			case "extra":
			case "monsters":
			case "superiors":
				return true;
			default:
				return false;
		}
	}
}
