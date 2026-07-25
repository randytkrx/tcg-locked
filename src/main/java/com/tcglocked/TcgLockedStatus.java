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

	/** Why an item is usable, so the panel can tell your own progress from what the group is lending. */
	enum UnlockSource
	{
		/** No card owned by you or the group. */
		LOCKED,
		/** The collection isn't known yet, so nothing is being enforced at all. */
		SUSPENDED,
		/** Your own card. */
		OWNED,
		/** A synced partner's card, and not one of yours. */
		POOLED,
		/** Bronze starter gear or your always-allow list. */
		EXEMPT,
		/** No card exists for it, so it was never part of the challenge. */
		UNCARDED
	}

	/** One catalogued item for the lockbook: its id, display name (resolved on the client thread), and lock state. */
	static final class LockItem
	{
		final int itemId;
		final String name;
		final boolean locked;
		final UnlockSource source;
		/** Synced partners whose cards open this item; empty unless {@link #source} is POOLED. */
		final List<String> unlockedBy;

		LockItem(int itemId, String name, boolean locked, UnlockSource source, List<String> unlockedBy)
		{
			this.itemId = itemId;
			this.name = name;
			this.locked = locked;
			this.source = source;
			this.unlockedBy = unlockedBy;
		}
	}

	/** One party member's shared progress, or a synced partner who isn't currently in the party. */
	static final class PartyEntry
	{
		final String name;
		final int cardsOwned;
		final int unlocked;
		final int seen;
		final boolean local;
		/** Whether their cards pool with yours; drives the approve / decline / revoke controls. */
		final TcgLockedPoolConsent.Decision consent;
		/** False for a saved partner who is not in the party right now. */
		final boolean present;
		/** False until their client reports a name, since a decision could not be saved against them. */
		final boolean decidable;
		/** Cards of theirs currently in your pool. */
		final int sharedCards;
		/**
		 * How many items you have seen their cards open. Several partners can own the same card, so
		 * these counts may overlap and do not sum to the group total.
		 */
		final int contributes;

		PartyEntry(String name, int cardsOwned, int unlocked, int seen, boolean local,
			TcgLockedPoolConsent.Decision consent, boolean present, boolean decidable,
			int sharedCards, int contributes)
		{
			this.decidable = decidable;
			this.sharedCards = sharedCards;
			this.contributes = contributes;
			this.name = name;
			this.cardsOwned = cardsOwned;
			this.unlocked = unlocked;
			this.seen = seen;
			this.local = local;
			this.consent = consent;
			this.present = present;
		}
	}

	/** True once the TCG plugin has supplied the collection; false means locking is suspended. */
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
	/** Of the unlocked ones, how many are open only because a synced partner owns the card. */
	final int lockbookPooled;
	/** Distinct cards currently pooled in from synced partners. */
	final int pooledCards;
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
		int lockbookPooled,
		int pooledCards,
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
		this.lockbookPooled = lockbookPooled;
		this.pooledCards = pooledCards;
		this.party = party;
		this.updatedAtMs = updatedAtMs;
	}
}
