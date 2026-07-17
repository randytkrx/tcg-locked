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
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.MouseInputAdapter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

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
	private static final int MAX_RECENT_ROWS = 8;
	private static final int MAX_BAG_ROWS = 10;

	private final JPanel body = new JPanel();
	private Runnable refreshAction = TcgLockedPanel::noop;

	@Inject
	TcgLockedPanel()
	{
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		add(body, BorderLayout.NORTH);

		render(emptyStatus());
	}

	void setRefreshAction(Runnable action)
	{
		this.refreshAction = action == null ? TcgLockedPanel::noop : action;
	}

	private static void noop()
	{
	}

	/** Rebuilds the panel from a snapshot. Call on the EDT. */
	void update(TcgLockedStatus status)
	{
		render(status);
		revalidate();
		repaint();
	}

	private void render(TcgLockedStatus status)
	{
		body.removeAll();
		body.add(buildHeader(status));
		body.add(vGap(10));
		body.add(buildStatTiles(status));
		body.add(vGap(12));
		body.add(section("Recently unlocked", buildRecent(status)));
		body.add(vGap(12));
		body.add(section("Carrying but locked", buildLockedBag(status)));
		if (!status.equippedViolations.isEmpty())
		{
			body.add(vGap(12));
			body.add(section("Equipped without a card", buildViolations(status), LOCK_RED));
		}
		body.add(vGap(12));
		body.add(buildFooter(status));
	}

	// ---- header -----------------------------------------------------------------------------------------------

	private JPanel buildHeader(TcgLockedStatus status)
	{
		JPanel header = row();
		JLabel crest = new JLabel(new ImageIcon(crestIcon(26)));
		crest.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
		header.add(crest, BorderLayout.WEST);

		JPanel titles = new JPanel();
		titles.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("TCG Locked");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));
		title.setForeground(GOLD);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel sub = new JLabel(status.collectionLoaded
			? "Collection linked · " + status.enforcementLabel
			: "TCG plugin not detected");
		sub.setFont(FontManager.getRunescapeSmallFont());
		sub.setForeground(status.collectionLoaded ? MUTED : LOCK_RED);
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);

		titles.add(title);
		titles.add(sub);
		header.add(titles, BorderLayout.CENTER);
		return header;
	}

	// ---- stat tiles -------------------------------------------------------------------------------------------

	private JPanel buildStatTiles(TcgLockedStatus status)
	{
		JPanel tiles = new JPanel(new GridLayout(1, 2, 6, 0));
		tiles.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tiles.add(statTile(Integer.toString(status.cardsOwned), "cards owned", GOLD));
		String sessionText = status.sessionUnlocks > 0 ? "+" + status.sessionUnlocks : "0";
		tiles.add(statTile(sessionText, "this session",
			status.sessionUnlocks > 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : INK));
		return tiles;
	}

	private JPanel statTile(String value, String label, Color valueColor)
	{
		JPanel tile = new JPanel(new BorderLayout());
		tile.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tile.setBorder(BorderFactory.createEmptyBorder(7, 9, 6, 9));

		JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		v.setForeground(valueColor);

		JLabel l = new JLabel(label.toUpperCase());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(FAINT);

		tile.add(v, BorderLayout.CENTER);
		tile.add(l, BorderLayout.SOUTH);
		return tile;
	}

	// ---- recent unlocks ---------------------------------------------------------------------------------------

	private JPanel buildRecent(TcgLockedStatus status)
	{
		JPanel list = vBox();
		if (status.recentUnlocks.isEmpty())
		{
			list.add(emptyLine(status.collectionLoaded
				? "Open a pack to start unlocking."
				: "Enable the OSRS TCG plugin to begin."));
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
		r.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
		r.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
		JPanel footer = new JPanel();
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
		footer.setBorder(new MatteBorder(1, 0, 0, 0, ColorScheme.BORDER_COLOR));

		JPanel spacer = new JPanel();
		spacer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spacer.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		footer.add(spacer);

		JLabel updated = new JLabel(status.collectionLoaded
			? "Updated " + relativeTime(status.updatedAtMs, status.updatedAtMs)
			: "Not linked");
		updated.setFont(FontManager.getRunescapeSmallFont());
		updated.setForeground(FAINT);
		updated.setAlignmentX(Component.LEFT_ALIGNMENT);
		footer.add(updated);
		footer.add(vGap(6));
		footer.add(refreshButton());
		return footer;
	}

	private JButton refreshButton()
	{
		JButton button = new JButton("Refresh");
		button.setFocusPainted(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(INK);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.BORDER_COLOR),
			BorderFactory.createEmptyBorder(4, 0, 4, 0)));
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
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
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(INK);
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
		outer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Gold left margin -> the "ledger rule".
		outer.setBorder(new CompoundBorder(
			new MatteBorder(0, 2, 0, 0, accent.darker()),
			BorderFactory.createEmptyBorder(0, 8, 0, 0)));

		JPanel inner = vBox();
		JLabel h = new JLabel(heading.toUpperCase());
		h.setFont(FontManager.getRunescapeSmallFont());
		h.setForeground(accent.equals(GOLD) ? GOLD : accent);
		h.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		inner.add(h);
		inner.add(content);

		outer.add(inner, BorderLayout.CENTER);
		return outer;
	}

	private JPanel vBox()
	{
		JPanel p = new JPanel();
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
		return new TcgLockedStatus(false, 0, 0, "", List.of(), List.of(), List.of(), 0L);
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
