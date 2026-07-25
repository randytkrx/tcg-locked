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
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.SoundEffectID;
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
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

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
	/** Menu actions that count as interacting with an NPC (not Examine). */
	private static final Set<MenuAction> NPC_ACTIONS = Set.of(
		MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION,
		MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION);
	private static final String SEEN_ITEMS_KEY = "seenItems";
	private static final int RECENT_UNLOCK_CAP = 30;
	private static final int LOCKBOOK_CAP = 400;

	// OSRS TCG's PluginMessage API (its OwnedCardNamesApiService). We post a query; it replies
	// with "owned-names" and pushes "owned-names-changed" after every collection change. String
	// constants are copied, not imported — Hub plugins can't see each other's classes.
	private static final String TCG_API_NAMESPACE = "osrstcg";
	private static final String TCG_API_QUERY = "query-owned-names";
	private static final String TCG_API_REPLY = "owned-names";
	private static final String TCG_API_CHANGED = "owned-names-changed";
	private static final String TCG_API_NAMES_KEY = "ownedNames";
	/** Re-query cadence (game ticks) until OSRS TCG answers — covers it starting after us. */
	private static final int API_QUERY_RETRY_TICKS = 100;

	// Bronzeman TCG's shared-unlocks API. When its engine is the one enforcing, party pooling has no
	// effect on its own — every check it makes reads the player's collection alone — so the pooled
	// cards are offered to it. Each message carries the complete set and replaces the last.
	private static final String BRONZEMAN_API_NAMESPACE = "bronzemantcg";
	private static final String BRONZEMAN_SHARED_UNLOCKS = "shared-unlocks";
	/** Its handshake: it asks, we answer with the complete current set. */
	private static final String BRONZEMAN_QUERY = "query-shared-unlocks";
	private static final String BRONZEMAN_SOURCE_KEY = "source";
	private static final String BRONZEMAN_NAMES_KEY = "cardNames";
	private static final String BRONZEMAN_SOURCE_NAME = "TCG Locked";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TcgLockedConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private TcgLockedCollectionReader collectionReader;

	@Inject
	private TcgCardCatalog cardCatalog;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TcgLockedOverlay overlay;

	@Inject
	private TcgLockedItemOverlay itemOverlay;

	@Inject
	private TcgLockedRevealOverlay revealOverlay;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PartyService partyService;

	@Inject
	private WSClient wsClient;

	@Inject
	private TcgLockedPanel panel;

	@Inject
	private net.runelite.client.eventbus.EventBus eventBus;

	@Inject
	private net.runelite.client.plugins.PluginManager pluginManager;

	private NavigationButton navButton;

	/** Bronzeman TCG's PluginDescriptor name — detected so our enforcement can defer to its engine. */
	private static final String BRONZEMAN_PLUGIN_NAME = "Bronzeman TCG";

	/** True while the Bronzeman TCG plugin is installed AND enabled. */
	private volatile boolean bronzemanActive;

	/** Ticks until the next osrs-tcg API query; -1 once answered (pushes take over). */
	private int apiQueryTicks = -1;

	/** Lower-cased owned card names, refreshed from the TCG plugin state. */
	private volatile Set<String> ownedLower = Collections.emptySet();

	/** Normalized ("card key") forms of the owned names, so item variants unlock from a single base card. */
	private volatile Set<String> ownedNormalized = Collections.emptySet();

	/** Normalized keys from the user's always-allow config list. */
	private volatile Set<String> extraAllowNormalized = Collections.emptySet();

	/** Item ids the player has encountered (inventory/bank/worn); the lockbook, persisted per RS profile. */
	private final Set<Integer> seenItemIds = new HashSet<>();
	private String seenProfileKey;

	/** Other party members' shared progress: memberId to {cardsOwned, unlocked, seen}. */
	private final Map<Long, int[]> partyProgress = new HashMap<>();
	/** Other party members' owned card keys, for pooled unlocks: memberId to their normalized keys. */
	private final Map<Long, Set<String>> partyOwnedKeys = new HashMap<>();
	/** Last progress values we broadcast, to avoid spamming the party with unchanged updates. */
	private int[] lastBroadcast;

	/** Pooled cards last offered to Bronzeman TCG; null re-sends even if the set is unchanged. */
	private Set<String> lastSharedWithBronzeman = Collections.emptySet();

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

		wsClient.registerMessage(TcgLockedPartyProgressMessage.class);
		wsClient.registerMessage(TcgLockedPartyUnlockMessage.class);

		overlayManager.add(overlay);
		overlayManager.add(itemOverlay);
		overlayManager.add(revealOverlay);

		rebuildExtraAllow();
		detectBronzeman();
		// Ask OSRS TCG for the collection right away (answers inline if it's already
		// running, e.g. this plugin was toggled on mid-session); the game-tick loop
		// retries in case that plugin starts after us.
		queryTcgApi();
		apiQueryTicks = collectionReader.hasCollection() ? -1 : 0;
		scheduleRefresh();
	}

	/** Re-checks whether Bronzeman TCG is enabled; its engine takes over locking when it is. */
	private void detectBronzeman()
	{
		boolean active = false;
		for (Plugin p : pluginManager.getPlugins())
		{
			PluginDescriptor descriptor = p.getClass().getAnnotation(PluginDescriptor.class);
			if (descriptor != null && BRONZEMAN_PLUGIN_NAME.equals(descriptor.name())
				&& pluginManager.isPluginEnabled(p))
			{
				active = true;
				break;
			}
		}
		bronzemanActive = active;
	}

	/**
	 * True when locking is handed off to Bronzeman TCG (it's enabled and the user kept the
	 * default compatibility setting). Our menu-stripping, padlocks and warnings stand down;
	 * the lockbook, reveals, panel and party features keep running.
	 */
	boolean enforcementDeferred()
	{
		return config.deferToBronzeman() && bronzemanActive;
	}

	@Subscribe
	public void onPluginChanged(net.runelite.client.events.PluginChanged event)
	{
		final boolean was = bronzemanActive;
		detectBronzeman();
		if (was != bronzemanActive)
		{
			// Bronzeman TCG starting up missed anything offered before it did, so re-offer on the
			// next refresh rather than leaving the group's cards unknown to the plugin now enforcing.
			lastSharedWithBronzeman = null;
			scheduleRefresh(); // refreshes the panel footer + overlay visibility
		}
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		wsClient.unregisterMessage(TcgLockedPartyProgressMessage.class);
		wsClient.unregisterMessage(TcgLockedPartyUnlockMessage.class);
		partyProgress.clear();
		partyOwnedKeys.clear();
		lastBroadcast = null;
		// Withdraw the pooled cards from Bronzeman TCG: with this plugin off, the group's unlocks
		// should stop applying there too rather than linger for the rest of the session.
		shareUnlocksWithBronzeman();

		overlayManager.remove(overlay);
		overlayManager.remove(itemOverlay);
		overlayManager.remove(revealOverlay);
		revealOverlay.clear();

		collectionReader.invalidate();
		apiQueryTicks = -1;
		ownedLower = Collections.emptySet();
		ownedNormalized = Collections.emptySet();
		extraAllowNormalized = Collections.emptySet();
		equippedViolationNames = Collections.emptyList();
		lockedInBagNames = Collections.emptyList();
		warnedViolationItemIds.clear();
		previousOwned.clear();
		recentUnlocks.clear();
		seenItemIds.clear();
		seenProfileKey = null;
		baselineEstablished = false;
		sessionUnlocks = 0;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Re-ask on every login and world hop, and synchronously (EventBus.post replies inline
			// when the TCG plugin is running) so the refresh below reads the real collection rather
			// than a transiently unknown one. Also re-syncs after a push we missed while hopping.
			forceQueryTcgApi();
			scheduleRefresh();
		}
	}

	/**
	 * Posts the owned-names query, but only while the collection is still unknown — the TCG plugin
	 * pushes every change after that, so re-asking would be noise.
	 */
	private void queryTcgApi()
	{
		if (!collectionReader.hasCollection())
		{
			forceQueryTcgApi();
		}
	}

	/**
	 * Asks for the collection even if we already have one. The query is answered with a fresh
	 * snapshot, so this recovers from a push we never saw — which is what the panel's Refresh
	 * button needs to do to be worth pressing.
	 */
	private void forceQueryTcgApi()
	{
		eventBus.post(new net.runelite.client.events.PluginMessage(TCG_API_NAMESPACE, TCG_API_QUERY));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (TcgLockedConfig.GROUP.equals(event.getGroup()))
		{
			rebuildExtraAllow();
			scheduleRefresh();
		}
	}

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		// Query the osrs-tcg PluginMessage API until it answers; once a payload has arrived,
		// its pushes keep us current and this stops (apiQueryTicks stays -1).
		if (apiQueryTicks >= 0 && --apiQueryTicks < 0)
		{
			queryTcgApi();
			// EventBus.post is synchronous, so an answered query flips hasCollection before this line.
			apiQueryTicks = collectionReader.hasCollection() ? -1 : API_QUERY_RETRY_TICKS;
		}
	}

	/**
	 * OSRS TCG's PluginMessage API: both the reply to our query and unsolicited pushes after
	 * collection changes carry the same owned-names payload, so they share a path.
	 */
	@Subscribe
	public void onPluginMessage(net.runelite.client.events.PluginMessage event)
	{
		if (BRONZEMAN_API_NAMESPACE.equals(event.getNamespace()))
		{
			if (BRONZEMAN_QUERY.equals(event.getName()))
			{
				// Bronzeman TCG asks after starting, switching profile, or having sharing turned
				// back on — in each case it has forgotten what we sent, so answer with the current
				// set even when it hasn't changed.
				lastSharedWithBronzeman = null;
				shareUnlocksWithBronzeman();
			}
			return;
		}
		if (!TCG_API_NAMESPACE.equals(event.getNamespace())
			|| (!TCG_API_REPLY.equals(event.getName()) && !TCG_API_CHANGED.equals(event.getName())))
		{
			return;
		}
		java.util.Map<String, Object> data = event.getData();
		Object names = data == null ? null : data.get(TCG_API_NAMES_KEY);
		if (!(names instanceof List))
		{
			return;
		}
		boolean firstPayload = !collectionReader.hasCollection();
		collectionReader.onApiOwnedNames((List<?>) names);
		if (firstPayload && collectionReader.hasCollection())
		{
			log.debug("TCG Locked: osrs-tcg PluginMessage API active; collection now push-updated.");
		}
		scheduleRefresh();
	}

	@Subscribe
	public void onRuneScapeProfileChanged(net.runelite.client.events.RuneScapeProfileChanged event)
	{
		// New account/profile: drop the previous profile's collection (API data included),
		// re-baseline silently (the cross-profile delta is not "unlocks"), and re-query.
		collectionReader.invalidate();
		baselineEstablished = false;
		queryTcgApi();
		apiQueryTicks = collectionReader.hasCollection() ? -1 : 0;
		scheduleRefresh();
	}

	@Subscribe
	public void onTcgLockedPartyProgressMessage(TcgLockedPartyProgressMessage message)
	{
		// Party messages arrive off the client thread; defer so shared state stays single-threaded.
		if (message != null)
		{
			clientThread.invokeLater(() -> handlePartyProgress(message));
		}
	}

	private void handlePartyProgress(TcgLockedPartyProgressMessage message)
	{
		if (isLocalMember(message.getMemberId()))
		{
			return;
		}
		boolean firstContact = !partyProgress.containsKey(message.getMemberId());
		partyProgress.put(message.getMemberId(),
			new int[]{message.getCardsOwned(), message.getUnlocked(), message.getSeen()});
		partyOwnedKeys.put(message.getMemberId(),
			message.getOwnedKeys() != null ? message.getOwnedKeys() : Collections.emptySet());
		if (firstContact)
		{
			// A member we hadn't heard from: reply once so they see us too (converges, no loop).
			broadcastProgress(true);
		}
		publishStatus();
	}

	@Subscribe
	public void onTcgLockedPartyUnlockMessage(TcgLockedPartyUnlockMessage message)
	{
		if (message != null)
		{
			clientThread.invokeLater(() -> handlePartyUnlock(message));
		}
	}

	private void handlePartyUnlock(TcgLockedPartyUnlockMessage message)
	{
		if (isLocalMember(message.getMemberId()) || !config.partyShare())
		{
			return;
		}
		String item = message.getItemName();
		if (item == null || item.trim().isEmpty())
		{
			return;
		}
		PartyMember from = partyService.getMemberById(message.getMemberId());
		String who = from != null && from.getDisplayName() != null && !from.getDisplayName().trim().isEmpty()
			? from.getDisplayName().trim()
			: "A party member";
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[TCG Locked] " + who + " unlocked " + item.trim() + "!", null);
		}
	}

	@Subscribe
	public void onUserJoin(UserJoin event)
	{
		// Someone joined (or we joined and are seeing existing members): re-share our progress so they see us.
		clientThread.invokeLater(() -> broadcastProgress(true));
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		// Refresh the panel so a departed member drops off the party list.
		clientThread.invokeLater(this::publishStatus);
	}

	private boolean isLocalMember(long memberId)
	{
		PartyMember local = partyService.getLocalMember();
		return local != null && local.getMemberId() == memberId;
	}

	private void broadcastProgress(boolean force)
	{
		if (!config.partyShare() || !partyService.isInParty())
		{
			return;
		}
		int[] current = new int[]{ownedLower.size(), countUnlockedSeen(), seenItemIds.size()};
		if (!force && lastBroadcast != null
			&& lastBroadcast[0] == current[0] && lastBroadcast[1] == current[1] && lastBroadcast[2] == current[2])
		{
			return;
		}
		lastBroadcast = current;
		TcgLockedPartyProgressMessage message = new TcgLockedPartyProgressMessage();
		message.setCardsOwned(current[0]);
		message.setUnlocked(current[1]);
		message.setSeen(current[2]);
		message.setOwnedKeys(new HashSet<>(ownedNormalized));
		partyService.send(message);
	}

	private void broadcastUnlock(String itemName)
	{
		if (!config.partyShare() || !partyService.isInParty() || itemName == null || itemName.isEmpty())
		{
			return;
		}
		TcgLockedPartyUnlockMessage message = new TcgLockedPartyUnlockMessage();
		message.setItemName(itemName);
		partyService.send(message);
	}

	private int countUnlockedSeen()
	{
		int unlocked = 0;
		for (int id : seenItemIds)
		{
			if (isUnlocked(id))
			{
				unlocked++;
			}
		}
		return unlocked;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (config.enforcement() != TcgLockedConfig.Enforcement.BLOCK || enforcementDeferred())
		{
			return;
		}

		String verb = event.getOption() == null ? "" : event.getOption().toLowerCase(Locale.ROOT).trim();

		// NPC interaction: block any action on a locked carded monster except Examine.
		if (effGateNpcs() && !"examine".equals(verb))
		{
			MenuEntry[] entries = client.getMenuEntries();
			if (entries.length > 0)
			{
				NPC npc = entries[entries.length - 1].getNpc();
				if (npc != null && npc.getName() != null && !isNpcUnlocked(npc.getName()))
				{
					client.setMenuEntries(Arrays.copyOf(entries, entries.length - 1));
					return;
				}
			}
		}

		// Item interaction: block gated verbs on locked items.
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
		if (config.enforcement() != TcgLockedConfig.Enforcement.BLOCK || enforcementDeferred())
		{
			return;
		}

		// NPC backstop.
		if (effGateNpcs() && NPC_ACTIONS.contains(event.getMenuAction()))
		{
			String npcName = stripNpcTarget(event.getMenuTarget());
			if (!npcName.isEmpty() && !isNpcUnlocked(npcName))
			{
				event.consume();
				warn("Locked: you don't own the card for " + npcName + ".");
				return;
			}
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
		if (containerId == InventoryID.WORN || containerId == InventoryID.INV || containerId == InventoryID.BANK)
		{
			catalogSeen(event.getItemContainer());
		}

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
		else if (containerId == InventoryID.BANK)
		{
			publishStatus();
		}
	}

	/** Adds every held item to the persistent lockbook of "seen" items. */
	private void catalogSeen(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		boolean grew = false;
		for (Item item : container.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			int id = itemManager.canonicalize(item.getId());
			if (id > 0 && seenItemIds.add(id))
			{
				grew = true;
			}
		}
		if (grew)
		{
			saveSeenItems();
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
		// Re-ask unconditionally: the reply lands inline and the refresh below then reads it, so the
		// button recovers a stale collection instead of just repainting the same numbers.
		forceQueryTcgApi();
		scheduleRefresh();
	}

	/** Runs on the client thread (scheduled via {@link #scheduleRefresh()}), so unlock-diff state has no races. */
	private void refreshOwned()
	{
		// Load this profile's lockbook once the RS profile is available (first refresh after login).
		String profileKey = configManager.getRSProfileKey();
		if (profileKey != null && !profileKey.equals(seenProfileKey))
		{
			seenProfileKey = profileKey;
			loadSeenItems();
		}

		// Read the "is it known" flag BEFORE the set: if a payload lands between the two reads this
		// pass is treated as still-unknown and the next one baselines silently, which is the safe
		// direction. (Set-then-flag could announce an entire collection as unlocks.)
		final boolean collectionKnown = collectionReader.hasCollection();
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

		if (!collectionKnown)
		{
			// The empty set here means "not told yet", not "owns nothing". Diffing against it would
			// announce the whole collection as unlocks the moment it arrives, so hold the baseline
			// open until we have real data.
			baselineEstablished = false;
			previousOwned.clear();
		}
		else if (baselineEstablished)
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
					revealOverlay.enqueue(display, now);
					broadcastUnlock(display);
				}
				while (recentUnlocks.size() > RECENT_UNLOCK_CAP)
				{
					recentUnlocks.removeLast();
				}
				playUnlockSound();
			}
			previousOwned.clear();
			previousOwned.addAll(owned);
		}
		else
		{
			// First known collection establishes the baseline silently — cards already owned when we
			// started are not "just unlocked".
			baselineEstablished = true;
			previousOwned.clear();
			previousOwned.addAll(owned);
		}

		recomputeEquippedViolations(client.getItemContainer(InventoryID.WORN));
		recomputeLockedInBag(client.getItemContainer(InventoryID.INV));
		publishStatus();
	}

	private void publishStatus()
	{
		lastUpdatedMs = System.currentTimeMillis();

		List<TcgLockedStatus.LockItem> all = new ArrayList<>(seenItemIds.size());
		int unlocked = 0;
		for (int id : seenItemIds)
		{
			boolean locked = !isUnlocked(id);
			if (!locked)
			{
				unlocked++;
			}
			String name = itemName(id);
			all.add(new TcgLockedStatus.LockItem(id, name.isEmpty() ? "Item" : name, locked));
		}
		all.sort(Comparator.comparing(li -> li.name.toLowerCase(Locale.ROOT)));
		List<TcgLockedStatus.LockItem> lockItems = all.size() > LOCKBOOK_CAP
			? new ArrayList<>(all.subList(0, LOCKBOOK_CAP))
			: all;

		TcgLockedStatus status = new TcgLockedStatus(
			isCollectionLoaded(),
			ownedLower.size(),
			sessionUnlocks,
			enforcementLabel(),
			new ArrayList<>(recentUnlocks),
			lockedInBagNames,
			equippedViolationNames,
			lockItems,
			seenItemIds.size(),
			unlocked,
			buildPartyEntries(unlocked),
			lastUpdatedMs);
		SwingUtilities.invokeLater(() -> panel.update(status));

		broadcastProgress(false);
		shareUnlocksWithBronzeman();
	}

	private List<TcgLockedStatus.PartyEntry> buildPartyEntries(int localUnlocked)
	{
		if (!config.partyShare() || !partyService.isInParty())
		{
			return Collections.emptyList();
		}
		List<PartyMember> members = partyService.getMembers();
		if (members == null || members.isEmpty())
		{
			return Collections.emptyList();
		}
		PartyMember local = partyService.getLocalMember();
		long localId = local != null ? local.getMemberId() : -1L;

		Set<Long> live = new HashSet<>();
		for (PartyMember m : members)
		{
			live.add(m.getMemberId());
		}
		partyProgress.keySet().retainAll(live);
		partyOwnedKeys.keySet().retainAll(live);

		List<TcgLockedStatus.PartyEntry> out = new ArrayList<>();
		for (PartyMember m : members)
		{
			String name = m.getDisplayName() != null && !m.getDisplayName().trim().isEmpty()
				? m.getDisplayName().trim() : "Member";
			if (m.getMemberId() == localId)
			{
				out.add(new TcgLockedStatus.PartyEntry(name, ownedLower.size(), localUnlocked, seenItemIds.size(), true));
			}
			else
			{
				int[] p = partyProgress.get(m.getMemberId());
				out.add(p != null
					? new TcgLockedStatus.PartyEntry(name, p[0], p[1], p[2], false)
					: new TcgLockedStatus.PartyEntry(name, -1, -1, -1, false));
			}
		}
		return out;
	}

	private String enforcementLabel()
	{
		if (!collectionReader.hasCollection())
		{
			// Say so plainly: with no collection nothing is locked, and a player who sees "Blocking"
			// here while everything works would reasonably report the plugin as broken.
			return "Waiting for OSRS TCG";
		}
		if (enforcementDeferred())
		{
			return "Locking by Bronzeman TCG";
		}
		String mode = config.enforcement() == TcgLockedConfig.Enforcement.BLOCK ? "Blocking" : "Warn only";
		return mode + " · " + config.preset();
	}

	private void rebuildExtraAllow()
	{
		extraAllowNormalized = TcgItemNameNormalizer.normalizeCsv(config.extraAllowList());
	}

	private void loadSeenItems()
	{
		seenItemIds.clear();
		String csv = configManager.getRSProfileConfiguration(TcgLockedConfig.GROUP, SEEN_ITEMS_KEY);
		if (csv == null || csv.isEmpty())
		{
			return;
		}
		for (String part : csv.split(","))
		{
			try
			{
				int id = Integer.parseInt(part.trim());
				if (id > 0)
				{
					seenItemIds.add(id);
				}
			}
			catch (NumberFormatException ignored)
			{
				// skip malformed entry
			}
		}
	}

	private void saveSeenItems()
	{
		StringBuilder sb = new StringBuilder(seenItemIds.size() * 6);
		for (int id : seenItemIds)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(id);
		}
		configManager.setRSProfileConfiguration(TcgLockedConfig.GROUP, SEEN_ITEMS_KEY, sb.toString());
	}

	private boolean isGatedVerb(String verb)
	{
		if (effGateEquipment() && EQUIP_VERBS.contains(verb))
		{
			return true;
		}
		if (effGateTeleports() && ACTIVATION_VERBS.contains(verb))
		{
			return true;
		}
		return effGateConsumables() && CONSUME_VERBS.contains(verb);
	}

	// The difficulty preset overrides the individual toggles unless it is Custom.

	private boolean effGateEquipment()
	{
		// Every preset locks gear.
		return config.preset() != TcgLockedConfig.Preset.CUSTOM || config.gateEquipment();
	}

	private boolean effGateTeleports()
	{
		switch (config.preset())
		{
			case CUSTOM:
				return config.gateTeleports();
			case GEAR_ONLY:
				return false;
			default:
				return true;
		}
	}

	private boolean effGateConsumables()
	{
		switch (config.preset())
		{
			case CUSTOM:
				return config.gateConsumables();
			case EVERYTHING:
				return true;
			default:
				return false;
		}
	}

	private boolean effGateNpcs()
	{
		switch (config.preset())
		{
			case CUSTOM:
				return config.gateNpcs();
			case EVERYTHING:
				return true;
			default:
				return false;
		}
	}

	/**
	 * @return true if the item may be used: the collection isn't known yet, the item has no card at
	 * all, the player (or their party) owns its card, or it is bronze starter gear / allow-listed.
	 * Package-private so the lock-icon overlay shares the exact same gating decision as menu
	 * enforcement.
	 */
	boolean isUnlocked(int itemId)
	{
		if (!collectionReader.hasCollection())
		{
			// The TCG plugin hasn't told us the collection yet (not installed, not started, or still
			// answering). Locking now would make every item unusable for reasons the player can't
			// act on, so nothing is locked until we actually know what they own.
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
		if (ownedNormalized.contains(key) || extraAllowNormalized.contains(key) || unlockedByParty(key))
		{
			return true;
		}
		if (config.unlockStarterGear() && key.startsWith("bronze "))
		{
			return true;
		}
		// No card exists for this item, so it is outside the challenge — the same rule monster gating
		// uses. Locking it would be permanent: there would be nothing the player could ever collect
		// to open it up. If normalization failed to match a card that does exist we land here too,
		// which errs towards letting the item through rather than towards a dead end.
		return !cardCatalog.hasItemCard(key);
	}

	/**
	 * Offers the party's pooled cards to Bronzeman TCG, which enforces the same locks from its own
	 * copy of the collection. Without this, pooled unlocks do nothing whenever that plugin is
	 * running: it blocks the item regardless of what we decide, so group play quietly stops working.
	 * Only the pooled extras are sent — the player's own cards it already has.
	 */
	private void shareUnlocksWithBronzeman()
	{
		Set<String> pooled = new HashSet<>();
		if (config.partyShare())
		{
			for (Set<String> keys : partyOwnedKeys.values())
			{
				if (keys != null)
				{
					pooled.addAll(keys);
				}
			}
		}
		if (pooled.equals(lastSharedWithBronzeman))
		{
			return;
		}
		lastSharedWithBronzeman = pooled;
		Map<String, Object> data = new HashMap<>();
		data.put(BRONZEMAN_SOURCE_KEY, BRONZEMAN_SOURCE_NAME);
		data.put(BRONZEMAN_NAMES_KEY, new ArrayList<>(pooled));
		// Harmless when Bronzeman TCG isn't installed: nothing is subscribed to the namespace.
		eventBus.post(new net.runelite.client.events.PluginMessage(
			BRONZEMAN_API_NAMESPACE, BRONZEMAN_SHARED_UNLOCKS, data));
	}

	/** @return true if any party member owns a card for this key (pooled unlocks). */
	private boolean unlockedByParty(String key)
	{
		if (!config.partyShare())
		{
			return false;
		}
		for (Set<String> keys : partyOwnedKeys.values())
		{
			if (keys != null && keys.contains(key))
			{
				return true;
			}
		}
		return false;
	}

	private String itemName(int itemId)
	{
		String name = itemManager.getItemComposition(itemId).getName();
		return name == null ? "" : name.trim();
	}

	/**
	 * @return true if the monster may be interacted with: the collection isn't known yet, no card
	 * exists for it, or one of its cards is owned. Mirrors {@link #isUnlocked(int)} for NPC names.
	 */
	private boolean isNpcUnlocked(String npcName)
	{
		if (!collectionReader.hasCollection())
		{
			return true;
		}
		String key = TcgItemNameNormalizer.normalize(npcName);
		if (key.isEmpty())
		{
			return true;
		}
		Set<String> cards = cardCatalog.npcCards(key);
		if (cards.isEmpty())
		{
			// No card exists for this NPC (or unparseable name): always free.
			return true;
		}
		// Several cards can share one in-game name (an "Archer" is carded per location); any of them
		// unlocks it, and the card's own name is what the collection is keyed by, not the NPC's.
		for (String card : cards)
		{
			if (ownedNormalized.contains(card) || extraAllowNormalized.contains(card) || unlockedByParty(card))
			{
				return true;
			}
		}
		return false;
	}

	/** Strips colour tags and a trailing "(level-N)" from an NPC menu target to recover the plain name. */
	private static String stripNpcTarget(String target)
	{
		if (target == null)
		{
			return "";
		}
		return Text.removeTags(target).replaceAll("\\s*\\(level[^)]*\\)\\s*$", "").trim();
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
		if (config.warnInChat() && !enforcementDeferred() && client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[TCG Locked] " + message, null);
		}
	}

	private void playUnlockSound()
	{
		if (config.unlockSound() && client.getGameState() == GameState.LOGGED_IN)
		{
			client.playSoundEffect(SoundEffectID.GE_ADD_OFFER_DINGALING);
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
	 * @return true once the TCG plugin has supplied the collection, owning zero cards included. False
	 * means locking is suspended, which is what the panel reports rather than a card count of zero.
	 */
	boolean isCollectionLoaded()
	{
		return collectionReader.hasCollection();
	}
}
