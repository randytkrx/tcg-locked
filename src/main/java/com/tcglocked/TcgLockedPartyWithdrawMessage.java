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

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * "Stop using my cards." Sent when you unsync someone, naming them, so their client drops the
 * collection you had shared instead of keeping it forever.
 *
 * <p>Unsyncing is otherwise one-directional: it stops their cards counting for you and stops you
 * sending updates, but anything already shared stays unlocked on their side across restarts, because
 * an approved partner's collection is saved to disk.</p>
 *
 * <p>Named rather than targeted because party messages reach everyone — recipients ignore any
 * withdrawal not addressed to them. Like the rest of the plugin this is cooperative: it asks the
 * other client to comply, and a player who wants to cheat can simply turn the plugin off.</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgLockedPartyWithdrawMessage extends PartyMemberMessage
{
	/** RuneScape name of the player whose access is being withdrawn. */
	private String target;
}
