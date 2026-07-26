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
import net.runelite.api.ItemLayer;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/** Outlines visible ground-item models with the shared TCG unlock-status palette. */
class TcgLockedGroundItemOverlay extends Overlay
{
	private static final int OUTLINE_WIDTH = 2;
	private static final int OUTLINE_FEATHER = 2;

	private final Client client;
	private final TcgLockedPlugin plugin;
	private final TcgLockedConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	TcgLockedGroundItemOverlay(
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
		if (!config.showLockIcons() || !plugin.isCollectionLoaded())
		{
			return null;
		}

		Player player = client.getLocalPlayer();
		WorldView worldView = player == null ? null : player.getWorldView();
		Scene scene = worldView == null ? null : worldView.getScene();
		if (scene == null)
		{
			return null;
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = worldView.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null)
		{
			return null;
		}

		for (Tile[] column : tiles[plane])
		{
			if (column == null)
			{
				continue;
			}
			for (Tile tile : column)
			{
				if (tile != null)
				{
					renderItemLayer(tile.getItemLayer());
				}
			}
		}
		return null;
	}

	private void renderItemLayer(ItemLayer layer)
	{
		if (layer == null)
		{
			return;
		}

		Renderable bottom = layer.getBottom();
		Renderable middle = layer.getMiddle();
		Renderable top = layer.getTop();
		renderItem(layer, bottom);
		if (middle != bottom)
		{
			renderItem(layer, middle);
		}
		if (top != bottom && top != middle)
		{
			renderItem(layer, top);
		}
	}

	private void renderItem(ItemLayer layer, Renderable renderable)
	{
		if (!(renderable instanceof TileItem))
		{
			return;
		}

		TileItem item = (TileItem) renderable;
		TcgLockedItemState state = TcgLockedItemState.from(plugin.unlockSourceFor(item.getId()));
		modelOutlineRenderer.drawOutline(
			layer,
			item,
			OUTLINE_WIDTH,
			TcgLockedItemOverlay.outlineColor(state),
			OUTLINE_FEATHER);
	}
}
