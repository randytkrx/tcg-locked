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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.http.api.item.ItemPrice;

/**
 * Transient "UNLOCKED" reveal cards shown near the top of the screen when a newly pulled TCG card frees an item.
 * Each card fades in, holds, then fades out, and shows the item's art resolved by name.
 */
class TcgLockedRevealOverlay extends Overlay
{
	private static final long FADE_IN_MS = 250;
	private static final long HOLD_MS = 2200;
	private static final long FADE_OUT_MS = 850;
	private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FADE_OUT_MS;
	private static final int MAX_CARDS = 3;
	private static final int CARD_W = 196;
	private static final int CARD_H = 46;
	private static final int GAP = 6;
	private static final Color GOLD = new Color(0xC8, 0xA9, 0x51);
	private static final Color BG = new Color(0x22, 0x22, 0x22);

	private final ItemManager itemManager;
	private final TcgLockedConfig config;
	private final ConcurrentLinkedDeque<Reveal> reveals = new ConcurrentLinkedDeque<>();

	@Inject
	TcgLockedRevealOverlay(ItemManager itemManager, TcgLockedConfig config)
	{
		this.itemManager = itemManager;
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void enqueue(String displayName, long nowMs)
	{
		if (!config.showUnlockReveal() || displayName == null || displayName.isEmpty())
		{
			return;
		}
		reveals.addLast(new Reveal(displayName, resolveIcon(displayName), nowMs));
		while (reveals.size() > MAX_CARDS)
		{
			reveals.pollFirst();
		}
	}

	void clear()
	{
		reveals.clear();
	}

	private BufferedImage resolveIcon(String name)
	{
		try
		{
			List<ItemPrice> hits = itemManager.search(name);
			if (hits == null || hits.isEmpty())
			{
				return null;
			}
			int id = -1;
			for (ItemPrice p : hits)
			{
				if (p.getName() != null && p.getName().equalsIgnoreCase(name))
				{
					id = p.getId();
					break;
				}
			}
			if (id < 0)
			{
				id = hits.get(0).getId();
			}
			return itemManager.getImage(id);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (reveals.isEmpty())
		{
			return null;
		}
		long now = System.currentTimeMillis();
		reveals.removeIf(r -> now - r.startMs > TOTAL_MS);
		if (reveals.isEmpty())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int y = 0;
		for (Reveal r : reveals)
		{
			drawCard(graphics, r, y, alphaFor(now - r.startMs));
			y += CARD_H + GAP;
		}
		return new Dimension(CARD_W, y == 0 ? CARD_H : y - GAP);
	}

	private void drawCard(Graphics2D g, Reveal r, int y, float alpha)
	{
		Composite prev = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp(alpha)));

		g.setColor(BG);
		g.fillRoundRect(0, y, CARD_W, CARD_H, 8, 8);
		g.setColor(GOLD);
		g.drawRoundRect(0, y, CARD_W - 1, CARD_H - 1, 8, 8);

		int iconX = 8;
		int iconY = y + (CARD_H - 32) / 2;
		if (r.icon != null)
		{
			g.drawImage(r.icon, iconX, iconY, null);
		}

		int textX = iconX + 40;
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(GOLD);
		g.drawString("UNLOCKED", textX, y + 18);
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(fit(g, r.name, CARD_W - textX - 8), textX, y + 36);

		g.setComposite(prev);
	}

	private static float alphaFor(long elapsed)
	{
		if (elapsed < FADE_IN_MS)
		{
			return elapsed / (float) FADE_IN_MS;
		}
		if (elapsed > TOTAL_MS - FADE_OUT_MS)
		{
			return (TOTAL_MS - elapsed) / (float) FADE_OUT_MS;
		}
		return 1f;
	}

	private static float clamp(float a)
	{
		return a < 0f ? 0f : (a > 1f ? 1f : a);
	}

	private static String fit(Graphics2D g, String s, int maxWidth)
	{
		if (g.getFontMetrics().stringWidth(s) <= maxWidth)
		{
			return s;
		}
		String ell = "...";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++)
		{
			if (g.getFontMetrics().stringWidth(sb.toString() + s.charAt(i) + ell) > maxWidth)
			{
				break;
			}
			sb.append(s.charAt(i));
		}
		return sb + ell;
	}

	private static final class Reveal
	{
		private final String name;
		private final BufferedImage icon;
		private final long startMs;

		private Reveal(String name, BufferedImage icon, long startMs)
		{
			this.name = name;
			this.icon = icon;
			this.startMs = startMs;
		}
	}
}
