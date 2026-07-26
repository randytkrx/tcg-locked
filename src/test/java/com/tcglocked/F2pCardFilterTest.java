package com.tcglocked;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class F2pCardFilterTest
{
	@Test
	public void parsesAndFiltersApiNames()
	{
		Set<String> eligible = F2pCardFilter.lowerNames(
			Arrays.asList("Bronze sword", "Goblin", null, 42));
		Set<String> owned = new HashSet<>(Arrays.asList(
			"bronze sword", "goblin", "abyssal whip"));

		Set<String> filtered = F2pCardFilter.filter(owned, eligible, true);

		assertEquals(new HashSet<>(Arrays.asList("bronze sword", "goblin")), filtered);
		assertEquals(owned, F2pCardFilter.filter(owned, eligible, false));
	}

	@Test
	public void normalizesVariantFriendlyEligibilityKeys()
	{
		Set<String> eligible = F2pCardFilter.normalizedNames(
			Arrays.asList("Amulet of glory", "Rune platebody"));

		assertTrue(eligible.contains(TcgItemNameNormalizer.normalize("Amulet of glory(4)")));
	}
}
