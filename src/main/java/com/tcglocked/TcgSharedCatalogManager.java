/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

@Singleton
final class TcgSharedCatalogManager
{
	private final TcgSharedCardCatalog catalog;
	private final TcgSharedCardImageCache images;
	private TcgSharedCatalogWindow window;
	private boolean started;
	private int generation;

	@Inject
	TcgSharedCatalogManager(TcgSharedCardCatalog catalog, TcgSharedCardImageCache images)
	{
		this.catalog = catalog;
		this.images = images;
	}

	synchronized void start()
	{
		started = true;
		generation++;
		images.start();
	}

	synchronized void show(TcgSharedCatalogSnapshot snapshot)
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
			if (window == null || !window.isDisplayable())
			{
				window = new TcgSharedCatalogWindow(catalog, images);
			}
			window.refresh(snapshot);
			window.setVisible(true);
			window.setExtendedState(window.getExtendedState() & ~java.awt.Frame.ICONIFIED);
			window.toFront();
			window.requestFocus();
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
		SwingUtilities.invokeLater(() ->
		{
			if (window != null)
			{
				window.dispose();
				window = null;
			}
		});
		images.dispose();
	}
}
