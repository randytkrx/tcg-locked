/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

@Singleton
final class TcgSharedCatalogManager
{
	private static final int ITEM_SCAN_BATCH = 2_000;

	private final TcgSharedCardCatalog catalog;
	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final TcgSharedNpcImageCache npcImages;
	private final Set<String> wantedItemKeys;
	private Map<String, Integer> itemIds;
	private Map<String, Integer> buildingItemIds;
	private int itemScanCursor;
	private TcgSharedCatalogWindow window;
	private boolean started;
	private int generation;

	@Inject
	TcgSharedCatalogManager(TcgSharedCardCatalog catalog, Client client, ClientThread clientThread,
		ItemManager itemManager, TcgSharedNpcImageCache npcImages)
	{
		this.catalog = catalog;
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.npcImages = npcImages;
		Set<String> wanted = new HashSet<>();
		for (TcgSharedCardCatalog.Card card : catalog.cards())
		{
			if (card.category == TcgSharedCardCatalog.Category.ITEM)
			{
				wanted.add(card.key);
			}
		}
		wantedItemKeys = Collections.unmodifiableSet(wanted);
	}

	synchronized void start()
	{
		started = true;
		generation++;
		itemIds = null;
		buildingItemIds = null;
		itemScanCursor = 0;
		npcImages.start();
	}

	synchronized void show(TcgSharedCatalogSnapshot snapshot)
	{
		final int requestGeneration = generation;
		if (itemIds == null && buildingItemIds == null)
		{
			buildingItemIds = new HashMap<>();
			itemScanCursor = 0;
			clientThread.invokeLater(() -> scanItemIds(requestGeneration));
		}
		final Map<String, Integer> itemIds = this.itemIds == null
			? Collections.emptyMap() : this.itemIds;
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

	/** Builds the local item-sprite index in small client-thread batches to avoid blocking game ticks. */
	private void scanItemIds(int requestGeneration)
	{
		final Map<String, Integer> building;
		final int from;
		final int to;
		synchronized (this)
		{
			if (!started || generation != requestGeneration || buildingItemIds == null)
			{
				return;
			}
			building = buildingItemIds;
			from = itemScanCursor;
			to = Math.min(client.getItemCount(), from + ITEM_SCAN_BATCH);
		}
		for (int id = from; id < to; id++)
		{
			ItemComposition item = client.getItemDefinition(id);
			if (item == null || item.getNote() != -1 || item.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			// getName() appends " (Members)" on F2P worlds; card names always use the real name.
			String key = TcgItemNameNormalizer.normalize(item.getMembersName());
			if (wantedItemKeys.contains(key))
			{
				building.putIfAbsent(key, itemManager.canonicalize(id));
			}
		}
		final Map<String, Integer> completed;
		synchronized (this)
		{
			if (!started || generation != requestGeneration || buildingItemIds != building)
			{
				return;
			}
			itemScanCursor = to;
			if (to < client.getItemCount())
			{
				clientThread.invokeLater(() -> scanItemIds(requestGeneration));
				return;
			}
			completed = Collections.unmodifiableMap(new HashMap<>(building));
			itemIds = completed;
			buildingItemIds = null;
		}
		SwingUtilities.invokeLater(() ->
		{
			synchronized (TcgSharedCatalogManager.this)
			{
				if (!started || generation != requestGeneration)
				{
					return;
				}
			}
			if (window != null && window.isDisplayable())
			{
				window.setItemIds(completed);
			}
		});
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
		buildingItemIds = null;
		itemScanCursor = 0;
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
