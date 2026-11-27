package com.combatbreakdown.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;

public final class HitDistribution
{
	@Value
	public static class Bucket
	{
		String label;
		String rangeLabel;
		int count;
		double percent;
	}

	private final Map<Integer, Integer> countsByDamage = new HashMap<>();
	private int totalSwings;
	private int maxHit;
	private int nativeMaxSplats;

	public void record(int damage, boolean nativeMaxSplat)
	{
		int clean = Math.max(0, damage);
		countsByDamage.merge(clean, 1, Integer::sum);
		totalSwings++;
		if (clean > maxHit)
		{
			maxHit = clean;
		}
		if (nativeMaxSplat)
		{
			nativeMaxSplats++;
		}
	}

	public void reset()
	{
		countsByDamage.clear();
		totalSwings = 0;
		maxHit = 0;
		nativeMaxSplats = 0;
	}

	public int getTotalSwings()
	{
		return totalSwings;
	}

	public int getMaxHit()
	{
		return maxHit;
	}

	public int getNativeMaxSplats()
	{
		return nativeMaxSplats;
	}

	public List<Bucket> toBuckets()
	{
		if (totalSwings <= 0)
		{
			return Collections.emptyList();
		}

		int misses = countsByDamage.getOrDefault(0, 0);
		if (maxHit <= 0)
		{
			return Collections.singletonList(new Bucket("Miss", "0", misses, 100.0));
		}

		int lowTop = Math.max(1, maxHit / 3);
		int midTop = Math.max(lowTop, (maxHit * 2) / 3);
		int highTop = Math.max(midTop, maxHit - 1);

		int low = 0;
		int mid = 0;
		int high = 0;
		int maxHits = 0;

		for (Map.Entry<Integer, Integer> entry : countsByDamage.entrySet())
		{
			int dmg = entry.getKey();
			int count = entry.getValue();
			if (dmg <= 0)
			{
				continue;
			}
			if (dmg >= maxHit)
			{
				maxHits += count;
			}
			else if (dmg <= lowTop)
			{
				low += count;
			}
			else if (dmg <= midTop)
			{
				mid += count;
			}
			else
			{
				high += count;
			}
		}

		double denom = totalSwings;
		if (maxHit <= 3)
		{
			int nonMax = totalSwings - misses - maxHits;
			return Arrays.asList(
				new Bucket("Miss", "0", misses, (misses * 100.0) / denom),
				new Bucket("Hit", "1-" + Math.max(1, maxHit - 1), nonMax, (nonMax * 100.0) / denom),
				new Bucket("Max", Integer.toString(maxHit), maxHits, (maxHits * 100.0) / denom)
			);
		}

		return Arrays.asList(
			new Bucket("Miss", "0", misses, (misses * 100.0) / denom),
			new Bucket("Low", "1-" + lowTop, low, (low * 100.0) / denom),
			new Bucket("Mid", (lowTop + 1) + "-" + midTop, mid, (mid * 100.0) / denom),
			new Bucket("High", (midTop + 1) + "-" + highTop, high, (high * 100.0) / denom),
			new Bucket("Max", Integer.toString(maxHit), maxHits, (maxHits * 100.0) / denom)
		);
	}
}
