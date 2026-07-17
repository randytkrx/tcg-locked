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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reduces an OSRS item name to a base "card key" so a single owned card unlocks all of an item's charge / dose /
 * degrade / cosmetic variants. Applied symmetrically to both owned card names and queried item names, so it does not
 * matter whether the catalog stored the base or a decorated form.
 *
 * <p>Intentionally conservative: it only strips suffixes that are (almost) always variant markers, not arbitrary
 * trailing parentheses (many item names legitimately end in one, e.g. {@code Clue scroll (medium)}). Exact-name
 * matching is tried before this in {@link TcgLockedPlugin}, so normalization only decides borderline cases.</p>
 */
final class TcgItemNameNormalizer
{
	private TcgItemNameNormalizer()
	{
	}

	/** Trailing dose / charge count, e.g. {@code (6)}, {@code (4)}, {@code (100)}. */
	private static final Pattern DOSE = Pattern.compile("\\(\\d+\\)\\s*$");

	/** Barrows-style degrade counter, e.g. {@code Dharok's greataxe 100}. Only the discrete barrows values. */
	private static final Pattern DEGRADE = Pattern.compile("\\s+(?:0|25|50|75|100)$");

	/** Legacy percent charge, e.g. {@code ... 100%}. */
	private static final Pattern PERCENT = Pattern.compile("\\s+\\d+%$");

	/**
	 * Curated set of trailing parenthetical variant markers: charge state, imbue/ornament/poison/minigame variants,
	 * and common god / gem recolour suffixes. Anything not listed is left intact.
	 */
	private static final Pattern VARIANT_PAREN = Pattern.compile(
		"\\((?:"
			+ "i|g|t|l|c|u|e|p|p\\+|p\\+\\+|kp|or|cr|nz|bh|lms|deadman"
			+ "|uncharged|charged|empty|full|inactive|deployed|broken|damaged|locked"
			+ "|last man standing|historical|ornament"
			+ "|guthix|saradomin|zamorak|armadyl|bandos|ancient"
			+ "|rune|gilded|ruby|emerald|diamond|dragonstone|onyx|zenyte"
			+ ")\\)\\s*$",
		Pattern.CASE_INSENSITIVE);

	/**
	 * @return a lower-cased base key with variant decorations stripped, or an empty string for null/blank input.
	 */
	static String normalize(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		String s = raw.trim().toLowerCase(Locale.ROOT)
			.replace('’', '\'')  // right single quote -> apostrophe
			.replace('‘', '\'')  // left single quote -> apostrophe
			.replace(' ', ' ');  // non-breaking space -> space
		s = s.replaceAll("\\s+", " ").trim();

		String prev;
		do
		{
			prev = s;
			s = DOSE.matcher(s).replaceAll("").trim();
			s = VARIANT_PAREN.matcher(s).replaceAll("").trim();
			s = DEGRADE.matcher(s).replaceAll("").trim();
			s = PERCENT.matcher(s).replaceAll("").trim();
		}
		while (!s.equals(prev));

		return s;
	}

	/**
	 * Parses a comma-separated list of item names into a set of normalized keys, for the "always-allow" config.
	 *
	 * @return normalized keys (blank entries dropped); empty set for null/blank input.
	 */
	static Set<String> normalizeCsv(String csv)
	{
		Set<String> out = new HashSet<>();
		if (csv == null || csv.trim().isEmpty())
		{
			return out;
		}
		for (String part : csv.split(","))
		{
			String key = normalize(part);
			if (!key.isEmpty())
			{
				out.add(key);
			}
		}
		return out;
	}
}
