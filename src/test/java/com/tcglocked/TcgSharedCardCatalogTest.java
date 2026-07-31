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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TcgSharedCardCatalogTest
{
	private final TcgSharedCardCatalog catalog = new TcgSharedCardCatalog();

	@Test
	public void bundledCatalogUsesExistingLocalCardResources()
	{
		assertEquals(6376, catalog.cards().size());
		assertEquals(5149, catalog.cards().stream()
			.filter(card -> card.category == TcgSharedCardCatalog.Category.ITEM).count());
		assertEquals(1227, catalog.cards().stream()
			.filter(card -> card.category == TcgSharedCardCatalog.Category.NPC).count());
		assertCard("Abyssal whip", "abyssal whip", TcgSharedCardCatalog.Category.ITEM);
		assertCard("Abyssal demon", "abyssal demon", TcgSharedCardCatalog.Category.NPC);
	}

	@Test
	public void onlyNpcCardsCarrySharedCacheImageUrls()
	{
		TcgSharedCardCatalog.Card item = card("Abyssal whip");
		TcgSharedCardCatalog.Card npc = card("Abyssal demon");
		assertEquals(null, item.imageUrl);
		assertTrue(npc.imageUrl.startsWith("https://oldschool.runescape.wiki/images/"));
	}

	@Test
	public void snapshotIsDefensiveAndSupportsFiltering()
	{
		Set<String> keys = new HashSet<>(Collections.singleton("abyssal whip"));
		Map<String, Set<String>> owners = new HashMap<>();
		owners.put("alice", keys);
		owners.put("bob", Collections.singleton("abyssal demon"));
		TcgSharedCatalogSnapshot snapshot = new TcgSharedCatalogSnapshot(owners);
		keys.add("twisted bow");
		owners.clear();

		assertEquals(Collections.singleton("abyssal whip"), snapshot.keysFor("alice"));
		assertEquals(Arrays.asList("alice"), snapshot.ownersOf("abyssal whip"));
		assertEquals(Arrays.asList("Abyssal whip", "Abyssal demon"), names(TcgSharedCardCatalog.filter(
			catalog.cards(), snapshot, null, null, true, "abyssal")));
		assertEquals(Collections.singletonList("Abyssal whip"), names(TcgSharedCardCatalog.filter(
			catalog.cards(), snapshot, "alice", TcgSharedCardCatalog.Category.ITEM, true, "")));
	}

	private void assertCard(String name, String key, TcgSharedCardCatalog.Category category)
	{
		TcgSharedCardCatalog.Card card = card(name);
		assertEquals(key, card.key);
		assertEquals(category, card.category);
	}

	private TcgSharedCardCatalog.Card card(String name)
	{
		return catalog.cards().stream().filter(candidate -> name.equals(candidate.name))
			.findFirst().orElseThrow(AssertionError::new);
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
}
