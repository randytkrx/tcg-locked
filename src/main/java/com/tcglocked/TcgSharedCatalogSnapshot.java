/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class TcgSharedCatalogSnapshot
{
	private final Map<String, Set<String>> byOwner;
	private final Set<String> combined;

	TcgSharedCatalogSnapshot(Map<String, Set<String>> owners)
	{
		Map<String, Set<String>> copy = new LinkedHashMap<>();
		Set<String> union = new LinkedHashSet<>();
		if (owners != null)
		{
			for (Map.Entry<String, Set<String>> entry : new TreeMap<>(owners).entrySet())
			{
				Set<String> keys = entry.getValue() == null
					? Collections.emptySet() : new LinkedHashSet<>(entry.getValue());
				Set<String> immutable = Collections.unmodifiableSet(keys);
				copy.put(entry.getKey(), immutable);
				union.addAll(immutable);
			}
		}
		byOwner = Collections.unmodifiableMap(copy);
		combined = Collections.unmodifiableSet(union);
	}

	List<String> owners()
	{
		return Collections.unmodifiableList(new ArrayList<>(byOwner.keySet()));
	}

	Set<String> keysFor(String owner)
	{
		return byOwner.getOrDefault(owner, Collections.emptySet());
	}

	Set<String> combinedKeys()
	{
		return combined;
	}

	boolean isShared(String key)
	{
		return combined.contains(key);
	}

	List<String> ownersOf(String key)
	{
		List<String> owners = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : byOwner.entrySet())
		{
			if (entry.getValue().contains(key))
			{
				owners.add(entry.getKey());
			}
		}
		return Collections.unmodifiableList(owners);
	}
}
