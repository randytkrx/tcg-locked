/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

@Singleton
final class TcgSharedCatalogManager
{
	private final TcgSharedCardCatalog catalog;
	private final Client client;
	private final ItemManager itemManager;
	private final TcgSharedNpcImageCache npcImages;
	private Map<String, Integer> itemIds;
	private TcgSharedCatalogWindow window;
	private boolean started;
	private int generation;

	@Inject
	TcgSharedCatalogManager(TcgSharedCardCatalog catalog, Client client, ItemManager itemManager,
		TcgSharedNpcImageCache npcImages)
	{
		this.catalog = catalog;
		this.client = client;
		this.itemManager = itemManager;
		this.npcImages = npcImages;
	}

	synchronized void start()
	{
		started = true;
		generation++;
		itemIds = null;
		npcImages.start();
	}

	synchronized void show(TcgSharedCatalogSnapshot snapshot)
	{
		final int requestGeneration = generation;
		final Map<String, Integer> itemIds = localItemIds();
		SwingUtilities.invokeLater(() ->
		{
			synchronized (TcgSharedCatalogManager.this)
			{
				if (!started || generation != requestGeneration)
				{
					return;
				}
			}
			if (window == null || !window.isDisplayable())
			{
				window = new TcgSharedCatalogWindow(catalog, itemManager, npcImages, itemIds);
			}
			window.setItemIds(itemIds);
			window.refresh(snapshot);
			window.setVisible(true);
			window.setExtendedState(window.getExtendedState() & ~java.awt.Frame.ICONIFIED);
			window.toFront();
			window.requestFocus();
		});
	}

	/** Builds an exact-name index from game-cache definitions. Called on the client thread; no request is made. */
	private Map<String, Integer> localItemIds()
	{
		if (itemIds != null)
		{
			return itemIds;
		}
		java.util.Set<String> wanted = new java.util.HashSet<>();
		for (TcgSharedCardCatalog.Card card : catalog.cards())
		{
			if (card.category == TcgSharedCardCatalog.Category.ITEM)
			{
				wanted.add(card.key);
			}
		}
		Map<String, Integer> ids = new HashMap<>();
		for (int id = 0; id < client.getItemCount(); id++)
		{
			ItemComposition item = client.getItemDefinition(id);
			if (item == null || item.getNote() != -1 || item.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			// getName() appends " (Members)" on F2P worlds; card names always use the real name.
			String key = TcgItemNameNormalizer.normalize(item.getMembersName());
			if (wanted.contains(key))
			{
				ids.putIfAbsent(key, itemManager.canonicalize(id));
			}
		}
		itemIds = Collections.unmodifiableMap(ids);
		return itemIds;
	}

	synchronized void refreshIfVisible(TcgSharedCatalogSnapshot snapshot)
	{
		final int requestGeneration = generation;
		SwingUtilities.invokeLater(() ->
		{
			synchronized (TcgSharedCatalogManager.this)
			{
				if (!started || generation != requestGeneration)
				{
					return;
				}
			}
			if (window != null && window.isVisible())
			{
				window.refresh(snapshot);
			}
		});
	}

	synchronized void dispose()
	{
		started = false;
		generation++;
		itemIds = null;
		npcImages.dispose();
		SwingUtilities.invokeLater(() ->
		{
			if (window != null)
			{
				window.dispose();
				window = null;
			}
		});
	}
}
