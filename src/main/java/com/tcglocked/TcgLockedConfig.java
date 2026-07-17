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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(TcgLockedConfig.GROUP)
public interface TcgLockedConfig extends Config
{
	String GROUP = "tcglocked";

	enum Enforcement
	{
		/** Remove the equip/use menu option for locked items (soft client-side block). */
		BLOCK,
		/** Leave the menu alone; only warn and track when a locked item is used/worn. */
		WARN_ONLY
	}

	@ConfigItem(
		keyName = "enforcement",
		name = "Enforcement",
		description = "Block: remove the equip/use option for cards you don't own. Warn only: allow it but flag violations.",
		position = 0
	)
	default Enforcement enforcement()
	{
		return Enforcement.BLOCK;
	}

	@ConfigItem(
		keyName = "gateEquipment",
		name = "Lock equipment",
		description = "Require owning a card before you can Wield / Wear / Equip an item.",
		position = 1
	)
	default boolean gateEquipment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gateTeleports",
		name = "Lock teleport items",
		description = "Require owning a card before you can Rub / Teleport / Break / Operate a teleport item "
			+ "(amulets, jewelry, tabs). Spellbook teleports are not items, so they are never affected.",
		position = 2
	)
	default boolean gateTeleports()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gateConsumables",
		name = "Lock consumables",
		description = "Also require owning a card before you can Eat / Drink an item. Off by default to avoid softlocks.",
		position = 3
	)
	default boolean gateConsumables()
	{
		return false;
	}

	@ConfigItem(
		keyName = "warnInChat",
		name = "Warn in chat",
		description = "Post a game message when a locked item ends up equipped.",
		position = 4
	)
	default boolean warnInChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show violation overlay",
		description = "Show an overlay listing locked items you currently have equipped.",
		position = 5
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLockIcons",
		name = "Lock icons on items",
		description = "Draw a padlock and dim items in the inventory, bank and equipment that you don't own a card for.",
		position = 6
	)
	default boolean showLockIcons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "announceUnlocks",
		name = "Announce unlocks",
		description = "Post a game message when a newly-pulled card unlocks an item.",
		position = 7
	)
	default boolean announceUnlocks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "unlockStarterGear",
		name = "Start with bronze",
		description = "Always allow bronze-tier items (name starting with \"Bronze \") so a fresh account isn't defenceless.",
		position = 8
	)
	default boolean unlockStarterGear()
	{
		return false;
	}

	@ConfigItem(
		keyName = "extraAllowList",
		name = "Always-allow items",
		description = "Comma-separated item names to always allow regardless of cards (e.g. untradeable quest gear with no card). Variants of a listed item are allowed too.",
		position = 9
	)
	default String extraAllowList()
	{
		return "";
	}
}
