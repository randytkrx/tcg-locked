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

import java.util.Set;
import java.util.function.Predicate;

enum TcgLockedNpcState
{
	UNTRACKED,
	UNLOCKED,
	LOCKED;

	static TcgLockedNpcState resolve(
		boolean collectionKnown,
		String npcName,
		TcgCardCatalog cardCatalog,
		Predicate<String> eligibleCard,
		Predicate<String> unlockedCard)
	{
		if (!collectionKnown)
		{
			return UNTRACKED;
		}

		String key = TcgItemNameNormalizer.normalize(npcName);
		if (key.isEmpty())
		{
			return UNTRACKED;
		}

		Set<String> cards = cardCatalog.npcCards(key);
		boolean hasEligibleCard = false;
		for (String card : cards)
		{
			if (!eligibleCard.test(card))
			{
				continue;
			}
			hasEligibleCard = true;
			if (unlockedCard.test(card))
			{
				return UNLOCKED;
			}
		}
		return hasEligibleCard ? LOCKED : UNTRACKED;
	}
}
