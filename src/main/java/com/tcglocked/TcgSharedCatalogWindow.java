/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

final class TcgSharedCatalogWindow extends JFrame
{
	private static final Color SURFACE = new Color(0x25, 0x25, 0x25);
	private static final Color RAISED = new Color(0x30, 0x30, 0x30);
	private static final Color BORDER = new Color(0x43, 0x43, 0x43);
	private static final Color MUTED = new Color(0x9A, 0x9A, 0x9A);
	private static final Color BLUE = new Color(0x5B, 0x9B, 0xD5);
	private static final int PAGE_SIZE = 21;

	private final TcgSharedCardCatalog catalog;
	private final TcgSharedCardImageCache images;
	private final JTextField search = new JTextField();
	private final JComboBox<String> owner = new JComboBox<>();
	private final JComboBox<String> category = new JComboBox<>(new String[]{"All", "Items", "NPCs"});
	private final JCheckBox sharedOnly = new JCheckBox("Shared only", true);
	private final JPanel grid = new JPanel(new GridLayout(3, 7, 6, 6));
	private final JLabel summary = new JLabel();
	private final JButton previous = new JButton("Previous");
	private final JButton next = new JButton("Next");
	private final JLabel pageLabel = new JLabel();
	private TcgSharedCatalogSnapshot snapshot = new TcgSharedCatalogSnapshot(Collections.emptyMap());
	private int page;

	TcgSharedCatalogWindow(TcgSharedCardCatalog catalog, TcgSharedCardImageCache images)
	{
		super("TCG Locked - Shared catalog");
		this.catalog = catalog;
		this.images = images;
		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		setSize(1100, 720);
		setMinimumSize(new Dimension(980, 640));
		setLocationByPlatform(true);

		JPanel root = new JPanel(new BorderLayout(0, 10));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));
		root.add(buildControls(), BorderLayout.NORTH);
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.add(grid, BorderLayout.CENTER);
		root.add(buildPaging(), BorderLayout.SOUTH);
		setContentPane(root);
		wireControls();
	}

	void refresh(TcgSharedCatalogSnapshot snapshot)
	{
		String selected = owner.getSelectedIndex() <= 0 ? null : (String) owner.getSelectedItem();
		this.snapshot = snapshot;
		owner.removeAllItems();
		owner.addItem("Everyone");
		for (String name : snapshot.owners())
		{
			owner.addItem(name);
		}
		if (selected != null && snapshot.owners().contains(selected))
		{
			owner.setSelectedItem(selected);
		}
		else
		{
			owner.setSelectedIndex(0);
		}
		page = 0;
		render();
	}

	private JPanel buildControls()
	{
		JPanel controls = new JPanel(new BorderLayout(10, 8));
		controls.setBackground(SURFACE);
		controls.setBorder(new CompoundBorder(new MatteBorder(1, 1, 1, 1, BORDER),
			BorderFactory.createEmptyBorder(9, 10, 9, 10)));
		search.setToolTipText("Search card names");
		search.setPreferredSize(new Dimension(300, 28));
		controls.add(search, BorderLayout.CENTER);

		JPanel filters = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
		filters.setBackground(SURFACE);
		owner.setPreferredSize(new Dimension(145, 28));
		category.setPreferredSize(new Dimension(90, 28));
		sharedOnly.setBackground(SURFACE);
		sharedOnly.setForeground(ColorScheme.TEXT_COLOR);
		filters.add(owner);
		filters.add(category);
		filters.add(sharedOnly);
		controls.add(filters, BorderLayout.EAST);
		return controls;
	}

	private JPanel buildPaging()
	{
		JPanel paging = new JPanel(new BorderLayout());
		paging.setBackground(ColorScheme.DARK_GRAY_COLOR);
		summary.setForeground(MUTED);
		summary.setFont(FontManager.getRunescapeSmallFont());
		paging.add(summary, BorderLayout.WEST);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		pageLabel.setForeground(ColorScheme.TEXT_COLOR);
		pageLabel.setHorizontalAlignment(SwingConstants.CENTER);
		buttons.add(previous);
		buttons.add(pageLabel);
		buttons.add(next);
		paging.add(buttons, BorderLayout.EAST);
		return paging;
	}

	private void wireControls()
	{
		DocumentListener listener = new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				resetAndRender();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				resetAndRender();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				resetAndRender();
			}
		};
		search.getDocument().addDocumentListener(listener);
		owner.addActionListener(event -> resetAndRender());
		category.addActionListener(event -> resetAndRender());
		sharedOnly.addActionListener(event -> resetAndRender());
		previous.addActionListener(event ->
		{
			page = Math.max(0, page - 1);
			render();
		});
		next.addActionListener(event ->
		{
			page++;
			render();
		});
	}

	private void resetAndRender()
	{
		page = 0;
		render();
	}

	private void render()
	{
		if (owner.getItemCount() == 0)
		{
			return;
		}
		String selectedOwner = owner.getSelectedIndex() <= 0 ? null : (String) owner.getSelectedItem();
		TcgSharedCardCatalog.Category selectedCategory = category.getSelectedIndex() == 1
			? TcgSharedCardCatalog.Category.ITEM : category.getSelectedIndex() == 2
			? TcgSharedCardCatalog.Category.NPC : null;
		List<TcgSharedCardCatalog.Card> cards = TcgSharedCardCatalog.filter(
			catalog.cards(), snapshot, selectedOwner, selectedCategory, sharedOnly.isSelected(), search.getText());
		int pages = Math.max(1, (cards.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		page = Math.min(page, pages - 1);
		int from = page * PAGE_SIZE;
		int to = Math.min(cards.size(), from + PAGE_SIZE);
		Set<String> visibleUrls = new HashSet<>();
		for (int i = from; i < to; i++)
		{
			visibleUrls.add(cards.get(i).imageUrl);
		}
		images.retainVisible(visibleUrls);

		grid.removeAll();
		for (int i = from; i < to; i++)
		{
			grid.add(cardCell(cards.get(i), selectedOwner));
		}
		for (int i = to; i < from + PAGE_SIZE; i++)
		{
			JPanel empty = new JPanel();
			empty.setOpaque(false);
			grid.add(empty);
		}
		summary.setText(cards.size() + (cards.size() == 1 ? " card" : " cards")
			+ "  |  " + snapshot.combinedKeys().size() + " shared by group");
		pageLabel.setText("Page " + (page + 1) + " of " + pages);
		previous.setEnabled(page > 0);
		next.setEnabled(page + 1 < pages);
		grid.revalidate();
		grid.repaint();
	}

	private JPanel cardCell(TcgSharedCardCatalog.Card card, String selectedOwner)
	{
		boolean shared = selectedOwner == null ? snapshot.isShared(card.key)
			: snapshot.keysFor(selectedOwner).contains(card.key);
		JPanel cell = new JPanel(new BorderLayout(0, 4));
		cell.setBackground(shared ? RAISED : SURFACE);
		cell.setBorder(new CompoundBorder(new MatteBorder(1, 1, 1, 1, shared ? BLUE : BORDER),
			BorderFactory.createEmptyBorder(5, 5, 5, 5)));

		FadedLabel image = new FadedLabel(shared ? 1f : .35f);
		image.setText("Loading image...");
		image.setForeground(MUTED);
		image.setHorizontalAlignment(SwingConstants.CENTER);
		images.get(card.imageUrl, icon ->
		{
			if (icon == null)
			{
				image.setText("Image unavailable");
				return;
			}
			image.setText("");
			image.setCardImage(icon.getImage());
		});
		cell.add(image, BorderLayout.CENTER);

		JPanel details = new JPanel();
		details.setOpaque(false);
		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		JLabel name = new JLabel(card.name);
		name.setForeground(shared ? ColorScheme.TEXT_COLOR : MUTED.darker());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setAlignmentX(LEFT_ALIGNMENT);
		details.add(name);
		JPanel line = new JPanel(new BorderLayout());
		line.setOpaque(false);
		JLabel kind = new JLabel(card.category.label);
		kind.setForeground(MUTED);
		kind.setFont(FontManager.getRunescapeSmallFont());
		line.add(kind, BorderLayout.WEST);
		if (shared)
		{
			JLabel marker = new JLabel("SHARED");
			marker.setForeground(BLUE);
			marker.setFont(FontManager.getRunescapeSmallFont());
			line.add(marker, BorderLayout.EAST);
		}
		details.add(Box.createVerticalStrut(2));
		details.add(line);
		cell.add(details, BorderLayout.SOUTH);

		String ownership = selectedOwner != null
			? shared ? "Shared by " + selectedOwner : "Not shared by " + selectedOwner
			: snapshot.ownersOf(card.key).isEmpty() ? "Not shared"
			: "Shared by " + String.join(", ", snapshot.ownersOf(card.key));
		cell.setToolTipText(card.name + " - " + ownership);
		image.setToolTipText(cell.getToolTipText());
		name.setToolTipText(cell.getToolTipText());
		kind.setToolTipText(cell.getToolTipText());
		return cell;
	}

	@Override
	public void setVisible(boolean visible)
	{
		if (!visible)
		{
			images.retainVisible(Collections.emptySet());
		}
		super.setVisible(visible);
	}

	private static final class FadedLabel extends JLabel
	{
		private final float opacity;
		private java.awt.Image cardImage;

		private FadedLabel(float opacity)
		{
			this.opacity = opacity;
		}

		private void setCardImage(java.awt.Image cardImage)
		{
			this.cardImage = cardImage;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
			super.paintComponent(g);
			if (cardImage != null)
			{
				int imageWidth = cardImage.getWidth(this);
				int imageHeight = cardImage.getHeight(this);
				double scale = Math.min((double) getWidth() / imageWidth, (double) getHeight() / imageHeight);
				int width = Math.max(1, (int) Math.round(imageWidth * scale));
				int height = Math.max(1, (int) Math.round(imageHeight * scale));
				g.drawImage(cardImage, (getWidth() - width) / 2, (getHeight() - height) / 2,
					width, height, this);
			}
			g.setComposite(old);
			g.dispose();
		}
	}
}
