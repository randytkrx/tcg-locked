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

import com.google.inject.Provides;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "TCG Locked",
	description = "Challenge mode: you can only equip/use an item once you own its OSRS TCG card",
	tags = {"tcg", "cards", "locked", "challenge", "ironman", "collection"}
)
public class TcgLockedPlugin extends Plugin
{
	private static final Set<String> EQUIP_VERBS = Set.of("wield", "wear", "equip");
	private static final Set<String> CONSUME_VERBS = Set.of("eat", "drink");
	/** Teleport / charge activations that aren't equips: jewelry "Rub", teleport tab "Break", direct "Teleport", etc. */
	private static final Set<String> ACTIVATION_VERBS =
		Set.of("rub", "teleport", "break", "commune", "invoke", "operate", "activate");
	private static final String TCG_STATE_GROUP = "osrstcg";
	private static final String TCG_STATE_KEY = "state";
	private static final int RECENT_UNLOCK_CAP = 30;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TcgLockedConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private TcgLockedCollectionReader collectionReader;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TcgLockedOverlay overlay;

	@Inject
	private TcgLockedItemOverlay itemOverlay;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private TcgLockedPanel panel;

	private NavigationButton navButton;

	/** Lower-cased owned card names, refreshed from the TCG plugin state. */
	private volatile Set<String> ownedLower = Collections.emptySet();

	/** Normalized ("card key") forms of the owned names, so item variants unlock from a single base card. */
	private volatile Set<String> ownedNormalized = Collections.emptySet();

	/** Normalized keys from the user's always-allow config list. */
	private volatile Set<String> extraAllowNormalized = Collections.emptySet();

	/** Item names currently equipped that the player does not own a card for (for overlay + de-duped chat warns). */
	private volatile List<String> equippedViolationNames = Collections.emptyList();

	/** Item names currently in the inventory that are locked. */
	private volatile List<String> lockedInBagNames = Collections.emptyList();

	private final Set<Integer> warnedViolationItemIds = new HashSet<>();

	// Unlock tracking (mutated only on the client thread via refreshOwned).
	private final Set<String> previousOwned = new HashSet<>();
	private final Deque<TcgLockedStatus.Unlock> recentUnlocks = new ArrayDeque<>();
	private boolean baselineEstablished;
	private int sessionUnlocks;
	private long lastUpdatedMs;

	@Provides
	TcgLockedConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TcgLockedConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel.setRefreshAction(this::manualRefresh);
		navButton = NavigationButton.builder()
			.tooltip("TCG Locked")
			.icon(TcgLockedPanel.crestIcon(24))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		overlayManager.add(overlay);
		overlayManager.add(itemOverlay);

		rebuildExtraAllow();
		scheduleRefresh();
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		overlayManager.remove(overlay);
		overlayManager.remove(itemOverlay);

		ownedLower = Collections.emptySet();
		ownedNormalized = Collections.emptySet();
		extraAllowNormalized = Collections.emptySet();
		equippedViolationNames = Collections.emptyList();
		lockedInBagNames = Collections.emptyList();
		warnedViolationItemIds.clear();
		previousOwned.clear();
		recentUnlocks.clear();
		baselineEstablished = false;
		sessionUnlocks = 0;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			scheduleRefresh();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		String group = event.getGroup();
		if (TCG_STATE_GROUP.equals(group))
		{
			// The TCG plugin saved its state (e.g. a pack was opened) — unlock newly-pulled cards immediately.
			// Fully event-driven: this fires on every osrstcg save, so no periodic polling is needed.
			if (TCG_STATE_KEY.equals(event.getKey()))
			{
				scheduleRefresh();
			}
		}
		else if (TcgLockedConfig.GROUP.equals(group))
		{
			rebuildExtraAllow();
			scheduleRefresh();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (config.enforcement() != TcgLockedConfig.Enforcement.BLOCK)
		{
			return;
		}

		String verb = event.getOption() == null ? "" : event.getOption().toLowerCase(Locale.ROOT).trim();
		if (!isGatedVerb(verb))
		{
			return;
		}

		int itemId = event.getItemId();
		if (itemId <= 0 || isUnlocked(itemId))
		{
			return;
		}

		// The just-added entry is the last element; drop it so the locked option can't be clicked.
		MenuEntry[] entries = client.getMenuEntries();
		if (entries.length > 0)
		{
			client.setMenuEntries(Arrays.copyOf(entries, entries.length - 1));
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Backstop for any path that reaches a gated action without going through the menu strip above.
		if (config.enforcement() != TcgLockedConfig.Enforcement.BLOCK)
		{
			return;
		}

		String verb = event.getMenuOption() == null ? "" : event.getMenuOption().toLowerCase(Locale.ROOT).trim();
		if (!isGatedVerb(verb))
		{
			return;
		}

		int itemId = event.getItemId();
		if (itemId <= 0 || isUnlocked(itemId))
		{
			return;
		}

		event.consume();
		warn("Locked: you don't own the card for " + itemName(itemId) + ".");
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();
		if (containerId == InventoryID.WORN)
		{
			recomputeEquippedViolations(event.getItemContainer());
			publishStatus();
		}
		else if (containerId == InventoryID.INV)
		{
			recomputeLockedInBag(event.getItemContainer());
			publishStatus();
		}
	}

	private void recomputeEquippedViolations(ItemContainer worn)
	{
		if (worn == null)
		{
			equippedViolationNames = Collections.emptyList();
			return;
		}

		List<String> names = new ArrayList<>();
		Set<Integer> currentViolationIds = new HashSet<>();
		for (Item item : worn.getItems())
		{
			int id = item.getId();
			if (id <= 0 || isUnlocked(id))
			{
				continue;
			}
			currentViolationIds.add(id);
			String name = itemName(id);
			names.add(name);
			if (warnedViolationItemIds.add(id))
			{
				warn("Locked item equipped without its card: " + name + ".");
			}
		}
		warnedViolationItemIds.retainAll(currentViolationIds);
		equippedViolationNames = Collections.unmodifiableList(names);
	}

	private void recomputeLockedInBag(ItemContainer inventory)
	{
		if (inventory == null)
		{
			lockedInBagNames = Collections.emptyList();
			return;
		}
		TreeSet<String> names = new TreeSet<>();
		for (Item item : inventory.getItems())
		{
			int id = item.getId();
			if (id <= 0 || isUnlocked(id))
			{
				continue;
			}
			names.add(itemName(itemManager.canonicalize(id)));
		}
		lockedInBagNames = new ArrayList<>(names);
	}

	private void scheduleRefresh()
	{
		clientThread.invokeLater(this::refreshOwned);
	}

	private void manualRefresh()
	{
		scheduleRefresh();
	}

	/** Runs on the client thread (scheduled via {@link #scheduleRefresh()}), so unlock-diff state has no races. */
	private void refreshOwned()
	{
		Set<String> owned = collectionReader.readOwnedCardNamesLower();
		Set<String> normalized = new HashSet<>();
		for (String name : owned)
		{
			String key = TcgItemNameNormalizer.normalize(name);
			if (!key.isEmpty())
			{
				normalized.add(key);
			}
		}
		ownedLower = owned;
		ownedNormalized = normalized;

		if (baselineEstablished)
		{
			List<String> newlyUnlocked = new ArrayList<>();
			for (String name : owned)
			{
				if (!previousOwned.contains(name))
				{
					newlyUnlocked.add(name);
				}
			}
			if (!newlyUnlocked.isEmpty())
			{
				Collections.sort(newlyUnlocked);
				long now = System.currentTimeMillis();
				for (String name : newlyUnlocked)
				{
					String display = displayName(name);
					recentUnlocks.addFirst(new TcgLockedStatus.Unlock(display, now));
					sessionUnlocks++;
					announceUnlock(display);
				}
				while (recentUnlocks.size() > RECENT_UNLOCK_CAP)
				{
					recentUnlocks.removeLast();
				}
			}
		}
		else
		{
			// First load establishes the baseline silently — existing cards are not "just unlocked".
			baselineEstablished = true;
		}
		previousOwned.clear();
		previousOwned.addAll(owned);

		recomputeEquippedViolations(client.getItemContainer(InventoryID.WORN));
		recomputeLockedInBag(client.getItemContainer(InventoryID.INV));
		publishStatus();
	}

	private void publishStatus()
	{
		lastUpdatedMs = System.currentTimeMillis();
		TcgLockedStatus status = new TcgLockedStatus(
			isCollectionLoaded(),
			ownedLower.size(),
			sessionUnlocks,
			enforcementLabel(),
			new ArrayList<>(recentUnlocks),
			lockedInBagNames,
			equippedViolationNames,
			lastUpdatedMs);
		SwingUtilities.invokeLater(() -> panel.update(status));
	}

	private String enforcementLabel()
	{
		String mode = config.enforcement() == TcgLockedConfig.Enforcement.BLOCK ? "Blocking" : "Warn only";
		String scope = config.gateConsumables() ? "gear + food" : "gear";
		return mode + " · " + scope;
	}

	private void rebuildExtraAllow()
	{
		extraAllowNormalized = TcgItemNameNormalizer.normalizeCsv(config.extraAllowList());
	}

	private boolean isGatedVerb(String verb)
	{
		if (config.gateEquipment() && EQUIP_VERBS.contains(verb))
		{
			return true;
		}
		if (config.gateTeleports() && ACTIVATION_VERBS.contains(verb))
		{
			return true;
		}
		return config.gateConsumables() && CONSUME_VERBS.contains(verb);
	}

	/**
	 * @return true if the player owns a card matching the item (or gating is inactive / the item can't be identified).
	 * Package-private so the lock-icon overlay shares the exact same gating decision as menu enforcement.
	 */
	boolean isUnlocked(int itemId)
	{
		if (!isCollectionLoaded())
		{
			// No TCG collection for this profile: the gamemode is inert (avoids locking everything with zero cards).
			return true;
		}
		int canonical = itemManager.canonicalize(itemId);
		String name = itemName(canonical);
		if (name.isEmpty())
		{
			// Unknown item name: don't lock the player out of something we can't identify.
			return true;
		}
		if (ownedLower.contains(name.toLowerCase(Locale.ROOT)))
		{
			return true;
		}
		String key = TcgItemNameNormalizer.normalize(name);
		if (key.isEmpty())
		{
			return true;
		}
		if (ownedNormalized.contains(key) || extraAllowNormalized.contains(key))
		{
			return true;
		}
		return config.unlockStarterGear() && key.startsWith("bronze ");
	}

	private String itemName(int itemId)
	{
		String name = itemManager.getItemComposition(itemId).getName();
		return name == null ? "" : name.trim();
	}

	private void announceUnlock(String display)
	{
		if (config.announceUnlocks() && client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[TCG Locked] Unlocked: " + display + "!", null);
		}
	}

	private void warn(String message)
	{
		if (config.warnInChat() && client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[TCG Locked] " + message, null);
		}
	}

	private static String displayName(String lower)
	{
		if (lower == null || lower.isEmpty())
		{
			return "";
		}
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	/** For the overlay. */
	List<String> getEquippedViolationNames()
	{
		return equippedViolationNames;
	}

	/**
	 * @return true if a TCG collection is present for this profile. When no cards are owned at all (TCG plugin absent
	 * or unconfigured) the gamemode is inert and the lock-icon overlay stays hidden.
	 */
	boolean isCollectionLoaded()
	{
		return !ownedLower.isEmpty();
	}
}
