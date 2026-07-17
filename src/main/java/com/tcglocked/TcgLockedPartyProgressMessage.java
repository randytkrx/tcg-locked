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

import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/** A party member's TCG Locked progress and owned card keys, shared for the party panel and pooled unlocks. */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgLockedPartyProgressMessage extends PartyMemberMessage
{
	private int cardsOwned;
	private int unlocked;
	private int seen;
	/** Normalized owned card keys, so party members can unlock items any member owns. */
	private Set<String> ownedKeys;
}
