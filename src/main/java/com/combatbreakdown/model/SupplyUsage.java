package com.combatbreakdown.model;

import lombok.Value;

public final class SupplyUsage
{
	private final String key;
	private final String displayName;
	private final int iconItemId;
	private final SupplyCategory category;
	private long unitsConsumed;
	private long totalCostGp;

	public SupplyUsage(String key, String displayName, int iconItemId, SupplyCategory category)
	{
		this.key = key;
		this.displayName = displayName;
		this.iconItemId = iconItemId;
		this.category = category;
	}

	public void add(long units, long gpCost)
	{
		if (units > 0)
		{
			unitsConsumed += units;
		}
		if (gpCost > 0)
		{
			totalCostGp += gpCost;
		}
	}

	public Snapshot toSnapshot()
	{
		return new Snapshot(key, displayName, iconItemId, category, unitsConsumed, totalCostGp);
	}

	@Value
	public static class Snapshot
	{
		String key;
		String displayName;
		int iconItemId;
		SupplyCategory category;
		long unitsConsumed;
		long totalCostGp;

		public double getUnitsPerHour(double sessionSeconds)
		{
			if (unitsConsumed <= 0 || sessionSeconds <= 0)
			{
				return 0.0;
			}
			double hours = Math.max(WeaponStats.SECONDS_PER_TICK, sessionSeconds) / 3600.0;
			return unitsConsumed / hours;
		}

		public double getUnitsPerKill(int kills)
		{
			return kills > 0 ? ((double) unitsConsumed) / kills : 0.0;
		}

		public long getGpPerHour(double sessionSeconds)
		{
			if (totalCostGp <= 0 || sessionSeconds <= 0)
			{
				return 0L;
			}
			double hours = Math.max(WeaponStats.SECONDS_PER_TICK, sessionSeconds) / 3600.0;
			return Math.round(totalCostGp / hours);
		}
	}
}
