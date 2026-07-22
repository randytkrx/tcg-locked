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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Dims and draws a small padlock on inventory / bank / equipment items the player does not own a card for, sharing
 * {@link TcgLockedPlugin#isUnlocked(int)} so the visual exactly matches what menu enforcement blocks.
 */
class TcgLockedItemOverlay extends WidgetItemOverlay
{
	private static final Color TINT = new Color(0, 0, 0, 130);
	private static final Color LOCK_FILL = new Color(230, 230, 230);
	private static final Color LOCK_OUTLINE = new Color(30, 30, 30, 200);

	private final TcgLockedPlugin plugin;
	private final TcgLockedConfig config;

	@Inject
	TcgLockedItemOverlay(TcgLockedPlugin plugin, TcgLockedConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		showOnInventory();
		showOnBank();
		showOnEquipment();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.showLockIcons() || plugin.enforcementDeferred())
		{
			// Deferred: Bronzeman TCG draws its own lock icons — avoid double padlocks.
			return;
		}
		if (plugin.isUnlocked(itemId))
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return;
		}

		graphics.setColor(TINT);
		graphics.fill(bounds);
		drawPadlock(graphics, bounds);
	}

	/** Draws a small vector padlock in the top-left corner of the slot; no image asset needed. */
	private static void drawPadlock(Graphics2D graphics, Rectangle bounds)
	{
		Object prevAa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Stroke prevStroke = graphics.getStroke();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int bodyW = 8;
		int bodyH = 6;
		int x = bounds.x + 1;
		int y = bounds.y + 1;

		// Shackle: top half-loop above the body.
		graphics.setStroke(new BasicStroke(1.3f));
		graphics.setColor(LOCK_OUTLINE);
		graphics.draw(new Arc2D.Double(x + 1.5, y, bodyW - 3, bodyH, 0, 180, Arc2D.OPEN));

		// Body: rounded rectangle.
		RoundRectangle2D body = new RoundRectangle2D.Double(x, y + bodyH - 2.0, bodyW, bodyH, 2, 2);
		graphics.setColor(LOCK_FILL);
		graphics.fill(body);
		graphics.setColor(LOCK_OUTLINE);
		graphics.setStroke(new BasicStroke(1.0f));
		graphics.draw(body);

		graphics.setStroke(prevStroke);
		if (prevAa != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, prevAa);
		}
	}
}
