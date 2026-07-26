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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

	@Test
	public void packedCollectionRoundTrips()
	{
		Set<String> keys = new HashSet<>(Arrays.asList(
			"abyssal whip", "twisted bow", "shark", "abyssal demon"));
		assertEquals(keys, catalog.unpackKeys(catalog.packKeys(keys)));
	}

	@Test
	public void packedCollectionCarriesCatalogVersion()
	{
		String packed = catalog.packKeys(Collections.singleton("abyssal whip"));
		assertTrue(packed.startsWith("2:"));
		assertNull(catalog.unpackKeysOrNull("2:wrongcatalog:" + packed.substring(packed.lastIndexOf(':') + 1)));
	}

	@Test
	public void malformedFormatsAreRejected()
	{
		assertNull(catalog.unpackKeysOrNull("xAA=="));
		assertNull(catalog.unpackKeysOrNull("2:wrong:bAA=="));
		assertNull(catalog.unpackKeysOrNull("2:wrong:zAA=="));
		assertTrue(catalog.unpackKeys("not a format").isEmpty());
	}

	@Test
	public void packedDataMustHaveTheExactBitmapLength()
	{
		String packed = catalog.packKeys(Collections.singleton("abyssal whip"));
		String header = packed.substring(0, packed.lastIndexOf(':') + 1);
		assertNull(catalog.unpackKeysOrNull(header + "bAA=="));
	}

	@Test
	public void incomingLegacyKeysAreBoundedToTheCatalog()
	{
		assertEquals(Collections.singleton("abyssal whip"),
			catalog.filterKnownKeys(Collections.singleton("abyssal whip")));
		assertNull(catalog.filterKnownKeys(Collections.singleton("not a card")));
	}

	@Test
	public void packingDropsCardsOutsideTheCatalog()
	{
		// Lossless for gating: a key with no item or monster behind it can never unlock anything, so
		// dropping it costs nothing and keeps the bitmap addressable by catalog index.
		Set<String> keys = new HashSet<>(Arrays.asList("abyssal whip", "not a real card at all"));
		assertEquals(Collections.singleton("abyssal whip"), catalog.unpackKeys(catalog.packKeys(keys)));
	}

	@Test
	public void aSparseCollectionIsDeflated()
	{
		// A handful of cards out of thousands is nearly all zero bits, so this is where compressing
		// earns its keep.
		String sparse = catalog.packKeys(Collections.singleton("abyssal whip"));
		assertEquals('z', sparse.charAt(sparse.lastIndexOf(':') + 1));
		assertEquals(Collections.singleton("abyssal whip"), catalog.unpackKeys(sparse));
	}

	@Test
	public void aScatteredCollectionRoundTripsWhicheverFormIsChosen()
	{
		// Roughly two thirds of the catalog, scattered rather than contiguous, which is the shape a
		// real part-finished collection has and the case least friendly to compression.
		Set<String> scattered = new HashSet<>();
		int index = 0;
		for (String key : catalog.allCardKeys())
		{
			if ((index++ * 2654435761L >>> 16) % 3 != 0)
			{
				scattered.add(key);
			}
		}
		String packed = catalog.packKeys(scattered);
		assertEquals(scattered, catalog.unpackKeys(packed));
		assertTrue("packed collection was " + packed.length() + " chars", packed.length() < 4000);
	}

	@Test
	public void aFullCollectionStaysSmallEnoughToStore()
	{
		// The reason for packing at all: thousands of names is tens of kilobytes of synced config.
		String packed = catalog.packKeys(catalog.allCardKeys());
		assertTrue("packed collection was " + packed.length() + " chars", packed.length() < 4000);
	}

	@Test
	public void unusablePackedDataYieldsNothing()
	{
		assertTrue(catalog.unpackKeys(null).isEmpty());
		assertTrue(catalog.unpackKeys("").isEmpty());
		assertTrue(catalog.unpackKeys("z!!!not base64!!!").isEmpty());
		assertTrue(catalog.packKeys(Collections.emptySet()).isEmpty());
	}
}
