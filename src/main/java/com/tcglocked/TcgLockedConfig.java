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
import net.runelite.client.config.ConfigSection;

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

	enum Preset
	{
		GEAR_ONLY("Gear only"),
		GEAR_AND_TELEPORTS("Gear and teleports"),
		EVERYTHING("Everything"),
		CUSTOM("Custom");

		private final String label;

		Preset(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigSection(
		name = "Locking",
		description = "What is locked and how",
		position = 0
	)
	String LOCKING = "locking";

	@ConfigSection(
		name = "Feedback",
		description = "What the plugin shows and plays",
		position = 1
	)
	String FEEDBACK = "feedback";

	@ConfigSection(
		name = "Advanced",
		description = "Starter allowances and overrides",
		position = 2,
		closedByDefault = true
	)
	String ADVANCED = "advanced";

	@ConfigItem(
		keyName = "preset",
		name = "Difficulty",
		description = "A one click difficulty. Choose Custom to control each lock below yourself.",
		section = LOCKING,
		position = 0
	)
	default Preset preset()
	{
		return Preset.GEAR_AND_TELEPORTS;
	}

	@ConfigItem(
		keyName = "enforcement",
		name = "Enforcement",
		description = "Block: remove the equip/use option for cards you don't own. Warn only: allow it but flag violations.",
		section = LOCKING,
		position = 1
	)
	default Enforcement enforcement()
	{
		return Enforcement.BLOCK;
	}

	@ConfigItem(
		keyName = "gateEquipment",
		name = "Lock equipment",
		description = "Require owning a card before you can Wield / Wear / Equip an item. Used when Difficulty is Custom.",
		section = LOCKING,
		position = 2
	)
	default boolean gateEquipment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gateTeleports",
		name = "Lock teleport items",
		description = "Require owning a card before you can Rub / Teleport / Break / Operate a teleport item "
			+ "(amulets, jewellery, tabs). Spellbook teleports are not items, so they are never affected. "
			+ "Used when Difficulty is Custom.",
		section = LOCKING,
		position = 3
	)
	default boolean gateTeleports()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gateConsumables",
		name = "Lock consumables",
		description = "Also require owning a card before you can Eat / Drink an item. Used when Difficulty is Custom.",
		section = LOCKING,
		position = 4
	)
	default boolean gateConsumables()
	{
		return false;
	}

	@ConfigItem(
		keyName = "gateNpcs",
		name = "Lock monsters",
		description = "Require owning a monster's card before you can interact with it (attack, talk, pickpocket, etc.). "
			+ "Monsters with no card in the TCG catalog are always free, and Examine is always allowed. "
			+ "Used when Difficulty is Custom.",
		section = LOCKING,
		position = 5
	)
	default boolean gateNpcs()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showUnlockReveal",
		name = "Unlock reveal",
		description = "Show a reveal card with the item art when a newly pulled card unlocks an item.",
		section = FEEDBACK,
		position = 0
	)
	default boolean showUnlockReveal()
	{
		return true;
	}

	@ConfigItem(
		keyName = "unlockSound",
		name = "Unlock sound",
		description = "Play a chime when a card unlocks an item.",
		section = FEEDBACK,
		position = 1
	)
	default boolean unlockSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "announceUnlocks",
		name = "Announce unlocks in chat",
		description = "Post a game message when a newly pulled card unlocks an item.",
		section = FEEDBACK,
		position = 2
	)
	default boolean announceUnlocks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLockIcons",
		name = "Lock icons on items",
		description = "Draw a padlock and dim items in the inventory, bank and equipment that you don't own a card for.",
		section = FEEDBACK,
		position = 3
	)
	default boolean showLockIcons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show violation overlay",
		description = "Show an overlay listing locked items you currently have equipped.",
		section = FEEDBACK,
		position = 4
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "warnInChat",
		name = "Warn in chat",
		description = "Post a game message when a locked item ends up equipped.",
		section = FEEDBACK,
		position = 5
	)
	default boolean warnInChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "partyShare",
		name = "Share with party",
		description = "When in a RuneLite party, share your unlock progress and announce your unlocks, and show a "
			+ "party progress list in the panel.",
		section = FEEDBACK,
		position = 6
	)
	default boolean partyShare()
	{
		return true;
	}

	@ConfigItem(
		keyName = "unlockStarterGear",
		name = "Start with bronze",
		description = "Always allow bronze-tier items (name starting with \"Bronze \") so you are never fully locked out. "
			+ "Turn this off for a pure run where even bronze needs its card.",
		section = ADVANCED,
		position = 0
	)
	default boolean unlockStarterGear()
	{
		return true;
	}

	@ConfigItem(
		keyName = "extraAllowList",
		name = "Always-allow items",
		description = "Comma-separated item names to always allow regardless of cards (e.g. untradeable quest gear with no card). Variants of a listed item are allowed too.",
		section = ADVANCED,
		position = 1
	)
	default String extraAllowList()
	{
		return "";
	}
}
