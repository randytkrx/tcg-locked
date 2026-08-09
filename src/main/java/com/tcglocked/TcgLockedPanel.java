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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.MouseInputAdapter;
import javax.swing.plaf.basic.BasicScrollBarUI;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * A collector's-ledger style panel for TCG Locked: a lock-shield crest, two stat tiles, a "latest / recently unlocked"
 * feed, a "carrying but locked" list, equipped-violation warnings, and a footer with the enforcement mode, a relative
 * last-updated time and a manual refresh. Sections carry a gold left margin so it reads as a ledger, not a form.
 */
@Singleton
class TcgLockedPanel extends PluginPanel
{
	/** Parchment gold used sparingly for the crest, title and section margins. */
	private static final Color GOLD = new Color(0xC8, 0xA9, 0x51);
	private static final Color GOLD_DIM = new Color(0x6E, 0x5C, 0x2E);
	private static final Color INK = ColorScheme.TEXT_COLOR;
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color FAINT = new Color(0x8A, 0x8A, 0x8A);
	private static final Color LOCK_RED = new Color(0xD0, 0x5B, 0x5B);
	/** Group blue: everything borrowed from a partner is this colour, never the gold of your own progress. */
	private static final Color GROUP_BLUE = new Color(0x5B, 0x9B, 0xD5);
	private static final Color SURFACE = new Color(0x25, 0x25, 0x25);
	private static final Color SURFACE_RAISED = new Color(0x30, 0x30, 0x30);
	private static final Color BORDER = new Color(0x3B, 0x3B, 0x3B);
	private static final Color SUCCESS = new Color(0x62, 0xB5, 0x72);
	/** Where players are pointed for bugs and requests. */
	private static final String DISCORD_URL = "https://discord.gg/P4pPu6RnCj";
	private static final int MAX_RECENT_ROWS = 8;
	private static final int MAX_BAG_ROWS = 10;
	private static final int LOCKBOOK_GRID_CAP = 150;
	private static final int LOCKBOOK_COLS = 4;
	private static final int LOCKBOOK_ROW_HEIGHT = 34;
	private static final int LOCKBOOK_MAX_HEIGHT = 170;

	private enum LockFilter
	{
		ALL, UNLOCKED, LOCKED, GROUP
	}

	private final ItemManager itemManager;
	private final JPanel body = new JPanel();
	private Runnable refreshAction = TcgLockedPanel::noop;
	private Runnable sharedCatalogAction = TcgLockedPanel::noop;
	/** (player name, approved) — set by the plugin so the pooling controls can act. */
	private BiConsumer<String, Boolean> consentAction = TcgLockedPanel::noConsent;
	private LockFilter filter = LockFilter.ALL;
	private TcgLockedStatus lastStatus = emptyStatus();

	@Inject
	TcgLockedPanel(ItemManager itemManager)
	{
		this.itemManager = itemManager;
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(8, 8, 10, 8));
		add(body, BorderLayout.NORTH);

		// PluginPanel wraps us in a JScrollPane and never clears its default border, which draws a
		// pale line around the whole panel once the content is tall enough for it to engage.
		getScrollPane().setBorder(null);
		getScrollPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		getScrollPane().getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		styleScrollBar(getScrollPane().getVerticalScrollBar());

		render(lastStatus);
	}

	void setRefreshAction(Runnable action)
	{
		this.refreshAction = action == null ? TcgLockedPanel::noop : action;
	}

	void setConsentAction(BiConsumer<String, Boolean> action)
	{
		this.consentAction = action == null ? TcgLockedPanel::noConsent : action;
	}

	private static void noop()
	{
	}

	private static void noConsent(String playerName, boolean approved)
	{
	}

	/** Rebuilds the panel from a snapshot. Call on the EDT. */
	void update(TcgLockedStatus status)
	{
		this.lastStatus = status;
		render(status);
		revalidate();
		repaint();
	}

	void setSharedCatalogAction(Runnable action)
	{
		this.sharedCatalogAction = action == null ? TcgLockedPanel::noop : action;
	}

	void reset()
	{
		update(emptyStatus());
	}

	private void render(TcgLockedStatus status)
	{
		body.removeAll();
		body.add(buildHeader(status));
		body.add(vGap(8));
		body.add(buildStatTiles(status));
		body.add(vGap(8));
		body.add(section("Lockbook", buildCollection(status)));
		if (!status.recentUnlocks.isEmpty())
		{
			body.add(vGap(8));
			body.add(section("Latest pulls", buildRecent(status)));
		}
		if (!status.lockedInBag.isEmpty() || !status.equippedViolations.isEmpty())
		{
			body.add(vGap(8));
			body.add(section("Needs attention", buildAttention(status), LOCK_RED));
		}
		if (!status.party.isEmpty())
		{
			body.add(vGap(8));
			body.add(section("Group", buildParty(status), GROUP_BLUE));
		}
		body.add(vGap(8));
		body.add(buildFooter(status));
	}

	private JPanel buildAttention(TcgLockedStatus status)
	{
		JPanel content = vBox();
		if (!status.equippedViolations.isEmpty())
		{
			content.add(subheading("EQUIPPED", LOCK_RED));
			content.add(buildViolations(status));
		}
		if (!status.lockedInBag.isEmpty())
		{
			if (!status.equippedViolations.isEmpty())
			{
				content.add(vGap(6));
			}
			content.add(subheading("IN YOUR BAG", MUTED));
			content.add(buildLockedBag(status));
		}
		return content;
	}

	private JPanel buildParty(TcgLockedStatus status)
	{
		JPanel list = vBox();
		list.add(sharedCatalogButton());
		list.add(vGap(7));
		if (status.party.isEmpty())
		{
			list.add(emptyLine("No synced partners yet."));
		}

		if (!status.sharingBlockedBy.isEmpty())
		{
			// Nothing of theirs and nothing of yours moves until at least one person is synced.
			String who = String.join(", ", status.sharingBlockedBy);
			JLabel blocked = new JLabel("<html>Not sharing yet &mdash; sync " + who
				+ " to pool cards</html>");
			blocked.setFont(FontManager.getRunescapeSmallFont());
			blocked.setForeground(GOLD);
			blocked.setToolTipText("Your collection is only sent to people you have synced with. "
				+ "Sync someone here and it starts flowing both ways.");
			blocked.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
			blocked.setAlignmentX(Component.LEFT_ALIGNMENT);
			list.add(blocked);
		}

		if (status.pooledCards > 0)
		{
			// The headline answer to "what is my group actually giving me?"
			JLabel summary = new JLabel(status.pooledCards + " cards pooled in, opening "
				+ status.lockbookPooled + " of your items");
			summary.setFont(FontManager.getRunescapeSmallFont());
			summary.setForeground(GROUP_BLUE);
			summary.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
			summary.setAlignmentX(Component.LEFT_ALIGNMENT);
			list.add(summary);
		}

		for (TcgLockedStatus.PartyEntry e : status.party)
		{
			JPanel r = new JPanel(new BorderLayout(6, 0));
			r.setBackground(SURFACE_RAISED);
			r.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));

			String suffix = e.local ? " (you)" : e.present ? "" : " (away)";
			JLabel name = new JLabel(e.name + suffix);
			name.setFont(FontManager.getRunescapeSmallFont());
			name.setForeground(e.local ? GOLD
				: e.consent == TcgLockedPoolConsent.Decision.APPROVED ? GROUP_BLUE : INK);

			r.add(name, BorderLayout.CENTER);
			r.add(partyRowEast(e), BorderLayout.EAST);
			list.add(r);

			if (!e.local && e.consent == TcgLockedPoolConsent.Decision.APPROVED)
			{
				list.add(sharingLine(e));
			}
			if (!e.local && e.decidable && e.consent == TcgLockedPoolConsent.Decision.PENDING)
			{
				list.add(poolPrompt(e.name));
			}
		}
		return list;
	}

	private JButton sharedCatalogButton()
	{
		JButton button = new JButton("Open shared cards");
		button.setFocusPainted(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(GROUP_BLUE);
		button.setBackground(SURFACE_RAISED);
		button.setBorder(new CompoundBorder(new MatteBorder(1, 1, 1, 1, GROUP_BLUE.darker()),
			BorderFactory.createEmptyBorder(5, 8, 5, 8)));
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 29));
		button.setToolTipText("Uses local item sprites; no Wiki downloads");
		button.addActionListener(event -> sharedCatalogAction.run());
		return button;
	}

	/** What one synced partner is contributing: cards lent, and how many of your items only they open. */
	private JPanel sharingLine(TcgLockedStatus.PartyEntry e)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(SURFACE);
		row.setBorder(BorderFactory.createEmptyBorder(3, 7, 6, 7));

		// A partner you approved but have never received cards from is still a group member; saying
		// "no cards yet" is the truth, where showing nothing at all looks like they were forgotten.
		JLabel detail = new JLabel(e.sharedCards > 0
			? e.sharedCards + " cards shared"
			: e.present ? "waiting for their cards" : "no cards yet");
		detail.setFont(FontManager.getRunescapeSmallFont());
		detail.setForeground(FAINT);

		JLabel gives = new JLabel(e.contributes > 0 ? "opens " + e.contributes : "opens nothing new");
		gives.setFont(FontManager.getRunescapeSmallFont());
		gives.setForeground(e.contributes > 0 ? GROUP_BLUE : FAINT);
		// Deliberately not "only they open": several partners can share the same card, and each is
		// credited, so claiming exclusivity here would sometimes be a lie.
		gives.setToolTipText(e.contributes > 0
			? e.contributes + " items you have seen are opened by " + e.name + "'s cards"
			: e.name + " shares cards, but none of them open anything you have seen");

		row.add(detail, BorderLayout.CENTER);
		row.add(gives, BorderLayout.EAST);
		return row;
	}

	/** Progress for anyone settled; for a pending member the progress is replaced by the prompt below. */
	private JComponent partyRowEast(TcgLockedStatus.PartyEntry e)
	{
		if (e.local || !e.decidable || e.consent == TcgLockedPoolConsent.Decision.PENDING)
		{
			JLabel prog = new JLabel(e.unlocked < 0 ? "…" : e.unlocked + " / " + e.seen);
			prog.setFont(FontManager.getRunescapeSmallFont());
			prog.setForeground(FAINT);
			return prog;
		}

		JPanel east = new JPanel(new BorderLayout(4, 0));
		east.setBackground(SURFACE_RAISED);
		JLabel prog = new JLabel(e.unlocked < 0 ? "…" : e.unlocked + " / " + e.seen);
		prog.setFont(FontManager.getRunescapeSmallFont());
		prog.setForeground(FAINT);
		east.add(prog, BorderLayout.CENTER);

		boolean pooling = e.consent == TcgLockedPoolConsent.Decision.APPROVED;
		east.add(linkButton(pooling ? "unsync" : "sync",
			pooling ? LOCK_RED : ColorScheme.PROGRESS_COMPLETE_COLOR,
			pooling ? "Stop pooling cards with " + e.name : "Pool cards with " + e.name,
			() -> consentAction.accept(e.name, !pooling)), BorderLayout.EAST);
		return east;
	}

	/** The join prompt: nothing pools until this is answered, and ignoring it stays safe. */
	private JPanel poolPrompt(String playerName)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(SURFACE_RAISED);
		row.setBorder(BorderFactory.createEmptyBorder(0, 7, 6, 7));

		JLabel ask = new JLabel("Pool unlocks?");
		ask.setFont(FontManager.getRunescapeSmallFont());
		ask.setForeground(GOLD);

		JPanel actions = new JPanel(new BorderLayout(6, 0));
		actions.setBackground(SURFACE_RAISED);
		actions.add(linkButton("yes", ColorScheme.PROGRESS_COMPLETE_COLOR,
			"Share cards with " + playerName + " and use theirs",
			() -> consentAction.accept(playerName, true)), BorderLayout.CENTER);
		actions.add(linkButton("no", LOCK_RED,
			"Never pool with " + playerName,
			() -> consentAction.accept(playerName, false)), BorderLayout.EAST);

		row.add(ask, BorderLayout.CENTER);
		row.add(actions, BorderLayout.EAST);
		return row;
	}

	private JLabel linkButton(String text, Color color, String tooltip, Runnable onClick)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseInputAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				label.setForeground(color.brighter());
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				label.setForeground(color);
			}
		});
		return label;
	}

	// ---- header -----------------------------------------------------------------------------------------------

	private JPanel buildHeader(TcgLockedStatus status)
	{
		JPanel header = row();
		header.setBackground(SURFACE);
		header.setBorder(new CompoundBorder(new MatteBorder(1, 1, 1, 1, BORDER),
			BorderFactory.createEmptyBorder(9, 10, 9, 10)));
		JLabel crest = new JLabel(new ImageIcon(crestIcon(30)));
		crest.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
		header.add(crest, BorderLayout.WEST);

		JPanel titles = new JPanel();
		titles.setBackground(SURFACE);
		titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("TCG Locked");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));
		title.setForeground(INK);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel sub = new JLabel((status.collectionLoaded ? "●  " : "○  ")
			+ (status.enforcementLabel.isEmpty() ? "Waiting for collection" : status.enforcementLabel));
		sub.setFont(FontManager.getRunescapeSmallFont());
		sub.setForeground(status.collectionLoaded ? SUCCESS : GOLD);
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);

		titles.add(title);
		titles.add(sub);
		header.add(titles, BorderLayout.CENTER);
		return header;
	}

	// ---- stat tiles -------------------------------------------------------------------------------------------

	private JPanel buildStatTiles(TcgLockedStatus status)
	{
		JPanel tiles = new JPanel(new GridLayout(1, 3, 5, 0));
		tiles.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tiles.add(statTile(Integer.toString(status.cardsOwned), "CARDS", GOLD));
		int pct = status.lockbookSeen == 0 ? 0
			: (int) Math.round(100.0 * status.lockbookUnlocked / status.lockbookSeen);
		tiles.add(statTile(pct + "%", "OPEN", status.lockbookSeen == 0 ? FAINT : SUCCESS));
		String sessionText = status.sessionUnlocks > 0 ? "+" + status.sessionUnlocks : "0";
		tiles.add(statTile(sessionText, "SESSION",
			status.sessionUnlocks > 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : INK));
		return tiles;
	}

	private JPanel statTile(String value, String label, Color valueColor)
	{
		JPanel tile = new JPanel(new BorderLayout());
		tile.setBackground(SURFACE_RAISED);
		tile.setBorder(new CompoundBorder(new MatteBorder(1, 1, 1, 1, BORDER),
			BorderFactory.createEmptyBorder(7, 7, 6, 7)));

		JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		v.setForeground(valueColor);

		JLabel l = new JLabel(label);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(FAINT);

		tile.add(v, BorderLayout.CENTER);
		tile.add(l, BorderLayout.SOUTH);
		return tile;
	}

	// ---- collection lockbook ----------------------------------------------------------------------------------

	private JPanel buildCollection(TcgLockedStatus status)
	{
		JPanel box = vBox();
		if (status.lockbookSeen == 0)
		{
			box.add(emptyLine("Items you see get catalogued here."));
			return box;
		}

		int seen = status.lockbookSeen;
		int unlocked = status.lockbookUnlocked;
		int pct = seen == 0 ? 0 : (int) Math.round(100.0 * unlocked / seen);

		JLabel progress = new JLabel("<html><b>" + unlocked + "</b> of " + seen + " items available <font color='#8a8a8a'>· " + pct + "%</font></html>");
		progress.setFont(FontManager.getRunescapeSmallFont());
		progress.setForeground(INK);
		progress.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		progress.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.add(progress);

		if (status.lockbookPooled > 0)
		{
			// Says plainly how much of that progress is borrowed rather than earned.
			JLabel byGroup = new JLabel(status.lockbookPooled + " of those are unlocked by your group");
			byGroup.setFont(FontManager.getRunescapeSmallFont());
			byGroup.setForeground(GROUP_BLUE);
			byGroup.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
			byGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
			box.add(byGroup);
		}

		ProgressBar bar = new ProgressBar(seen == 0 ? 0f : (float) unlocked / seen);
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.add(bar);
		box.add(vGap(6));
		box.add(buildFilterRow());
		box.add(vGap(6));

		JPanel grid = new JPanel(new GridLayout(0, LOCKBOOK_COLS, 3, 3));
		grid.setBackground(SURFACE);
		int shown = 0;
		for (TcgLockedStatus.LockItem li : status.lockItems)
		{
			if (!passesFilter(li))
			{
				continue;
			}
			if (shown >= LOCKBOOK_GRID_CAP)
			{
				break;
			}
			String tip = li.name + lockNote(li);
			grid.add(new TcgLockedItemCell(itemManager, li.itemId, li.locked,
				li.source == TcgLockedStatus.UnlockSource.POOLED, tip));
			shown++;
		}

		if (shown == 0)
		{
			box.add(emptyLine(filter == LockFilter.LOCKED ? "Nothing locked here."
				: filter == LockFilter.GROUP ? "Nothing is unlocked by your group." : "Nothing to show."));
			return box;
		}

		// Contain the grid in a fixed-height dark scroll box so a full bank doesn't balloon the whole panel.
		JScrollPane scroll = new JScrollPane(grid,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBackground(SURFACE);
		scroll.getViewport().setBackground(SURFACE);
		scroll.setBorder(null);
		styleScrollBar(scroll.getVerticalScrollBar());
		scroll.setAlignmentX(Component.LEFT_ALIGNMENT);

		int rows = (shown + LOCKBOOK_COLS - 1) / LOCKBOOK_COLS;
		int viewHeight = Math.min(rows * LOCKBOOK_ROW_HEIGHT, LOCKBOOK_MAX_HEIGHT);
		scroll.setPreferredSize(new Dimension(PANEL_WIDTH - 16, viewHeight));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, viewHeight));
		box.add(scroll);
		return box;
	}

	private JPanel buildFilterRow()
	{
		JPanel row = new JPanel(new GridLayout(1, 4, 4, 0));
		row.setBackground(SURFACE);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		row.add(filterButton("All", LockFilter.ALL));
		row.add(filterButton("Unlocked", LockFilter.UNLOCKED));
		row.add(filterButton("Locked", LockFilter.LOCKED));
		row.add(filterButton("Group", LockFilter.GROUP));
		return row;
	}

	private JButton filterButton(String label, LockFilter value)
	{
		boolean active = filter == value;
		JButton button = new JButton(label);
		button.setFocusPainted(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(active ? GOLD : MUTED);
		button.setBackground(active ? SURFACE_RAISED : SURFACE);
		button.setBorder(new MatteBorder(0, 0, active ? 2 : 1, 0, active ? GOLD_DIM : BORDER));
		button.addActionListener(e ->
		{
			filter = value;
			update(lastStatus);
		});
		return button;
	}

	/** Tooltip suffix naming who an item is open through, so borrowed unlocks are never ambiguous. */
	private static String lockNote(TcgLockedStatus.LockItem item)
	{
		if (item.locked)
		{
			return " (locked)";
		}
		if (item.source != TcgLockedStatus.UnlockSource.POOLED)
		{
			return "";
		}
		return item.unlockedBy.isEmpty()
			? " (unlocked by your group)"
			: " (unlocked by " + String.join(", ", item.unlockedBy) + ")";
	}

	private boolean passesFilter(TcgLockedStatus.LockItem item)
	{
		switch (filter)
		{
			case UNLOCKED:
				return !item.locked;
			case LOCKED:
				return item.locked;
			case GROUP:
				// Only what the group is lending you - the answer to "what am I actually borrowing?"
				return item.source == TcgLockedStatus.UnlockSource.POOLED;
			default:
				return true;
		}
	}

	private static final class ProgressBar extends JComponent
	{
		private final float frac;

		private ProgressBar(float frac)
		{
			this.frac = Math.max(0f, Math.min(1f, frac));
			setPreferredSize(new Dimension(10, 6));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g2.fillRoundRect(0, 0, w, h, h, h);
			g2.setColor(GOLD);
			g2.fillRoundRect(0, 0, Math.round(w * frac), h, h, h);
			g2.dispose();
		}
	}

	// ---- recent unlocks ---------------------------------------------------------------------------------------

	private JPanel buildRecent(TcgLockedStatus status)
	{
		JPanel list = vBox();
		if (status.recentUnlocks.isEmpty())
		{
			list.add(emptyLine("Open a pack to start unlocking."));
			return list;
		}

		long now = status.updatedAtMs;
		int shown = Math.min(status.recentUnlocks.size(), MAX_RECENT_ROWS);
		for (int i = 0; i < shown; i++)
		{
			TcgLockedStatus.Unlock u = status.recentUnlocks.get(i);
			list.add(unlockRow(u.name, relativeTime(u.atMs, now), i == 0));
		}
		int extra = status.recentUnlocks.size() - shown;
		if (extra > 0)
		{
			list.add(emptyLine("+ " + extra + " more"));
		}
		return list;
	}

	private JPanel unlockRow(String name, String when, boolean latest)
	{
		JPanel r = new JPanel(new BorderLayout(6, 0));
		r.setBackground(SURFACE);
		r.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

		JLabel n = new JLabel(name);
		n.setFont(latest ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
		n.setForeground(latest ? GOLD : INK);

		JLabel t = new JLabel(latest ? "just unlocked" : when);
		t.setFont(FontManager.getRunescapeSmallFont());
		t.setForeground(latest ? ColorScheme.PROGRESS_COMPLETE_COLOR : FAINT);
		t.setHorizontalAlignment(SwingConstants.RIGHT);

		r.add(n, BorderLayout.CENTER);
		r.add(t, BorderLayout.EAST);
		return r;
	}

	// ---- carrying but locked ----------------------------------------------------------------------------------

	private JPanel buildLockedBag(TcgLockedStatus status)
	{
		JPanel list = vBox();
		if (status.lockedInBag.isEmpty())
		{
			list.add(emptyLine("Nothing locked in your bag."));
			return list;
		}
		int shown = Math.min(status.lockedInBag.size(), MAX_BAG_ROWS);
		for (int i = 0; i < shown; i++)
		{
			list.add(lockLine(status.lockedInBag.get(i), MUTED));
		}
		int extra = status.lockedInBag.size() - shown;
		if (extra > 0)
		{
			list.add(emptyLine("+ " + extra + " more"));
		}
		return list;
	}

	private JPanel buildViolations(TcgLockedStatus status)
	{
		JPanel list = vBox();
		for (String name : status.equippedViolations)
		{
			list.add(lockLine(name, LOCK_RED));
		}
		return list;
	}

	private JPanel lockLine(String name, Color color)
	{
		JPanel r = new JPanel(new BorderLayout(6, 0));
		r.setBackground(SURFACE);
		r.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		JLabel n = new JLabel(name);
		n.setFont(FontManager.getRunescapeSmallFont());
		n.setForeground(color);
		r.add(n, BorderLayout.CENTER);
		return r;
	}

	// ---- footer -----------------------------------------------------------------------------------------------

	private JPanel buildFooter(TcgLockedStatus status)
	{
		JPanel footer = row();
		footer.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
		JLabel updated = new JLabel(status.collectionLoaded
			? "Updated " + relativeTime(status.updatedAtMs, System.currentTimeMillis())
			: "Waiting for OSRS TCG");
		updated.setFont(FontManager.getRunescapeSmallFont());
		updated.setForeground(FAINT);

		JPanel actions = new JPanel(new BorderLayout(8, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.add(linkButton("Discord", MUTED, DISCORD_URL, () -> LinkBrowser.browse(DISCORD_URL)),
			BorderLayout.CENTER);
		actions.add(refreshButton(), BorderLayout.EAST);
		footer.add(updated, BorderLayout.CENTER);
		footer.add(actions, BorderLayout.EAST);
		return footer;
	}

	private JButton refreshButton()
	{
		JButton button = new JButton("Refresh");
		button.setFocusPainted(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(MUTED);
		button.setBackground(SURFACE_RAISED);
		button.setToolTipText("Refresh collection");
		button.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, BORDER),
			BorderFactory.createEmptyBorder(2, 5, 2, 5)));
		button.addMouseListener(new MouseInputAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				button.setForeground(GOLD);
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				button.setBackground(SURFACE_RAISED);
				button.setForeground(MUTED);
			}
		});
		button.addActionListener(e -> refreshAction.run());
		return button;
	}

	// ---- section + layout helpers -----------------------------------------------------------------------------

	private JPanel section(String heading, JPanel content)
	{
		return section(heading, content, GOLD);
	}

	private JPanel section(String heading, JPanel content, Color accent)
	{
		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(SURFACE);
		outer.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, BORDER),
			BorderFactory.createEmptyBorder(9, 9, 9, 9)));

		JPanel inner = vBox();
		JLabel h = new JLabel(heading);
		h.setFont(FontManager.getRunescapeBoldFont());
		h.setForeground(accent.equals(GOLD) ? GOLD : accent);
		h.setBorder(BorderFactory.createEmptyBorder(0, 0, 7, 0));
		inner.add(h);
		inner.add(content);

		outer.add(inner, BorderLayout.CENTER);
		return outer;
	}

	private JPanel vBox()
	{
		JPanel p = new JPanel();
		p.setBackground(SURFACE);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		return p;
	}

	private JPanel row()
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return p;
	}

	private JLabel emptyLine(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(FAINT);
		l.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		return l;
	}

	private JLabel subheading(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static void styleScrollBar(JScrollBar scrollBar)
	{
		scrollBar.setPreferredSize(new Dimension(6, 0));
		scrollBar.setUnitIncrement(16);
		scrollBar.setOpaque(false);
		scrollBar.setUI(new SlimScrollBarUI());
	}

	private static class SlimScrollBarUI extends BasicScrollBarUI
	{
		private static JButton zeroButton()
		{
			JButton button = new JButton();
			Dimension zero = new Dimension(0, 0);
			button.setPreferredSize(zero);
			button.setMinimumSize(zero);
			button.setMaximumSize(zero);
			return button;
		}

		@Override
		protected JButton createDecreaseButton(int orientation)
		{
			return zeroButton();
		}

		@Override
		protected JButton createIncreaseButton(int orientation)
		{
			return zeroButton();
		}

		@Override
		protected void paintTrack(Graphics g, JComponent c, Rectangle bounds)
		{
		}

		@Override
		protected void paintThumb(Graphics g, JComponent c, Rectangle bounds)
		{
			if (bounds.width <= 0 || bounds.height <= 0)
			{
				return;
			}

			Graphics2D graphics = (Graphics2D) g.create();
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(isThumbRollover() ? FAINT : BORDER);
			graphics.fillRoundRect(bounds.x + 1, bounds.y + 1, bounds.width - 2, bounds.height - 2,
				bounds.width, bounds.width);
			graphics.dispose();
		}
	}

	private Component vGap(int h)
	{
		JPanel p = new JPanel();
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setPreferredSize(new Dimension(1, h));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
		return p;
	}

	private static TcgLockedStatus emptyStatus()
	{
		return new TcgLockedStatus(
			false, 0, 0, "", List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0, List.of(), List.of(), 0L);
	}

	private static String relativeTime(long thenMs, long nowMs)
	{
		long delta = Math.max(0L, nowMs - thenMs);
		long secs = delta / 1000L;
		if (secs < 45)
		{
			return "just now";
		}
		long mins = secs / 60L;
		if (mins < 60)
		{
			return mins + "m ago";
		}
		long hours = mins / 60L;
		if (hours < 24)
		{
			return hours + "h ago";
		}
		return (hours / 24L) + "d ago";
	}

	/** A small drawn lock-shield crest; no bundled image. Used for the header and the toolbar nav button. */
	static BufferedImage crestIcon(int size)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		float pad = size * 0.10f;
		float w = size - pad * 2;
		float x = pad;
		float y = pad;

		GeneralPath shield = new GeneralPath();
		shield.moveTo(x, y);
		shield.lineTo(x + w, y);
		shield.lineTo(x + w, y + w * 0.55f);
		shield.quadTo(x + w, y + w * 0.9f, x + w / 2f, y + w);      // right shoulder to point
		shield.quadTo(x, y + w * 0.9f, x, y + w * 0.55f);            // left shoulder to point
		shield.closePath();

		g.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g.fill(shield);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(Math.max(1f, size * 0.06f)));
		g.draw(shield);

		// Keyhole: circle + tapered stem.
		float kc = x + w / 2f;
		float kr = w * 0.14f;
		float ky = y + w * 0.36f;
		g.fill(new Ellipse2D.Float(kc - kr, ky - kr, kr * 2f, kr * 2f));
		GeneralPath stem = new GeneralPath();
		stem.moveTo(kc - kr * 0.5f, ky);
		stem.lineTo(kc + kr * 0.5f, ky);
		stem.lineTo(kc + kr * 0.9f, ky + w * 0.28f);
		stem.lineTo(kc - kr * 0.9f, ky + w * 0.28f);
		stem.closePath();
		g.fill(stem);

		g.dispose();
		return img;
	}
}
