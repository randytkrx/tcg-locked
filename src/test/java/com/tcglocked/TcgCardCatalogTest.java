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
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TcgCardCatalogTest
{
	private final TcgCardCatalog catalog = new TcgCardCatalog();

	private boolean carded(String itemName)
	{
		return catalog.hasItemCard(TcgItemNameNormalizer.normalize(itemName));
	}

	private Set<String> npcCards(String npcName)
	{
		return catalog.npcCards(TcgItemNameNormalizer.normalize(npcName));
	}

	@Test
	public void bundledResourcesLoad()
	{
		// Guards against a resource that silently failed to load, which would switch item gating off
		// entirely (everything uncarded) rather than fail loudly.
		assertTrue("expected the item catalog to load", catalog.itemCardCount() > 5000);
		assertTrue("expected the monster catalog to load", catalog.npcCount() > 1000);
	}

	@Test
	public void carriedItemsWithCardsAreTracked()
	{
		assertTrue(carded("Abyssal whip"));
		assertTrue(carded("Bandos chestplate"));
		assertTrue(carded("Twisted bow"));
		assertTrue(carded("Shark"));
	}

	@Test
	public void itemsWithNoCardAreUntracked()
	{
		// Real items with no card in the catalog. Gating these would be a permanent dead end: there
		// is no card to collect, so they must stay outside the challenge.
		assertFalse(carded("Salve amulet"));
		assertFalse(carded("Max cape"));
		assertFalse(carded("Quest point cape"));
		assertFalse(carded("Varrock teleport"));
		assertFalse(carded("Iban's staff"));
		assertFalse(carded("Holy symbol"));
	}

	@Test
	public void variantSpellingsResolveToTheBaseCard()
	{
		// Cards are stored dose-less and undecorated, so in-game variants must normalize onto them.
		assertTrue(carded("Attack potion(3)"));
		assertTrue(carded("Saradomin brew(4)"));
		assertTrue(carded("Dragon pickaxe (or)"));
		assertTrue(carded("Dharok's greataxe 100"));
	}

	@Test
	public void npcNamesMapToTheirCardNames()
	{
		// The in-game name is not always the card name: the collection is keyed by the card, so the
		// catalog has to bridge the two or these monsters would never unlock.
		Set<String> monkey = npcCards("Monkey");
		assertTrue("expected Monkey to be carded", monkey.contains("monkey (monster)"));

		// One in-game name shared by several cards; owning any of them unlocks it.
		Set<String> archer = npcCards("Archer");
		assertEquals(2, archer.size());
		assertTrue(archer.contains("archer (ardougne)"));
		assertTrue(archer.contains("archer (burthorpe)"));
	}

	@Test
	public void plainNpcNamesMapToThemselves()
	{
		assertTrue(npcCards("Abyssal demon").contains("abyssal demon"));
	}

	@Test
	public void untrackedNpcsHaveNoCards()
	{
		assertTrue(npcCards("Bob the Cat's imaginary friend").isEmpty());
		assertTrue(npcCards("").isEmpty());
		assertTrue(catalog.npcCards(null).isEmpty());
	}

	@Test
	public void nullItemKeyIsNotCarded()
	{
		assertFalse(catalog.hasItemCard(null));
	}
}
