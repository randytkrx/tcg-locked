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

import java.awt.Color;

/** Shared outline palette for item and NPC unlock status. */
final class TcgLockedHighlightColors
{
	static final Color UNLOCKED = new Color(0x00, 0xFF, 0x00);
	static final Color LOCKED = new Color(0xFF, 0x00, 0x00);
	static final Color NO_CARD = new Color(0xA0, 0x20, 0xF0);

	private TcgLockedHighlightColors()
	{
	}
}
