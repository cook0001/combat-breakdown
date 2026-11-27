package com.combatbreakdown.model;

import lombok.Value;

public final class WeaponStats
{
	public static final double SECONDS_PER_TICK = 0.6;

	private final int weaponItemId;
	private final String weaponName;
	private int hitsLanded;
	private int misses;
	private long totalDamage;
	private int maxHit;
	private int nativeMaxSplats;
	private int activeTicks;

	public WeaponStats(int weaponItemId, String weaponName)
	{
		this.weaponItemId = weaponItemId;
		this.weaponName = weaponName != null && !weaponName.isEmpty() ? weaponName : "Unarmed";
	}

	public void recordHit(int damage, boolean nativeMaxSplat)
	{
		if (damage <= 0)
		{
			misses++;
			return;
		}
		hitsLanded++;
		totalDamage += damage;
		if (damage > maxHit)
		{
			maxHit = damage;
		}
		if (nativeMaxSplat)
		{
			nativeMaxSplats++;
		}
	}

	public void addActiveTick()
	{
		activeTicks++;
	}

	public Snapshot toSnapshot()
	{
		return toSnapshot(0);
	}

	public Snapshot toSnapshot(int pendingActiveTicks)
	{
		return new Snapshot(
			weaponItemId,
			weaponName,
			hitsLanded,
			misses,
			totalDamage,
			maxHit,
			nativeMaxSplats,
			activeTicks + Math.max(0, pendingActiveTicks)
		);
	}

	@Value
	public static class Snapshot
	{
		int weaponItemId;
		String weaponName;
		int hitsLanded;
		int misses;
		long totalDamage;
		int maxHit;
		int nativeMaxSplats;
		int activeTicks;

		public int getTotalSwings()
		{
			return hitsLanded + misses;
		}

		public double getAccuracyPercent()
		{
			int total = getTotalSwings();
			return total > 0 ? (hitsLanded * 100.0) / total : 0.0;
		}

		public double getAverageLandedHit()
		{
			return hitsLanded > 0 ? ((double) totalDamage) / hitsLanded : 0.0;
		}

		public double getAveragePerSwing()
		{
			int total = getTotalSwings();
			return total > 0 ? ((double) totalDamage) / total : 0.0;
		}

		public double getActiveDps()
		{
			double seconds = Math.max(SECONDS_PER_TICK, activeTicks * SECONDS_PER_TICK);
			return totalDamage > 0 ? totalDamage / seconds : 0.0;
		}
	}
}
