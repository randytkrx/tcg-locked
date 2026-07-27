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
		MenuAction.ITEM_USE_ON_NPC, MenuAction.WIDGET_TARGET_ON_NPC,
		MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION,
		MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION);
	private static final String SEEN_ITEMS_KEY = "seenItems";
	/** CSV of the RuneScape names whose collections are stored; each blob lives under its own key. */
	private static final String POOLED_PARTNERS_KEY = "pooledPartners";
	private static final String POOLED_PREFIX = "pooled_";
	private static final int RECENT_UNLOCK_CAP = 30;
	private static final int MAX_PARTY_PACKED_LENGTH = 16_384;

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
	/**
	 * Game ticks of no new cards before held unlocks are shown. Ticks are 600ms, so this is ~4.8s:
	 * longer than the gap between flipping one card and the next, short enough that a single unlock
	 * outside a pack still feels immediate.
	 */
	private static final int UNLOCK_QUIET_TICKS = 8;

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
	private TcgLockedPoolConsent poolConsent;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TcgLockedOverlay overlay;

	@Inject
	private TcgLockedItemOverlay itemOverlay;

	@Inject
	private TcgLockedSceneOutlineOverlay sceneOutlineOverlay;

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
	private TcgSharedCatalogManager sharedCatalogManager;

	@Inject
	private net.runelite.client.eventbus.EventBus eventBus;

	@Inject
	private net.runelite.client.plugins.PluginManager pluginManager;

	private NavigationButton navButton;
	private boolean started;
	private int lifecycleGeneration;

	/** Bronzeman TCG's PluginDescriptor name — detected so our enforcement can defer to its engine. */
	private static final String BRONZEMAN_PLUGIN_NAME = "Bronzeman TCG";

	/** True while the Bronzeman TCG plugin is installed AND enabled. */
	private volatile boolean bronzemanActive;

	/** Ticks until the next osrs-tcg API query; -1 once answered (pushes take over). */
	private int apiQueryTicks = -1;

	/**
	 * Whether this session has said in chat that no collection arrived, and that one did. Suspended
	 * locking is otherwise invisible — everything simply works, which reads as the plugin being
	 * broken rather than as the challenge not having started.
	 */
	private boolean saidNoCollection;
	private boolean saidCollectionLoaded;

	/** Players we've mentioned in chat as awaiting a pooling decision, so it's said once each. */
	private final Set<String> saidPoolPending = new HashSet<>();

	/**
	 * Member id to the name key we resolved for it. A {@link PartyMember} starts out as
	 * {@code <unknown>} until their client reports in, and relogging or hopping gives them a fresh
	 * id, so the current display name is not a reliable way to recognise someone.
	 */
	private final Map<Long, String> memberKeyById = new HashMap<>();

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
	/**
	 * Approved partners' card keys, by RuneScape name. Survives leaving the party and restarts —
	 * the party is only the transport, so a synced partner keeps unlocking things until you revoke
	 * them, refreshed whenever you are in a party together again.
	 */
	private final Map<String, Set<String>> pooledKeys = new HashMap<>();

	/**
	 * Collections received from players not (yet) approved, this session only. Held so that saying
	 * yes applies immediately instead of waiting for them to broadcast again.
	 */
	private final Map<String, Set<String>> offeredKeys = new HashMap<>();
	/** Last progress values we broadcast, to avoid spamming the party with unchanged updates. */
	private int[] lastBroadcast;
	private Set<String> lastBroadcastOwned = Collections.emptySet();

	/** Pooled cards last offered to Bronzeman TCG; null re-sends even if the set is unchanged. */
	private Set<String> lastSharedWithBronzeman = Collections.emptySet();

	/** Unlocks detected but not yet shown, held while the collection is still changing. */
	private final List<String> pendingUnlocks = new ArrayList<>();
	/** Quiet ticks left before {@link #pendingUnlocks} is shown; -1 when nothing is waiting. */
	private int unlockQuietTicks = -1;

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
	private boolean lockbookDirty = true;
	private List<TcgLockedStatus.LockItem> cachedLockItems = Collections.emptyList();
	private int cachedLockbookUnlocked;
	private int cachedLockbookPooled;
	private Map<String, Integer> cachedContributions = Collections.emptyMap();
	private boolean sharedCatalogDirty = true;
	private TcgSharedCatalogSnapshot sharedCatalogSnapshot =
		new TcgSharedCatalogSnapshot(Collections.emptyMap());

	@Provides
	TcgLockedConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TcgLockedConfig.class);
	}

	@Override
	protected void startUp()
	{
		started = true;
		lifecycleGeneration++;
		sharedCatalogDirty = true;
		panel.setRefreshAction(this::manualRefresh);
		panel.setConsentAction(this::setPoolConsent);
		panel.setSharedCatalogAction(this::openSharedCatalog);
		sharedCatalogManager.start();
		navButton = NavigationButton.builder()
			.tooltip("TCG Locked")
			.icon(TcgLockedPanel.crestIcon(24))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		wsClient.registerMessage(TcgLockedPartyProgressMessage.class);
		wsClient.registerMessage(TcgLockedPartyUnlockMessage.class);
		wsClient.registerMessage(TcgLockedPartyWithdrawMessage.class);

		overlayManager.add(overlay);
		overlayManager.add(itemOverlay);
		overlayManager.add(sceneOutlineOverlay);
		// overlayManager.add(revealOverlay); // disabled with the reveal, see showPendingUnlocks

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
		started = false;
		lifecycleGeneration++;
		panel.setSharedCatalogAction(null);
		sharedCatalogManager.dispose();
		withdrawFromApprovedMembers();
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		wsClient.unregisterMessage(TcgLockedPartyProgressMessage.class);
		wsClient.unregisterMessage(TcgLockedPartyUnlockMessage.class);
		wsClient.unregisterMessage(TcgLockedPartyWithdrawMessage.class);
		partyProgress.clear();
		pooledKeys.clear();
		offeredKeys.clear();
		saidPoolPending.clear();
		memberKeyById.clear();
		saidNoCollection = false;
		saidCollectionLoaded = false;
		poolConsent.invalidate();
		lastBroadcast = null;
		lastBroadcastOwned = Collections.emptySet();
		// Withdraw the pooled cards from Bronzeman TCG: with this plugin off, the group's unlocks
		// should stop applying there too rather than linger for the rest of the session.
		shareUnlocksWithBronzeman();

		overlayManager.remove(overlay);
		overlayManager.remove(itemOverlay);
		overlayManager.remove(sceneOutlineOverlay);
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
		pendingUnlocks.clear();
		unlockQuietTicks = -1;
		seenItemIds.clear();
		cachedLockItems = Collections.emptyList();
		cachedContributions = Collections.emptyMap();
		lockbookDirty = true;
		sharedCatalogDirty = true;
		sharedCatalogSnapshot = new TcgSharedCatalogSnapshot(Collections.emptyMap());
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
			if ("partyShare".equals(event.getKey()))
			{
				if (config.partyShare())
				{
					lastBroadcast = null;
					lastBroadcastOwned = Collections.emptySet();
				}
				else
				{
					withdrawFromApprovedMembers();
				}
				markSharedCatalogDirty();
			}
			rebuildExtraAllow();
			lockbookDirty = true;
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
		if (unlockQuietTicks >= 0 && --unlockQuietTicks < 0)
		{
			showPendingUnlocks();
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
		withdrawFromApprovedMembers();
		collectionReader.invalidate();
		poolConsent.invalidate();
		pooledKeys.clear();
		offeredKeys.clear();
		saidPoolPending.clear();
		memberKeyById.clear();
		saidNoCollection = false;
		saidCollectionLoaded = false;
		seenProfileKey = null;
		seenItemIds.clear();
		cachedLockItems = Collections.emptyList();
		cachedContributions = Collections.emptyMap();
		ownedLower = Collections.emptySet();
		ownedNormalized = Collections.emptySet();
		previousOwned.clear();
		pendingUnlocks.clear();
		unlockQuietTicks = -1;
		recentUnlocks.clear();
		sessionUnlocks = 0;
		warnedViolationItemIds.clear();
		partyProgress.clear();
		lastBroadcast = null;
		lockbookDirty = true;
		markSharedCatalogDirty();
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
			invokeLaterIfStarted(() -> handlePartyProgress(message));
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

		Set<String> sent = null;
		String packed = message.getPackedOwnedKeys();
		if (packed != null && packed.length() <= MAX_PARTY_PACKED_LENGTH)
		{
			sent = cardCatalog.unpackKeysOrNull(packed);
		}
		else if (packed == null && message.getOwnedKeys() != null)
		{
			// Compatibility with clients released before packed party collections.
			sent = cardCatalog.filterKnownKeys(message.getOwnedKeys());
		}
		PartyMember from = partyService.getMemberById(message.getMemberId());
		String partnerKey = from == null ? "" : rememberMemberKey(from);
		boolean addressed = addressedToUs(message.getSharedWith());
		if (!partnerKey.isEmpty() && !addressed && message.getSharedWith() != null)
		{
			removePooledCollection(partnerKey);
		}
		else if (sent != null && !partnerKey.isEmpty() && addressed)
		{
			// Keys reach the whole party — there is no per-recipient send — so two things gate them:
			// the sender addressing us, and our own consent. Both must agree, which is what keeps
			// sharing mutual rather than something either side can impose.
			offeredKeys.put(partnerKey, sent);
			if (poolConsent.isApproved(partnerKey))
			{
				Set<String> previous = pooledKeys.put(partnerKey, sent);
				savePooledPartner(partnerKey, sent);
				lockbookDirty = true;
				if (!sent.equals(previous))
				{
					markSharedCatalogDirty();
				}
			}
		}
		else if (packed != null && !partnerKey.isEmpty() && addressed)
		{
			// A malformed or differently-versioned bitmap must not leave an older collection active.
			removePooledCollection(partnerKey);
		}
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
			invokeLaterIfStarted(() -> handlePartyUnlock(message));
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
		invokeLaterIfStarted(() ->
		{
			broadcastProgress(true);
			// They may have been offline when we unsynced them, in which case the withdrawal never
			// reached them and they still hold our cards. Re-send it whenever a declined player is
			// here; it is idempotent, so repeating it costs nothing.
			if (config.partyShare())
			{
				withdrawFromDeclinedMembers();
			}
			else
			{
				withdrawFromApprovedMembers();
			}
			reportPoolPending();
			publishStatus();
		});
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		invokeLaterIfStarted(() ->
		{
			// Drop what only made sense while they were here: their live progress, and any collection
			// they offered but was never approved. An APPROVED partner's cards deliberately survive —
			// that is the whole point of syncing with someone — and stay revocable in the panel.
			partyProgress.remove(event.getMemberId());
			String key = memberKeyById.remove(event.getMemberId());
			if (key != null && !key.isEmpty())
			{
				offeredKeys.remove(key);
			}
			publishStatus();
		});
	}

	/**
	 * Clears state that only means anything inside a party. Without this, leaving one group and
	 * joining another leaves the first group's members listed and their un-approved offers in memory
	 * for the rest of the session.
	 */
	private void prunePartyStateWhenAlone()
	{
		if (partyService.isInParty())
		{
			return;
		}
		if (!partyProgress.isEmpty() || !memberKeyById.isEmpty() || !offeredKeys.isEmpty())
		{
			partyProgress.clear();
			memberKeyById.clear();
			offeredKeys.clear();
		}
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
		rebuildLockbookIfNeeded();
		int[] current = new int[]{ownedLower.size(), cachedLockbookUnlocked, seenItemIds.size()};
		boolean collectionChanged = !ownedNormalized.equals(lastBroadcastOwned);
		if (!force && lastBroadcast != null
			&& lastBroadcast[0] == current[0] && lastBroadcast[1] == current[1] && lastBroadcast[2] == current[2]
			&& !collectionChanged)
		{
			return;
		}
		lastBroadcast = current;
		TcgLockedPartyProgressMessage message = new TcgLockedPartyProgressMessage();
		message.setCardsOwned(current[0]);
		message.setUnlocked(current[1]);
		message.setSeen(current[2]);
		// Counts always go out so the party list works for everyone. The collection goes out as soon
		// as anyone here is approved, addressed to exactly those people: a party message cannot skip
		// a recipient, so the payload names who it is for and everyone else ignores it. That lets you
		// share with the people you have synced without waiting on someone who is still undecided.
		Set<String> audience = approvedMembersPresent();
		message.setSharedWith(audience);
		if (!audience.isEmpty() && (force || collectionChanged))
		{
			message.setPackedOwnedKeys(cardCatalog.packKeys(ownedNormalized));
			// Keep interoperability with the currently released pre-bitmap client during rollout.
			message.setOwnedKeys(ownedNormalized);
			lastBroadcastOwned = Collections.unmodifiableSet(new HashSet<>(ownedNormalized));
		}
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
			MenuEntry entry = event.getMenuEntry();
			NPC npc = entry.getNpc();
			if (npc != null && npc.getName() != null && !isNpcUnlocked(npc.getName()))
			{
				removeMenuEntry(entry);
				return;
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

		removeMenuEntry(event.getMenuEntry());
	}

	private void removeMenuEntry(MenuEntry entry)
	{
		MenuEntry[] entries = client.getMenuEntries();
		List<MenuEntry> kept = new ArrayList<>(entries.length);
		for (MenuEntry candidate : entries)
		{
			if (candidate != entry)
			{
				kept.add(candidate);
			}
		}
		if (kept.size() != entries.length)
		{
			client.setMenuEntries(kept.toArray(new MenuEntry[0]));
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Backstop for any path that reaches a gated action without going through the menu strip above.
		if (enforcementDeferred())
		{
			return;
		}
		boolean block = config.enforcement() == TcgLockedConfig.Enforcement.BLOCK;

		// NPC backstop.
		if (effGateNpcs() && NPC_ACTIONS.contains(event.getMenuAction()))
		{
			NPC npc = event.getMenuEntry().getNpc();
			String npcName = npc != null && npc.getName() != null
				? npc.getName() : stripNpcTarget(event.getMenuTarget());
			if (!npcName.isEmpty() && !isNpcUnlocked(npcName))
			{
				if (block)
				{
					event.consume();
				}
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

		if (block)
		{
			event.consume();
		}
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
			lockbookDirty = true;
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
			if (id <= 0 || !effGateEquipment() || isUnlocked(id))
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
		invokeLaterIfStarted(this::refreshOwned);
	}

	private void invokeLaterIfStarted(Runnable action)
	{
		final int generation = lifecycleGeneration;
		clientThread.invokeLater(() ->
		{
			if (started && lifecycleGeneration == generation)
			{
				action.run();
			}
		});
	}

	private void manualRefresh()
	{
		// Re-ask unconditionally: the reply lands inline and the refresh below then reads it, so the
		// button recovers a stale collection instead of just repainting the same numbers.
		forceQueryTcgApi();
		scheduleRefresh();
	}

	private void openSharedCatalog()
	{
		invokeLaterIfStarted(() -> sharedCatalogManager.show(sharedCatalogSnapshot()));
	}

	private TcgSharedCatalogSnapshot sharedCatalogSnapshot()
	{
		if (!sharedCatalogDirty)
		{
			return sharedCatalogSnapshot;
		}
		Map<String, Set<String>> owners = new HashMap<>();
		if (config.partyShare())
		{
			for (String approved : poolConsent.approvedKeys())
			{
				owners.put(approved, Collections.emptySet());
			}
			for (Map.Entry<String, Set<String>> entry : pooledKeys.entrySet())
			{
				owners.put(entry.getKey(), entry.getValue() == null
					? Collections.emptySet() : new HashSet<>(entry.getValue()));
			}
		}
		sharedCatalogSnapshot = new TcgSharedCatalogSnapshot(owners);
		sharedCatalogDirty = false;
		return sharedCatalogSnapshot;
	}

	private void markSharedCatalogDirty()
	{
		sharedCatalogDirty = true;
		sharedCatalogManager.refreshIfVisible(sharedCatalogSnapshot());
	}

	/** Runs on the client thread (scheduled via {@link #scheduleRefresh()}), so unlock-diff state has no races. */
	private void refreshOwned()
	{
		lastUpdatedMs = System.currentTimeMillis();
		lockbookDirty = true;
		// Load this profile's lockbook once the RS profile is available (first refresh after login).
		String profileKey = configManager.getRSProfileKey();
		if (profileKey != null && !profileKey.equals(seenProfileKey))
		{
			seenProfileKey = profileKey;
			loadSeenItems();
			poolConsent.load(profileKey);
			loadPooledPartners();
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
				for (String name : newlyUnlocked)
				{
					pendingUnlocks.add(displayName(name));
				}
				// Cards land in the collection as a pack is opened, one per flip. Showing each one
				// as it arrives names the card before the player has turned it over, which gives
				// away their own pack. Holding until the collection goes quiet reveals the lot
				// together, after the pack is done.
				unlockQuietTicks = config.waitForPackOpenings() ? UNLOCK_QUIET_TICKS : 0;
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

		reportCollectionState(collectionKnown, owned.size());
		reportPoolPending();

		recomputeEquippedViolations(client.getItemContainer(InventoryID.WORN));
		recomputeLockedInBag(client.getItemContainer(InventoryID.INV));
		publishStatus();
	}

	/**
	 * Shows everything held back, once the collection has stopped changing. Runs on the client
	 * thread from the tick loop, like the rest of the unlock bookkeeping.
	 */
	private void showPendingUnlocks()
	{
		if (pendingUnlocks.isEmpty())
		{
			return;
		}
		long now = System.currentTimeMillis();
		for (String display : pendingUnlocks)
		{
			recentUnlocks.addFirst(new TcgLockedStatus.Unlock(display, now));
			sessionUnlocks++;
			announceUnlock(display);
			// Reveal disabled for now: cards enter the collection the moment a pack is opened, not as
			// each one is flipped, so the reveal names them before the player has turned them over.
			// The quiet-period delay only postpones that, it cannot know when the flipping is done.
			// Restore this (and the overlay registration in startUp) once OSRS TCG signals the end of
			// a pack reveal, or commits the cards when the interface closes.
			// revealOverlay.enqueue(display, now);
			broadcastUnlock(display);
		}
		while (recentUnlocks.size() > RECENT_UNLOCK_CAP)
		{
			recentUnlocks.removeLast();
		}
		pendingUnlocks.clear();
		// One chime for the batch: a pack's worth of them at once is the same spoiler in sound.
		playUnlockSound();
		publishStatus();
	}

	/**
	 * Says once per session whether the challenge is actually running. Without this, an OSRS TCG that
	 * never answered looks identical to a plugin that has stopped working: everything stays usable
	 * and the only clue is one line in a panel the player may not have open.
	 */
	private void reportCollectionState(boolean collectionKnown, int cardsOwned)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!collectionKnown)
		{
			if (!saidNoCollection)
			{
				saidNoCollection = true;
				chat("No collection from OSRS TCG yet, so nothing is locked. Check that the OSRS TCG "
					+ "plugin is enabled and has loaded your cards.");
			}
			return;
		}
		if (!saidCollectionLoaded)
		{
			saidCollectionLoaded = true;
			// Worth saying even after a "no collection" line: it is the correction to it.
			chat("Collection loaded: " + cardsOwned + (cardsOwned == 1 ? " card" : " cards")
				+ ". Locking is active.");
		}
	}

	/** Tells the player a party member is waiting on a pooling decision they can only see in the panel. */
	private void reportPoolPending()
	{
		if (!config.partyShare() || !partyService.isInParty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		List<PartyMember> members = partyService.getMembers();
		if (members == null)
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		long localId = local != null ? local.getMemberId() : -1L;
		for (PartyMember member : members)
		{
			String name = member.getDisplayName();
			String key = TcgLockedPoolConsent.key(name);
			if (member.getMemberId() == localId || key.isEmpty()
				|| poolConsent.decisionFor(name) != TcgLockedPoolConsent.Decision.PENDING
				|| !saidPoolPending.add(key))
			{
				continue;
			}
			chat(name.trim() + " is in your party. Open the TCG Locked panel to choose whether to pool "
				+ "unlocks with them; until then nothing is shared either way.");
		}
	}

	private void chat(String message)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[TCG Locked] " + message, null);
	}

	private void publishStatus()
	{
		prunePartyStateWhenAlone();
		rebuildLockbookIfNeeded();

		int unlocked = cachedLockbookUnlocked;
		int pooled = cachedLockbookPooled;
		Map<String, Integer> contributions = cachedContributions;
		List<TcgLockedStatus.LockItem> lockItems = cachedLockItems;

		Set<String> pooledCardKeys = new HashSet<>();
		for (Set<String> keys : pooledKeys.values())
		{
			if (keys != null)
			{
				pooledCardKeys.addAll(keys);
			}
		}

		TcgLockedStatus status = new TcgLockedStatus(
			isCollectionLoaded(), ownedLower.size(), sessionUnlocks, enforcementLabel(),
			new ArrayList<>(recentUnlocks), lockedInBagNames, equippedViolationNames, lockItems,
			seenItemIds.size(), unlocked, pooled, pooledCardKeys.size(), sharingBlockedBy(),
			buildPartyEntries(unlocked, contributions), lastUpdatedMs);
		final int generation = lifecycleGeneration;
		SwingUtilities.invokeLater(() ->
		{
			if (started && lifecycleGeneration == generation)
			{
				panel.update(status);
			}
		});

		broadcastProgress(false);
		shareUnlocksWithBronzeman();
	}

	private void rebuildLockbookIfNeeded()
	{
		if (!lockbookDirty)
		{
			return;
		}
		List<TcgLockedStatus.LockItem> all = new ArrayList<>(seenItemIds.size());
		int unlocked = 0;
		int pooled = 0;
		// How many seen items each partner opens on their own, so the group view can show what each
		// person is actually contributing rather than just that they are connected.
		Map<String, Integer> contributions = new HashMap<>();
		for (int id : seenItemIds)
		{
			TcgLockedStatus.UnlockSource source = unlockSourceFor(id);
			boolean locked = source == TcgLockedStatus.UnlockSource.LOCKED;
			if (!locked)
			{
				unlocked++;
			}
			List<String> unlockedBy = Collections.emptyList();
			if (source == TcgLockedStatus.UnlockSource.POOLED)
			{
				pooled++;
				unlockedBy = partnersUnlocking(TcgItemNameNormalizer.normalize(itemName(id)));
				for (String partner : unlockedBy)
				{
					contributions.merge(partner, 1, Integer::sum);
				}
			}
			String name = itemName(id);
			all.add(new TcgLockedStatus.LockItem(
				id, name.isEmpty() ? "Item" : name, locked, source, unlockedBy));
		}
		all.sort(Comparator.comparing(li -> li.name.toLowerCase(Locale.ROOT)));
		cachedLockItems = Collections.unmodifiableList(all);
		cachedLockbookUnlocked = unlocked;
		cachedLockbookPooled = pooled;
		cachedContributions = Collections.unmodifiableMap(contributions);
		lockbookDirty = false;
	}

	/**
	 * Rows for the panel's party section: everyone currently in the party, plus any synced partner
	 * who is not — their cards still apply, so they must remain revocable without having to get back
	 * into a party with them first.
	 */
	private List<TcgLockedStatus.PartyEntry> buildPartyEntries(int localUnlocked,
		Map<String, Integer> contributions)
	{
		if (!config.partyShare())
		{
			return Collections.emptyList();
		}
		List<PartyMember> members = partyService.isInParty() ? partyService.getMembers() : null;
		PartyMember local = partyService.getLocalMember();
		long localId = local != null ? local.getMemberId() : -1L;

		List<TcgLockedStatus.PartyEntry> out = new ArrayList<>();
		Set<String> listed = new HashSet<>();
		if (members != null)
		{
			Set<Long> live = new HashSet<>();
			for (PartyMember m : members)
			{
				live.add(m.getMemberId());
			}
			partyProgress.keySet().retainAll(live);
			memberKeyById.keySet().retainAll(live);

			for (PartyMember m : members)
			{
				String key = rememberMemberKey(m);
				boolean isLocal = m.getMemberId() == localId;
				if (!key.isEmpty())
				{
					listed.add(key);
				}
				String name = displayNameFor(m, key);
				if (isLocal)
				{
					out.add(new TcgLockedStatus.PartyEntry(name, ownedLower.size(), localUnlocked,
						seenItemIds.size(), true, TcgLockedPoolConsent.Decision.APPROVED, true, false,
						ownedNormalized.size(), 0));
					continue;
				}
				int[] p = partyProgress.get(m.getMemberId());
				// Nobody to save a decision against until their client reports a name, so no prompt
				// is offered for them yet rather than one that could not be acted on.
				TcgLockedPoolConsent.Decision consent = poolConsent.decisionFor(name);
				int shared = sharedCardCount(key);
				int gives = contributions.getOrDefault(key, 0);
				out.add(p != null
					? new TcgLockedStatus.PartyEntry(name, p[0], p[1], p[2], false, consent, true,
						!key.isEmpty(), shared, gives)
					: new TcgLockedStatus.PartyEntry(name, -1, -1, -1, false, consent, true,
						!key.isEmpty(), shared, gives));
			}
		}

		// Everyone you have approved, not merely those whose cards we happen to be holding. A partner
		// you synced with but never received a collection from (they were not running the plugin, or
		// had nothing to send yet) would otherwise vanish from the panel the moment you restart, even
		// though the approval is remembered — which reads as the group being forgotten.
		Set<String> saved = new TreeSet<>(poolConsent.approvedKeys());
		saved.addAll(pooledKeys.keySet());
		for (String partnerKey : saved)
		{
			if (listed.contains(partnerKey))
			{
				continue;
			}
			out.add(new TcgLockedStatus.PartyEntry(partnerKey, -1, -1, -1, false,
				TcgLockedPoolConsent.Decision.APPROVED, false, true,
				sharedCardCount(partnerKey), contributions.getOrDefault(partnerKey, 0)));
		}
		return out;
	}

	/**
	 * @return the stable name key for a member, remembering it the first time their client reports
	 * one so a later {@code <unknown>} (or a rejoin under a new id) still resolves to the same person.
	 */
	private String rememberMemberKey(PartyMember member)
	{
		String key = TcgLockedPoolConsent.key(member.getDisplayName());
		if (!key.isEmpty())
		{
			memberKeyById.put(member.getMemberId(), key);
			return key;
		}
		return memberKeyById.getOrDefault(member.getMemberId(), "");
	}

	private static String displayNameFor(PartyMember member, String key)
	{
		String name = member.getDisplayName();
		if (name != null && !name.trim().isEmpty() && !"<unknown>".equalsIgnoreCase(name.trim()))
		{
			return name.trim();
		}
		return key.isEmpty() ? "Member" : key;
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
		lockbookDirty = true;
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
		return unlockSourceFor(itemId) != TcgLockedStatus.UnlockSource.LOCKED;
	}

	/**
	 * The same decision {@link #isUnlocked(int)} makes, but reporting WHY. A player needs to be able
	 * to tell their own progress apart from what a group member is lending them — otherwise a pooled
	 * collection quietly reads as their own.
	 *
	 * <p>Order matters: owning the card yourself always wins over the group, so your own progress is
	 * never attributed to someone else.</p>
	 */
	TcgLockedStatus.UnlockSource unlockSourceFor(int itemId)
	{
		if (!collectionReader.hasCollection())
		{
			// The TCG plugin hasn't told us the collection yet (not installed, not started, or still
			// answering). Locking now would make every item unusable for reasons the player can't
			// act on, so nothing is locked until we actually know what they own.
			return TcgLockedStatus.UnlockSource.SUSPENDED;
		}
		int canonical = itemManager.canonicalize(itemId);
		String name = itemName(canonical);
		if (name.isEmpty())
		{
			// Unknown item name: don't lock the player out of something we can't identify.
			return TcgLockedStatus.UnlockSource.UNCARDED;
		}
		if (ownedLower.contains(name.toLowerCase(Locale.ROOT)))
		{
			return TcgLockedStatus.UnlockSource.OWNED;
		}
		String key = TcgItemNameNormalizer.normalize(name);
		if (key.isEmpty())
		{
			return TcgLockedStatus.UnlockSource.UNCARDED;
		}
		if (ownedNormalized.contains(key))
		{
			return TcgLockedStatus.UnlockSource.OWNED;
		}
		if (extraAllowNormalized.contains(key))
		{
			return TcgLockedStatus.UnlockSource.EXEMPT;
		}
		if (unlockedByParty(key))
		{
			return TcgLockedStatus.UnlockSource.POOLED;
		}
		if (config.unlockStarterGear() && key.startsWith("bronze "))
		{
			return TcgLockedStatus.UnlockSource.EXEMPT;
		}
		// No card exists for this item, so it is outside the challenge — the same rule monster gating
		// uses. Locking it would be permanent: there would be nothing the player could ever collect
		// to open it up. If normalization failed to match a card that does exist we land here too,
		// which errs towards letting the item through rather than towards a dead end.
		return cardCatalog.hasItemCard(key)
			? TcgLockedStatus.UnlockSource.LOCKED : TcgLockedStatus.UnlockSource.UNCARDED;
	}

	/** @return how many cards this partner currently has in your pool; 0 if they aren't synced. */
	private int sharedCardCount(String partnerKey)
	{
		Set<String> keys = pooledKeys.get(partnerKey);
		return keys == null ? 0 : keys.size();
	}

	/** @return the synced partners whose cards unlock this item, for the panel's group view. */
	private List<String> partnersUnlocking(String key)
	{
		if (!config.partyShare() || key.isEmpty())
		{
			return Collections.emptyList();
		}
		List<String> names = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : pooledKeys.entrySet())
		{
			Set<String> keys = entry.getValue();
			if (keys != null && keys.contains(key))
			{
				names.add(entry.getKey());
			}
		}
		return names;
	}

	/**
	 * Offers the pooled cards to Bronzeman TCG, which enforces the same locks from its own copy of
	 * the collection. Without this, pooled unlocks do nothing whenever that plugin is running: it
	 * blocks the item regardless of what we decide, so group play quietly stops working. Only cards
	 * from partners the player approved are sent, and only the pooled extras — their own collection
	 * it already has.
	 */
	private void shareUnlocksWithBronzeman()
	{
		Set<String> pooled = new HashSet<>();
		if (config.partyShare())
		{
			for (Set<String> keys : pooledKeys.values())
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

	/**
	 * @return true if a player you have approved owns a card for this key (pooled unlocks). The
	 * party is only how collections are exchanged, not how long they last: an approved partner's
	 * cards keep applying after you leave, and are refreshed the next time you are in a party
	 * together.
	 */
	private boolean unlockedByParty(String key)
	{
		if (!config.partyShare())
		{
			return false;
		}
		for (Set<String> keys : pooledKeys.values())
		{
			if (keys != null && keys.contains(key))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the people you could sync with, listed only while you have synced nobody here — at
	 * that point nothing of yours is going out at all. Once anyone is approved your collection is
	 * shared with them, so an undecided third party is no longer holding anything up.
	 */
	private List<String> sharingBlockedBy()
	{
		if (!config.partyShare() || !partyService.isInParty() || !approvedMembersPresent().isEmpty())
		{
			return Collections.emptyList();
		}
		List<PartyMember> members = partyService.getMembers();
		if (members == null)
		{
			return Collections.emptyList();
		}
		PartyMember local = partyService.getLocalMember();
		long localId = local != null ? local.getMemberId() : -1L;
		List<String> blocking = new ArrayList<>();
		for (PartyMember member : members)
		{
			if (member.getMemberId() != localId && !poolConsent.isApproved(member.getDisplayName()))
			{
				String key = rememberMemberKey(member);
				blocking.add(displayNameFor(member, key));
			}
		}
		return blocking;
	}

	/**
	 * @return true if a shared collection was meant for us. An absent list means an older build that
	 * predates addressing; those shared with the whole party, so treat it as addressed to us and let
	 * our own consent decide.
	 */
	private boolean addressedToUs(Set<String> sharedWith)
	{
		if (sharedWith == null)
		{
			return true;
		}
		String localName = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
		String me = TcgLockedPoolConsent.key(localName);
		return !me.isEmpty() && sharedWith.contains(me);
	}

	/** @return the present members you have approved — who your collection is addressed to. */
	private Set<String> approvedMembersPresent()
	{
		if (!config.partyShare() || !partyService.isInParty())
		{
			return Collections.emptySet();
		}
		List<PartyMember> members = partyService.getMembers();
		if (members == null)
		{
			return Collections.emptySet();
		}
		PartyMember local = partyService.getLocalMember();
		long localId = local != null ? local.getMemberId() : -1L;
		Set<String> audience = new HashSet<>();
		for (PartyMember member : members)
		{
			if (member.getMemberId() != localId && poolConsent.isApproved(member.getDisplayName()))
			{
				String key = rememberMemberKey(member);
				if (!key.isEmpty())
				{
					audience.add(key);
				}
			}
		}
		return audience;
	}

	/** Approve or revoke pooling with a player, from the panel. Revoking drops their cards at once. */
	private void setPoolConsent(String displayName, boolean approved)
	{
		String key = TcgLockedPoolConsent.key(displayName);
		if (key.isEmpty())
		{
			return;
		}
		invokeLaterIfStarted(() ->
		{
			if (approved)
			{
				poolConsent.approve(displayName);
				// Their collection may already be in hand from a broadcast we received but did not
				// apply, so approving takes effect immediately rather than waiting for the next one.
				Set<String> offered = offeredKeys.get(key);
				if (offered != null)
				{
					pooledKeys.put(key, offered);
					savePooledPartner(key, offered);
				}
			}
			else
			{
				poolConsent.decline(displayName);
				pooledKeys.remove(key);
				forgetPooledPartner(key);
				// Ask them to drop what we shared. Without this, unsyncing only stops future updates:
				// they keep everything already sent, saved to disk, indefinitely.
				sendWithdraw(key);
			}
			lockbookDirty = true;
			markSharedCatalogDirty();
			// Our own keys only go out once everyone present is approved, so a decision changes what
			// we broadcast as well as what we apply.
			broadcastProgress(true);
			refreshOwned();
		});
	}

	/** Re-asserts the withdrawal against every declined player currently in the party. */
	private void withdrawFromDeclinedMembers()
	{
		if (!partyService.isInParty())
		{
			return;
		}
		List<PartyMember> members = partyService.getMembers();
		if (members == null)
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		long localId = local != null ? local.getMemberId() : -1L;
		for (PartyMember member : members)
		{
			if (member.getMemberId() == localId)
			{
				continue;
			}
			String key = rememberMemberKey(member);
			if (!key.isEmpty()
				&& poolConsent.decisionFor(member.getDisplayName()) == TcgLockedPoolConsent.Decision.DECLINED)
			{
				sendWithdraw(key);
			}
		}
	}

	/** Retracts our cards from every approved member currently able to receive the message. */
	private void withdrawFromApprovedMembers()
	{
		if (!partyService.isInParty())
		{
			return;
		}
		List<PartyMember> members = partyService.getMembers();
		PartyMember local = partyService.getLocalMember();
		long localId = local != null ? local.getMemberId() : -1L;
		if (members == null)
		{
			return;
		}
		for (PartyMember member : members)
		{
			if (member.getMemberId() != localId && poolConsent.isApproved(member.getDisplayName()))
			{
				String key = rememberMemberKey(member);
				if (!key.isEmpty())
				{
					sendWithdraw(key);
				}
			}
		}
	}

	/** Tells a player to stop using our cards. Harmless if they aren't listening or already dropped them. */
	private void sendWithdraw(String partnerKey)
	{
		if (partnerKey.isEmpty() || !partyService.isInParty())
		{
			return;
		}
		TcgLockedPartyWithdrawMessage message = new TcgLockedPartyWithdrawMessage();
		message.setTarget(partnerKey);
		partyService.send(message);
	}

	@Subscribe
	public void onTcgLockedPartyWithdrawMessage(TcgLockedPartyWithdrawMessage message)
	{
		if (message != null)
		{
			invokeLaterIfStarted(() -> handlePartyWithdraw(message));
		}
	}

	/**
	 * Someone has withdrawn our access to their cards. Drop their collection and forget it, exactly
	 * as if we had unsynced them ourselves — but leave our consent decision alone, so if they share
	 * again later it applies without having to be re-approved.
	 */
	private void handlePartyWithdraw(TcgLockedPartyWithdrawMessage message)
	{
		if (isLocalMember(message.getMemberId()))
		{
			return;
		}
		String localName = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
		String me = TcgLockedPoolConsent.key(localName);
		if (me.isEmpty() || !me.equals(TcgLockedPoolConsent.key(message.getTarget())))
		{
			// Addressed to someone else in the party; party messages reach everyone.
			return;
		}
		PartyMember from = partyService.getMemberById(message.getMemberId());
		String senderKey = from == null ? "" : rememberMemberKey(from);
		if (senderKey.isEmpty())
		{
			return;
		}
		boolean had = pooledKeys.remove(senderKey) != null;
		offeredKeys.remove(senderKey);
		forgetPooledPartner(senderKey);
		if (had)
		{
			lockbookDirty = true;
			markSharedCatalogDirty();
			chat(from.getDisplayName().trim() + " stopped sharing their cards with you.");
			refreshOwned();
		}
	}

	private void removePooledCollection(String partnerKey)
	{
		boolean had = pooledKeys.remove(partnerKey) != null;
		offeredKeys.remove(partnerKey);
		forgetPooledPartner(partnerKey);
		if (had)
		{
			lockbookDirty = true;
			markSharedCatalogDirty();
		}
	}

	/** Stores an approved partner's collection as a packed bitmap under their name. */
	private void savePooledPartner(String partnerKey, Set<String> keys)
	{
		String packed = cardCatalog.packKeys(keys);
		if (packed.isEmpty())
		{
			forgetPooledPartner(partnerKey);
			return;
		}
		configManager.setRSProfileConfiguration(TcgLockedConfig.GROUP, pooledConfigKey(partnerKey), packed);
		Set<String> names = new java.util.LinkedHashSet<>(readPooledPartnerNames());
		if (names.add(partnerKey))
		{
			configManager.setRSProfileConfiguration(
				TcgLockedConfig.GROUP, POOLED_PARTNERS_KEY, String.join(",", names));
		}
	}

	private void forgetPooledPartner(String partnerKey)
	{
		configManager.unsetRSProfileConfiguration(TcgLockedConfig.GROUP, pooledConfigKey(partnerKey));
		Set<String> names = new java.util.LinkedHashSet<>(readPooledPartnerNames());
		if (names.remove(partnerKey))
		{
			configManager.setRSProfileConfiguration(
				TcgLockedConfig.GROUP, POOLED_PARTNERS_KEY, String.join(",", names));
		}
	}

	/** Restores approved partners' collections so their unlocks survive a restart. */
	private void loadPooledPartners()
	{
		pooledKeys.clear();
		lockbookDirty = true;
		for (String partnerKey : readPooledPartnerNames())
		{
			if (!poolConsent.isApproved(partnerKey))
			{
				// Approval was revoked while their blob lingered; drop it rather than honour it.
				forgetPooledPartner(partnerKey);
				continue;
			}
			String packed = configManager.getRSProfileConfiguration(
				TcgLockedConfig.GROUP, pooledConfigKey(partnerKey));
			Set<String> keys = cardCatalog.unpackKeys(packed);
			if (!keys.isEmpty())
			{
				pooledKeys.put(partnerKey, keys);
			}
			else if (packed != null && !packed.isEmpty())
			{
				forgetPooledPartner(partnerKey);
			}
		}
		markSharedCatalogDirty();
	}

	private List<String> readPooledPartnerNames()
	{
		String csv = configManager.getRSProfileConfiguration(TcgLockedConfig.GROUP, POOLED_PARTNERS_KEY);
		if (csv == null || csv.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		List<String> names = new ArrayList<>();
		for (String part : csv.split(","))
		{
			String key = TcgLockedPoolConsent.key(part);
			if (!key.isEmpty())
			{
				names.add(key);
			}
		}
		return names;
	}

	private static String pooledConfigKey(String partnerKey)
	{
		return POOLED_PREFIX + partnerKey.replace(' ', '_');
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

	/** Returns the NPC's outline state using the same collection, allow-list and party rules as enforcement. */
	TcgLockedHighlightState npcHighlightState(String npcName)
	{
		return TcgLockedHighlightState.forNpc(
			collectionReader.hasCollection(),
			npcName,
			cardCatalog,
			card -> ownedNormalized.contains(card)
				|| extraAllowNormalized.contains(card)
				|| unlockedByParty(card));
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
