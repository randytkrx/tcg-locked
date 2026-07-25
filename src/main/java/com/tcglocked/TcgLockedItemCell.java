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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * One lockbook cell: the item's icon, dimmed with a padlock when locked, and marked with a corner
 * flash when it is open only because a group member owns the card rather than you.
 */
class TcgLockedItemCell extends JComponent
{
	private static final Dimension SIZE = new Dimension(36, 32);
	private static final Color LOCK_TINT = new Color(0, 0, 0, 150);
	private static final Color LOCK_FILL = new Color(230, 230, 230);
	private static final Color LOCK_OUTLINE = new Color(20, 20, 20, 220);
	/** Group blue, distinct from the gold used for the player's own progress. */
	private static final Color POOLED_MARK = new Color(0x5B, 0x9B, 0xD5);
	private static final Color POOLED_MARK_EDGE = new Color(20, 20, 20, 180);

	private final AsyncBufferedImage image;
	private final boolean locked;
	private final boolean pooled;

	TcgLockedItemCell(ItemManager itemManager, int itemId, boolean locked, boolean pooled, String tooltip)
	{
		this.image = itemManager.getImage(itemId);
		this.locked = locked;
		this.pooled = pooled;
		setPreferredSize(SIZE);
		setToolTipText(tooltip);
		image.onLoaded(this::repaint);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.drawImage(image, 2, 0, null);
		if (locked)
		{
			g2.setColor(LOCK_TINT);
			g2.fillRect(0, 0, SIZE.width, SIZE.height);
			drawPadlock(g2, SIZE.width - 11, SIZE.height - 13);
		}
		else if (pooled)
		{
			// Top-left triangle: reads at a glance across a grid without hiding the item art.
			int[] xs = {0, 8, 0};
			int[] ys = {0, 0, 8};
			g2.setColor(POOLED_MARK);
			g2.fillPolygon(xs, ys, 3);
			g2.setColor(POOLED_MARK_EDGE);
			g2.setStroke(new BasicStroke(1f));
			g2.drawLine(8, 0, 0, 8);
		}
		g2.dispose();
	}

	private static void drawPadlock(Graphics2D g, int x, int y)
	{
		int bodyW = 8;
		int bodyH = 6;
		g.setStroke(new BasicStroke(1.2f));
		g.setColor(LOCK_OUTLINE);
		g.draw(new Arc2D.Double(x + 1.5, y, bodyW - 3, bodyH, 0, 180, Arc2D.OPEN));
		RoundRectangle2D body = new RoundRectangle2D.Double(x, y + bodyH - 2.0, bodyW, bodyH, 2, 2);
		g.setColor(LOCK_FILL);
		g.fill(body);
		g.setColor(LOCK_OUTLINE);
		g.setStroke(new BasicStroke(1f));
		g.draw(body);
	}
}
