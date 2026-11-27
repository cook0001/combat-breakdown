package com.combatbreakdown.model;

import lombok.Value;

public final class StatusDamageBreakdown
{
	private long poisonDealt;
	private long venomDealt;
	private long burnDealt;
	private long bleedDealt;
	private long corruptionDealt;
	private long doomDealt;

	private long directDamageTaken;
	private long statusDamageTaken;
	private int incomingHits;
	private int incomingBlocks;
	private long healingReceived;
	private long prayerDrained;

	public void recordOutgoingStatus(HitCategory category, int amount)
	{
		if (amount <= 0)
		{
			return;
		}
		switch (category)
		{
			case STATUS_POISON:
				poisonDealt += amount;
				break;
			case STATUS_VENOM:
				venomDealt += amount;
				break;
			case STATUS_BURN:
				burnDealt += amount;
				break;
			case STATUS_BLEED:
				bleedDealt += amount;
				break;
			case STATUS_CORRUPTION:
				corruptionDealt += amount;
				break;
			case STATUS_DOOM:
				doomDealt += amount;
				break;
			default:
				break;
		}
	}

	public void recordIncoming(HitCategory category, int amount)
	{
		if (category == HitCategory.DIRECT_MISS)
		{
			incomingBlocks++;
			return;
		}
		if (category == HitCategory.HEAL)
		{
			healingReceived += Math.max(0, amount);
			return;
		}
		if (category == HitCategory.PRAYER_DRAIN)
		{
			prayerDrained += Math.max(0, amount);
			return;
		}
		if (amount <= 0)
		{
			return;
		}
		if (category.isStatusDamage())
		{
			statusDamageTaken += amount;
		}
		else if (category == HitCategory.DIRECT_HIT || category == HitCategory.DIRECT_MAX_HIT)
		{
			incomingHits++;
			directDamageTaken += amount;
		}
	}

	public void reset()
	{
		poisonDealt = 0;
		venomDealt = 0;
		burnDealt = 0;
		bleedDealt = 0;
		corruptionDealt = 0;
		doomDealt = 0;
		directDamageTaken = 0;
		statusDamageTaken = 0;
		incomingHits = 0;
		incomingBlocks = 0;
		healingReceived = 0;
		prayerDrained = 0;
	}

	public Snapshot toSnapshot()
	{
		return new Snapshot(
			poisonDealt,
			venomDealt,
			burnDealt,
			bleedDealt,
			corruptionDealt,
			doomDealt,
			directDamageTaken,
			statusDamageTaken,
			incomingHits,
			incomingBlocks,
			healingReceived,
			prayerDrained
		);
	}

	@Value
	public static class Snapshot
	{
		long poisonDealt;
		long venomDealt;
		long burnDealt;
		long bleedDealt;
		long corruptionDealt;
		long doomDealt;
		long directDamageTaken;
		long statusDamageTaken;
		int incomingHits;
		int incomingBlocks;
		long healingReceived;
		long prayerDrained;

		public long getTotalStatusDealt()
		{
			return poisonDealt + venomDealt + burnDealt + bleedDealt + corruptionDealt + doomDealt;
		}

		public long getTotalDamageTaken()
		{
			return directDamageTaken + statusDamageTaken;
		}

		public double getEvadePercent()
		{
			int total = incomingHits + incomingBlocks;
			return total > 0 ? (incomingBlocks * 100.0) / total : 0.0;
		}
	}
}
