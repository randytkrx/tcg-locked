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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Set;
import org.junit.Test;

public class TcgItemNameNormalizerTest
{
	private static String n(String s)
	{
		return TcgItemNameNormalizer.normalize(s);
	}

	@Test
	public void nullAndBlankAreEmpty()
	{
		assertEquals("", n(null));
		assertEquals("", n("   "));
	}

	@Test
	public void doseAndChargeSuffixesStripped()
	{
		assertEquals("amulet of glory", n("Amulet of glory(6)"));
		assertEquals("ring of dueling", n("Ring of dueling(8)"));
		assertEquals("prayer potion", n("Prayer potion(4)"));
		assertEquals("prayer potion", n("Prayer potion(1)"));
	}

	@Test
	public void barrowsDegradeCounterStripped()
	{
		assertEquals("dharok's greataxe", n("Dharok's greataxe 100"));
		assertEquals("dharok's greataxe", n("Dharok's greataxe 0"));
		assertEquals("ahrim's robetop", n("Ahrim's robetop 25"));
	}

	@Test
	public void variantParensStripped()
	{
		assertEquals("berserker ring", n("Berserker ring (i)"));
		assertEquals("blade of saeldor", n("Blade of saeldor (c)"));
		assertEquals("blade of saeldor", n("Blade of saeldor (inactive)"));
		assertEquals("bow of faerdhinen", n("Bow of faerdhinen (c)"));
		assertEquals("serpentine helm", n("Serpentine helm (uncharged)"));
		assertEquals("iron dagger", n("Iron dagger(p++)"));
		assertEquals("armadyl godsword", n("Armadyl godsword (or)"));
	}

	@Test
	public void essentialTrailingParensPreserved()
	{
		// These parentheses are part of the item's identity, not a variant marker.
		assertEquals("clue scroll (medium)", n("Clue scroll (medium)"));
		assertEquals("toy horsey (black)", n("Toy horsey (black)"));
	}

	@Test
	public void apostropheAndWhitespaceNormalized()
	{
		// Curly apostrophe -> straight, collapsed whitespace, lower-cased.
		assertEquals("karil's crossbow", n("Karil’s  crossbow"));
		assertEquals("dharok's greataxe", n("  Dharok's greataxe  "));
	}

	@Test
	public void baseAndVariantConvergeToSameKey()
	{
		// The whole point: a base card and a decorated in-game item map to the same key.
		assertEquals(n("Amulet of glory"), n("Amulet of glory(6)"));
		assertEquals(n("Dharok's greataxe"), n("Dharok's greataxe 100"));
		assertTrue(!n("Amulet of glory").isEmpty());
	}

	@Test
	public void normalizeCsvProducesKeys()
	{
		Set<String> keys = TcgItemNameNormalizer.normalizeCsv("Amulet of glory(6),  Dharok's greataxe 100 , ,Fire cape");
		assertTrue(keys.contains("amulet of glory"));
		assertTrue(keys.contains("dharok's greataxe"));
		assertTrue(keys.contains("fire cape"));
		assertEquals(3, keys.size()); // blank entry dropped
	}

	@Test
	public void normalizeCsvEmptyForBlank()
	{
		assertTrue(TcgItemNameNormalizer.normalizeCsv(null).isEmpty());
		assertTrue(TcgItemNameNormalizer.normalizeCsv("   ").isEmpty());
	}

	@Test
	public void addedCosmeticSuffixesStripped()
	{
		assertEquals("dragon scimitar", n("Dragon scimitar (or)"));
		assertEquals("rune platebody", n("Rune platebody (historical)"));
		assertEquals("armadyl godsword", n("Armadyl godsword (ornament)"));
	}

	@Test
	public void starterGearPrefixIsRecognizable()
	{
		// The plugin gates starter gear via key.startsWith("bronze "); verify normalization keeps that prefix.
		assertTrue(n("Bronze full helm").startsWith("bronze "));
		assertFalse(n("Rune full helm").startsWith("bronze "));
	}
}
