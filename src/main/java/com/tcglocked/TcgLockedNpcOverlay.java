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

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

class TcgLockedNpcOverlay extends Overlay
{
	private static final int OUTLINE_WIDTH = 2;
	private static final int OUTLINE_FEATHER = 2;

	private final Client client;
	private final TcgLockedPlugin plugin;
	private final TcgLockedConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	TcgLockedNpcOverlay(
		Client client,
		TcgLockedPlugin plugin,
		TcgLockedConfig config,
		ModelOutlineRenderer modelOutlineRenderer)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showNpcHighlights() || !plugin.isCollectionLoaded())
		{
			return null;
		}

		for (NPC npc : client.getNpcs())
		{
			if (npc == null)
			{
				continue;
			}

			TcgLockedNpcState state = plugin.npcState(npc.getName());
			if (state == TcgLockedNpcState.UNLOCKED)
			{
				modelOutlineRenderer.drawOutline(
					npc, OUTLINE_WIDTH, TcgLockedHighlightColors.UNLOCKED, OUTLINE_FEATHER);
			}
			else if (state == TcgLockedNpcState.LOCKED)
			{
				modelOutlineRenderer.drawOutline(
					npc, OUTLINE_WIDTH, TcgLockedHighlightColors.LOCKED, OUTLINE_FEATHER);
			}
			else
			{
				modelOutlineRenderer.drawOutline(
					npc, OUTLINE_WIDTH, TcgLockedHighlightColors.NO_CARD, OUTLINE_FEATHER);
			}
		}
		return null;
	}
}
