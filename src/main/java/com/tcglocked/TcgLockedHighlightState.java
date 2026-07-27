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

import java.awt.Color;
import java.util.Set;
import java.util.function.Predicate;

/** Shared item and NPC outline state, derived from the plugin's existing unlock rules. */
enum TcgLockedHighlightState
{
	UNLOCKED(new Color(0x00, 0xFF, 0x00)),
	LOCKED(new Color(0xFF, 0x00, 0x00)),
	NO_CARD(new Color(0xA0, 0x20, 0xF0));

	private final Color color;

	TcgLockedHighlightState(Color color)
	{
		this.color = color;
	}

	Color getColor()
	{
		return color;
	}

	static TcgLockedHighlightState fromItemSource(TcgLockedStatus.UnlockSource source)
	{
		if (source == TcgLockedStatus.UnlockSource.LOCKED)
		{
			return LOCKED;
		}
		if (source == TcgLockedStatus.UnlockSource.UNCARDED
			|| source == TcgLockedStatus.UnlockSource.SUSPENDED)
		{
			return NO_CARD;
		}
		return UNLOCKED;
	}

	static TcgLockedHighlightState forNpc(
		boolean collectionKnown,
		String npcName,
		TcgCardCatalog cardCatalog,
		Predicate<String> unlockedCard)
	{
		if (!collectionKnown)
		{
			return NO_CARD;
		}

		String key = TcgItemNameNormalizer.normalize(npcName);
		if (key.isEmpty())
		{
			return NO_CARD;
		}

		Set<String> cards = cardCatalog.npcCards(key);
		if (cards.isEmpty())
		{
			return NO_CARD;
		}
		for (String card : cards)
		{
			if (unlockedCard.test(card))
			{
				return UNLOCKED;
			}
		}
		return LOCKED;
	}
}
