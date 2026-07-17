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

import java.util.List;

/** Immutable snapshot the plugin pushes to {@link TcgLockedPanel} whenever the collection or gear changes. */
final class TcgLockedStatus
{
	/** One unlock event: the card/item name and when it was first seen. */
	static final class Unlock
	{
		final String name;
		final long atMs;

		Unlock(String name, long atMs)
		{
			this.name = name;
			this.atMs = atMs;
		}
	}

	/** One catalogued item for the lockbook: its id, display name (resolved on the client thread), and lock state. */
	static final class LockItem
	{
		final int itemId;
		final String name;
		final boolean locked;

		LockItem(int itemId, String name, boolean locked)
		{
			this.itemId = itemId;
			this.name = name;
			this.locked = locked;
		}
	}

	/** One party member's shared progress. */
	static final class PartyEntry
	{
		final String name;
		final int cardsOwned;
		final int unlocked;
		final int seen;
		final boolean local;

		PartyEntry(String name, int cardsOwned, int unlocked, int seen, boolean local)
		{
			this.name = name;
			this.cardsOwned = cardsOwned;
			this.unlocked = unlocked;
			this.seen = seen;
			this.local = local;
		}
	}

	final boolean collectionLoaded;
	final int cardsOwned;
	final int sessionUnlocks;
	final String enforcementLabel;
	/** Newest first. */
	final List<Unlock> recentUnlocks;
	/** Item names held in the inventory that are currently locked. */
	final List<String> lockedInBag;
	/** Item names currently worn without owning a card. */
	final List<String> equippedViolations;
	/** Catalogued ("seen") items, sorted by name, for the lockbook grid. */
	final List<LockItem> lockItems;
	final int lockbookSeen;
	final int lockbookUnlocked;
	/** Party members' progress (including the local player) when sharing in a party; empty otherwise. */
	final List<PartyEntry> party;
	final long updatedAtMs;

	TcgLockedStatus(
		boolean collectionLoaded,
		int cardsOwned,
		int sessionUnlocks,
		String enforcementLabel,
		List<Unlock> recentUnlocks,
		List<String> lockedInBag,
		List<String> equippedViolations,
		List<LockItem> lockItems,
		int lockbookSeen,
		int lockbookUnlocked,
		List<PartyEntry> party,
		long updatedAtMs)
	{
		this.collectionLoaded = collectionLoaded;
		this.cardsOwned = cardsOwned;
		this.sessionUnlocks = sessionUnlocks;
		this.enforcementLabel = enforcementLabel;
		this.recentUnlocks = recentUnlocks;
		this.lockedInBag = lockedInBag;
		this.equippedViolations = equippedViolations;
		this.lockItems = lockItems;
		this.lockbookSeen = lockbookSeen;
		this.lockbookUnlocked = lockbookUnlocked;
		this.party = party;
		this.updatedAtMs = updatedAtMs;
	}
}
