/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TcgSharedCardCatalogTest
{
	private final TcgSharedCardCatalog catalog = new TcgSharedCardCatalog();

	@Test
	public void bundledCatalogContainsCurrentUpstreamCards()
	{
		assertEquals(6376, catalog.cards().size());
		assertEquals(5149, catalog.cards().stream()
			.filter(card -> card.category == TcgSharedCardCatalog.Category.ITEM).count());
		assertEquals(1227, catalog.cards().stream()
			.filter(card -> card.category == TcgSharedCardCatalog.Category.NPC).count());
		assertCard("Abyssal whip", "abyssal whip", TcgSharedCardCatalog.Category.ITEM,
			"https://oldschool.runescape.wiki/images/thumb/Abyssal_whip_detail.png/130px-Abyssal_whip_detail.png");
		assertCard("Abyssal demon", "abyssal demon", TcgSharedCardCatalog.Category.NPC,
			"https://oldschool.runescape.wiki/images/thumb/Abyssal_demon.png/130px-Abyssal_demon.png");
	}

	@Test
	public void snapshotIsDefensiveAndImmutable()
	{
		Set<String> keys = new HashSet<>(Collections.singleton("abyssal whip"));
		Map<String, Set<String>> owners = new HashMap<>();
		owners.put("alice", keys);
		TcgSharedCatalogSnapshot snapshot = new TcgSharedCatalogSnapshot(owners);
		keys.add("twisted bow");
		owners.clear();

		assertEquals(Collections.singleton("abyssal whip"), snapshot.keysFor("alice"));
		assertImmutable(snapshot.keysFor("alice"));
		assertImmutableList(snapshot.owners());
	}

	@Test
	public void snapshotSupportsCombinedAndPerOwnerOwnership()
	{
		Map<String, Set<String>> owners = new HashMap<>();
		owners.put("alice", new HashSet<>(Arrays.asList("abyssal whip", "twisted bow")));
		owners.put("bob", new HashSet<>(Arrays.asList("abyssal whip", "abyssal demon")));
		TcgSharedCatalogSnapshot snapshot = new TcgSharedCatalogSnapshot(owners);

		assertEquals(3, snapshot.combinedKeys().size());
		assertEquals(Arrays.asList("alice", "bob"), snapshot.ownersOf("abyssal whip"));
		assertTrue(snapshot.keysFor("alice").contains("twisted bow"));
		assertFalse(snapshot.keysFor("bob").contains("twisted bow"));
	}

	@Test
	public void filteringIsPureAcrossOwnerCategorySearchAndSharedModes()
	{
		Map<String, Set<String>> owners = new HashMap<>();
		owners.put("alice", Collections.singleton("abyssal whip"));
		owners.put("bob", Collections.singleton("abyssal demon"));
		TcgSharedCatalogSnapshot snapshot = new TcgSharedCatalogSnapshot(owners);

		List<TcgSharedCardCatalog.Card> union = TcgSharedCardCatalog.filter(
			catalog.cards(), snapshot, null, null, true, "abyssal");
		assertEquals(Arrays.asList("Abyssal whip", "Abyssal demon"), names(union));

		List<TcgSharedCardCatalog.Card> alice = TcgSharedCardCatalog.filter(
			catalog.cards(), snapshot, "alice", TcgSharedCardCatalog.Category.ITEM, true, "");
		assertEquals(Collections.singletonList("Abyssal whip"), names(alice));

		List<TcgSharedCardCatalog.Card> allNpcs = TcgSharedCardCatalog.filter(
			catalog.cards(), snapshot, "alice", TcgSharedCardCatalog.Category.NPC, false, "abyssal demon");
		assertEquals(Arrays.asList("Abyssal demon", "Greater abyssal demon"), names(allNpcs));
		assertEquals(6376, catalog.cards().size());
	}

	private void assertCard(String name, String key, TcgSharedCardCatalog.Category category, String imageUrl)
	{
		TcgSharedCardCatalog.Card card = catalog.cards().stream()
			.filter(candidate -> name.equals(candidate.name))
			.findFirst().orElseThrow(AssertionError::new);
		assertEquals(key, card.key);
		assertEquals(category, card.category);
		assertEquals(imageUrl, card.imageUrl);
	}

	private static List<String> names(List<TcgSharedCardCatalog.Card> cards)
	{
		List<String> names = new java.util.ArrayList<>();
		for (TcgSharedCardCatalog.Card card : cards)
		{
			names.add(card.name);
		}
		return names;
	}

	private static void assertImmutable(Set<String> values)
	{
		try
		{
			values.add("other");
			throw new AssertionError("set was mutable");
		}
		catch (UnsupportedOperationException expected)
		{
			// expected
		}
	}

	private static void assertImmutableList(List<String> values)
	{
		try
		{
			values.add("other");
			throw new AssertionError("list was mutable");
		}
		catch (UnsupportedOperationException expected)
		{
			// expected
		}
	}
}
