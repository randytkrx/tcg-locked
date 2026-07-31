package com.tcglocked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class TcgPartyProgressPolicyTest
{
	@Test
	public void unaddressedProgressRetainsLastKnownCollection()
	{
		Map<String, Set<String>> offered = new HashMap<>();
		Map<String, Set<String>> pooled = new HashMap<>();
		Set<String> existing = keys("rope", "shark");
		offered.put("partner", existing);
		pooled.put("partner", existing);

		TcgPartyProgressPolicy.Result result = TcgPartyProgressPolicy.apply(
			offered, pooled, "partner", false, keys("bronze sword"), true);

		assertFalse(result.isAccepted());
		assertEquals(existing, offered.get("partner"));
		assertEquals(existing, pooled.get("partner"));
	}

	@Test
	public void malformedReplacementRetainsLastKnownCollection()
	{
		Map<String, Set<String>> offered = new HashMap<>();
		Map<String, Set<String>> pooled = new HashMap<>();
		Set<String> existing = keys("rope");
		offered.put("partner", existing);
		pooled.put("partner", existing);

		TcgPartyProgressPolicy.Result result = TcgPartyProgressPolicy.apply(
			offered, pooled, "partner", true, null, true);

		assertFalse(result.isAccepted());
		assertEquals(existing, pooled.get("partner"));
	}

	@Test
	public void addressedApprovedSnapshotReplacesCollection()
	{
		Map<String, Set<String>> offered = new HashMap<>();
		Map<String, Set<String>> pooled = new HashMap<>();
		Set<String> existing = keys("rope");
		Set<String> replacement = keys("rope", "shark");
		pooled.put("partner", existing);

		TcgPartyProgressPolicy.Result result = TcgPartyProgressPolicy.apply(
			offered, pooled, "partner", true, replacement, true);

		assertTrue(result.isAccepted());
		assertTrue(result.isPooled());
		assertEquals(existing, result.getPrevious());
		assertEquals(replacement, offered.get("partner"));
		assertEquals(replacement, pooled.get("partner"));
	}

	@Test
	public void validEmptySnapshotClearsStaleCollection()
	{
		Map<String, Set<String>> offered = new HashMap<>();
		Map<String, Set<String>> pooled = new HashMap<>();
		pooled.put("partner", keys("rope"));
		Set<String> empty = new HashSet<>();

		TcgPartyProgressPolicy.Result result = TcgPartyProgressPolicy.apply(
			offered, pooled, "partner", true, empty, true);

		assertTrue(result.isPooled());
		assertEquals(empty, offered.get("partner"));
		assertEquals(empty, pooled.get("partner"));
	}

	private static Set<String> keys(String... values)
	{
		return new HashSet<>(Arrays.asList(values));
	}
}
