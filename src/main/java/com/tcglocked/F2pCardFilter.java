package com.tcglocked;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class F2pCardFilter
{
	private F2pCardFilter()
	{
	}

	static Set<String> lowerNames(List<?> payload)
	{
		if (payload == null || payload.isEmpty())
		{
			return Collections.emptySet();
		}
		Set<String> names = new HashSet<>();
		for (Object value : payload)
		{
			if (value instanceof String)
			{
				String name = ((String) value).trim().toLowerCase(Locale.ROOT);
				if (!name.isEmpty())
				{
					names.add(name);
				}
			}
		}
		return Collections.unmodifiableSet(names);
	}

	static Set<String> normalizedNames(List<?> payload)
	{
		if (payload == null || payload.isEmpty())
		{
			return Collections.emptySet();
		}
		Set<String> names = new HashSet<>();
		for (Object value : payload)
		{
			if (value instanceof String)
			{
				String key = TcgItemNameNormalizer.normalize((String) value);
				if (!key.isEmpty())
				{
					names.add(key);
				}
			}
		}
		return Collections.unmodifiableSet(names);
	}

	static Set<String> filter(Set<String> values, Set<String> eligible, boolean active)
	{
		if (values == null || values.isEmpty())
		{
			return Collections.emptySet();
		}
		if (!active)
		{
			return values;
		}
		Set<String> filtered = new HashSet<>(values);
		filtered.retainAll(eligible);
		return Collections.unmodifiableSet(filtered);
	}
}
