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

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TcgLockedPoolConsentTest
{
	@Test
	public void namesMatchRegardlessOfHowTheyArrive()
	{
		// A saved decision has to survive the name coming back with different casing or the
		// non-breaking space RuneScape uses, or an approved partner would silently stop matching and
		// their unlocks would vanish.
		String expected = TcgLockedPoolConsent.key("Mitisma");
		assertEquals(expected, TcgLockedPoolConsent.key("mitisma"));
		assertEquals(expected, TcgLockedPoolConsent.key("  Mitisma  "));

		String spaced = TcgLockedPoolConsent.key("Zezima Jr");
		assertEquals(spaced, TcgLockedPoolConsent.key("Zezima Jr"));
	}

	@Test
	public void tagsAreStripped()
	{
		assertEquals(TcgLockedPoolConsent.key("Mitisma"),
			TcgLockedPoolConsent.key("<col=ff0000>Mitisma</col>"));
	}

	@Test
	public void namesWeCannotDecideAboutHaveNoKey()
	{
		// Storing a decision against a placeholder would attach it to whoever loaded next.
		assertTrue(TcgLockedPoolConsent.key(null).isEmpty());
		assertTrue(TcgLockedPoolConsent.key("").isEmpty());
		assertTrue(TcgLockedPoolConsent.key("   ").isEmpty());
		assertTrue(TcgLockedPoolConsent.key("<unknown>").isEmpty());
	}
}
