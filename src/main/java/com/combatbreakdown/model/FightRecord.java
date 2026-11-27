package com.combatbreakdown.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FightRecord
{
	private String npcName;
	private int combatLevel;
	private int durationTicks;
	private long directDamage;
	private long statusDamage;
	private int hitsLanded;
	private int misses;
	private int maxHit;
	private long damageTaken;
	private long healingReceived;
	private long supplyCostGp;
	private long lootValueGp;
	private int killingHitDamage;
	private String primaryWeaponName;
	private long completedAtEpochMs;

	public long getNetProfitGp()
	{
		return lootValueGp - supplyCostGp;
	}

	public long getTotalDamage()
	{
		return directDamage + statusDamage;
	}

	public double getDurationSeconds()
	{
		return Math.max(WeaponStats.SECONDS_PER_TICK, durationTicks * WeaponStats.SECONDS_PER_TICK);
	}

	public double getDps()
	{
		return getTotalDamage() / getDurationSeconds();
	}

	public double getAccuracyPercent()
	{
		int total = hitsLanded + misses;
		return total > 0 ? (hitsLanded * 100.0) / total : 0.0;
	}
}
