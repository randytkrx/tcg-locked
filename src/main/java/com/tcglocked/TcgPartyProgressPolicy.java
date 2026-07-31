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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 */
package com.tcglocked;

import java.util.Map;
import java.util.Set;

/** Applies complete party snapshots; only explicit withdrawal messages remove existing cards. */
final class TcgPartyProgressPolicy
{
	private TcgPartyProgressPolicy()
	{
	}

	static Result apply(Map<String, Set<String>> offeredKeys, Map<String, Set<String>> pooledKeys,
		String partnerKey, boolean addressed, Set<String> sent, boolean approved)
	{
		if (partnerKey == null || partnerKey.isEmpty() || !addressed || sent == null)
		{
			return Result.ignored();
		}

		offeredKeys.put(partnerKey, sent);
		if (!approved)
		{
			return Result.offered();
		}

		Set<String> previous = pooledKeys.put(partnerKey, sent);
		return Result.pooled(previous);
	}

	static final class Result
	{
		private final boolean accepted;
		private final boolean pooled;
		private final Set<String> previous;

		private Result(boolean accepted, boolean pooled, Set<String> previous)
		{
			this.accepted = accepted;
			this.pooled = pooled;
			this.previous = previous;
		}

		private static Result ignored()
		{
			return new Result(false, false, null);
		}

		private static Result offered()
		{
			return new Result(true, false, null);
		}

		private static Result pooled(Set<String> previous)
		{
			return new Result(true, true, previous);
		}

		boolean isAccepted()
		{
			return accepted;
		}

		boolean isPooled()
		{
			return pooled;
		}

		Set<String> getPrevious()
		{
			return previous;
		}
	}
}
