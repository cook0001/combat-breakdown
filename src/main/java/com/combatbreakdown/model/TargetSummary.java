package com.combatbreakdown.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TargetSummary
{
	private String npcName;
	private int combatLevel;
	private int kills;
	private long totalDurationTicks;
	private int fastestKillTicks;
	private int slowestKillTicks;
	private long totalDirectDamage;
	private long totalStatusDamage;
	private int totalHitsLanded;
	private int totalMisses;
	private int maxHit;
	private long totalDamageTaken;
	private long totalHealing;
	private long totalSupplyCostGp;
	private long totalLootValueGp;
	private long lastKilledEpochMs;

	public void mergeFight(FightRecord fight)
	{
		if (fight == null)
		{
			return;
		}
		if (combatLevel <= 0 && fight.getCombatLevel() > 0)
		{
			combatLevel = fight.getCombatLevel();
		}
		kills++;
		int dur = Math.max(1, fight.getDurationTicks());
		totalDurationTicks += dur;
		if (fastestKillTicks <= 0 || dur < fastestKillTicks)
		{
			fastestKillTicks = dur;
		}
		if (dur > slowestKillTicks)
		{
			slowestKillTicks = dur;
		}
		totalDirectDamage += fight.getDirectDamage();
		totalStatusDamage += fight.getStatusDamage();
		totalHitsLanded += fight.getHitsLanded();
		totalMisses += fight.getMisses();
		if (fight.getMaxHit() > maxHit)
		{
			maxHit = fight.getMaxHit();
		}
		totalDamageTaken += fight.getDamageTaken();
		totalHealing += fight.getHealingReceived();
		totalSupplyCostGp += fight.getSupplyCostGp();
		totalLootValueGp += fight.getLootValueGp();
		lastKilledEpochMs = Math.max(lastKilledEpochMs, fight.getCompletedAtEpochMs());
	}

	public void addLootValueGp(long lootGp)
	{
		if (lootGp > 0)
		{
			totalLootValueGp += lootGp;
		}
	}

	public long getTotalDamage()
	{
		return totalDirectDamage + totalStatusDamage;
	}

	public double getAverageKillSeconds()
	{
		return kills > 0 ? (totalDurationTicks * WeaponStats.SECONDS_PER_TICK) / kills : 0.0;
	}

	public double getFastestKillSeconds()
	{
		return fastestKillTicks * WeaponStats.SECONDS_PER_TICK;
	}

	public double getAverageDps()
	{
		double seconds = Math.max(WeaponStats.SECONDS_PER_TICK, totalDurationTicks * WeaponStats.SECONDS_PER_TICK);
		return getTotalDamage() > 0 ? getTotalDamage() / seconds : 0.0;
	}

	public double getAccuracyPercent()
	{
		int total = totalHitsLanded + totalMisses;
		return total > 0 ? (totalHitsLanded * 100.0) / total : 0.0;
	}

	public long getSupplyCostPerKillGp()
	{
		return kills > 0 ? totalSupplyCostGp / kills : 0L;
	}

	public long getLootPerKillGp()
	{
		return kills > 0 ? totalLootValueGp / kills : 0L;
	}

	public long getNetProfitPerKillGp()
	{
		return kills > 0 ? (totalLootValueGp - totalSupplyCostGp) / kills : 0L;
	}
}
