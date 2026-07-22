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
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class TcgLockedOverlay extends OverlayPanel
{
	private final TcgLockedPlugin plugin;
	private final TcgLockedConfig config;

	@Inject
	TcgLockedOverlay(TcgLockedPlugin plugin, TcgLockedConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay() || plugin.enforcementDeferred())
		{
			// Deferred: Bronzeman TCG owns enforcement, so don't flag violations here too.
			return null;
		}

		List<String> violations = plugin.getEquippedViolationNames();
		if (violations.isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("TCG Locked — unowned gear")
			.color(Color.RED)
			.build());

		for (String name : violations)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(name)
				.leftColor(Color.RED)
				.build());
		}

		setPreferredSize(new Dimension(180, 0));
		return super.render(graphics);
	}
}
