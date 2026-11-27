package com.combatbreakdown.logic;

import com.combatbreakdown.model.FightRecord;
import com.combatbreakdown.model.HitCategory;
import com.combatbreakdown.model.HitDistribution;
import com.combatbreakdown.model.StatusDamageBreakdown;
import com.combatbreakdown.model.TargetSummary;
import com.combatbreakdown.model.WeaponStats;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Value;

public final class CombatSessionTracker
{
	public static final int MAX_RECENT_FIGHTS = 40;
	private static final int ABANDONED_NPC_TICKS = 100;

	private static final class ActiveNpcFight
	{
		final int npcIndex;
		final String npcName;
		final int combatLevel;
		final int firstHitTick;
		int lastActivityTick;
		long directDamage;
		long statusDamage;
		int hitsLanded;
		int misses;
		int maxHit;
		long damageTaken;
		long healingReceived;
		final Map<String, Long> damageByWeapon = new HashMap<>();

		ActiveNpcFight(int npcIndex, String npcName, int combatLevel, int tick)
		{
			this.npcIndex = npcIndex;
			this.npcName = npcName != null && !npcName.isEmpty() ? npcName : "Unknown NPC";
			this.combatLevel = combatLevel;
			this.firstHitTick = tick;
			this.lastActivityTick = tick;
		}

		String getPrimaryWeaponName()
		{
			String best = "Unarmed";
			long bestDmg = -1L;
			for (Map.Entry<String, Long> entry : damageByWeapon.entrySet())
			{
				if (entry.getValue() > bestDmg)
				{
					bestDmg = entry.getValue();
					best = entry.getKey();
				}
			}
			return best;
		}
	}

	private int idleTimeoutTicks = 10;
	private boolean paused;
	private boolean sessionStarted;
	private int activeTicks;
	private int sessionTicks;
	private int lastCombatTick = -10000;
	private int currentTick;
	private int currentWeaponId = -1;
	private String currentWeaponName = "Unarmed";

	private long totalDirectDamage;
	private int totalHitsLanded;
	private int totalMisses;
	private int sessionKills;

	private final HitDistribution hitDistribution = new HitDistribution();
	private final StatusDamageBreakdown statusAndDefense = new StatusDamageBreakdown();
	private final Map<Integer, WeaponStats> weaponStatsById = new LinkedHashMap<>();
	private final Map<Integer, ActiveNpcFight> activeNpcs = new HashMap<>();
	private final Deque<FightRecord> recentFights = new ArrayDeque<>();
	private final Map<String, TargetSummary> targetSummaries = new LinkedHashMap<>();

	private int focusNpcIndex = -1;
	private String lastTargetName = "";
	private int lastTargetCombatLevel;
	private long unattributedSupplyCostGp;

	public void setIdleTimeoutTicks(int idleTimeoutTicks)
	{
		this.idleTimeoutTicks = Math.max(2, Math.min(50, idleTimeoutTicks));
	}

	public void setPaused(boolean paused)
	{
		this.paused = paused;
	}

	public boolean isPaused()
	{
		return paused;
	}

	public boolean isInActiveCombat()
	{
		return sessionStarted && !paused && (currentTick - lastCombatTick) <= idleTimeoutTicks;
	}

	public void updateEquippedWeapon(int weaponItemId, @Nullable String weaponName)
	{
		this.currentWeaponId = weaponItemId;
		this.currentWeaponName = (weaponName != null && !weaponName.trim().isEmpty()) ? weaponName.trim() : "Unarmed";
	}

	public void addSupplyCostGp(long gpCost)
	{
		if (gpCost > 0 && !paused)
		{
			unattributedSupplyCostGp += gpCost;
		}
	}

	public void onGameTick(int tick)
	{
		this.currentTick = tick;
		if (!sessionStarted || paused)
		{
			return;
		}
		sessionTicks++;
		if ((tick - lastCombatTick) <= idleTimeoutTicks)
		{
			activeTicks++;
			WeaponStats ws = weaponStatsById.get(currentWeaponId);
			if (ws != null)
			{
				ws.addActiveTick();
			}
		}

		// Evict abandoned non-killed NPCs to keep memory bounded.
		if (!activeNpcs.isEmpty())
		{
			Iterator<Map.Entry<Integer, ActiveNpcFight>> it = activeNpcs.entrySet().iterator();
			while (it.hasNext())
			{
				ActiveNpcFight fight = it.next().getValue();
				if ((tick - fight.lastActivityTick) > ABANDONED_NPC_TICKS)
				{
					it.remove();
					if (fight.npcIndex == focusNpcIndex)
					{
						focusNpcIndex = -1;
					}
				}
			}
		}
	}

	public void onOutgoingHitsplat(int npcIndex, String npcName, int combatLevel, HitCategory category, int amount, int tick)
	{
		if (paused || category == HitCategory.OTHER)
		{
			return;
		}
		this.currentTick = tick;

		if (category.isStatusDamage())
		{
			// Only attribute status splats to NPCs the player has already directly engaged.
			ActiveNpcFight engaged = activeNpcs.get(npcIndex);
			if (engaged == null)
			{
				return;
			}
			markCombatActivity(tick);
			engaged.lastActivityTick = tick;
			engaged.statusDamage += Math.max(0, amount);
			statusAndDefense.recordOutgoingStatus(category, amount);
			return;
		}

		if (!category.isDirectOutgoing())
		{
			return;
		}

		markCombatActivity(tick);
		ActiveNpcFight fight = activeNpcs.computeIfAbsent(npcIndex,
			k -> new ActiveNpcFight(npcIndex, npcName, combatLevel, tick));
		fight.lastActivityTick = tick;
		focusNpcIndex = npcIndex;
		lastTargetName = fight.npcName;
		lastTargetCombatLevel = fight.combatLevel;

		boolean isMaxSplat = category == HitCategory.DIRECT_MAX_HIT;
		int cleanDmg = category == HitCategory.DIRECT_MISS ? 0 : Math.max(0, amount);

		if (cleanDmg > 0)
		{
			totalHitsLanded++;
			totalDirectDamage += cleanDmg;
			fight.hitsLanded++;
			fight.directDamage += cleanDmg;
			if (cleanDmg > fight.maxHit)
			{
				fight.maxHit = cleanDmg;
			}
		}
		else
		{
			totalMisses++;
			fight.misses++;
		}

		hitDistribution.record(cleanDmg, isMaxSplat);

		WeaponStats weapon = weaponStatsById.computeIfAbsent(currentWeaponId,
			id -> new WeaponStats(currentWeaponId, currentWeaponName));
		weapon.recordHit(cleanDmg, isMaxSplat);
		fight.damageByWeapon.merge(currentWeaponName, (long) cleanDmg, Long::sum);
	}

	public void onIncomingHitsplat(HitCategory category, int amount, int tick)
	{
		if (paused || category == HitCategory.OTHER)
		{
			return;
		}
		this.currentTick = tick;

		// Ignore passive out-of-combat regen/heals if no session has started yet.
		if (!sessionStarted && category == HitCategory.HEAL)
		{
			return;
		}

		if (category != HitCategory.HEAL)
		{
			markCombatActivity(tick);
		}

		statusAndDefense.recordIncoming(category, amount);

		ActiveNpcFight focus = activeNpcs.get(focusNpcIndex);
		if (focus != null)
		{
			focus.lastActivityTick = tick;
			if (category == HitCategory.HEAL)
			{
				focus.healingReceived += Math.max(0, amount);
			}
			else if (category != HitCategory.DIRECT_MISS && category != HitCategory.PRAYER_DRAIN)
			{
				focus.damageTaken += Math.max(0, amount);
			}
		}
	}

	@Nullable
	public FightRecord onNpcDeath(int npcIndex, int tick, long nowEpochMs)
	{
		ActiveNpcFight fight = activeNpcs.remove(npcIndex);
		if (fight == null)
		{
			return null;
		}
		if (focusNpcIndex == npcIndex)
		{
			focusNpcIndex = -1;
		}
		if (fight.hitsLanded == 0 && fight.misses == 0 && fight.statusDamage == 0)
		{
			return null;
		}

		int durationTicks = Math.max(1, (tick - fight.firstHitTick) + 1);
		long allocatedSupplyCost = unattributedSupplyCostGp;
		unattributedSupplyCostGp = 0L;

		FightRecord record = FightRecord.builder()
			.npcName(fight.npcName)
			.combatLevel(fight.combatLevel)
			.durationTicks(durationTicks)
			.directDamage(fight.directDamage)
			.statusDamage(fight.statusDamage)
			.hitsLanded(fight.hitsLanded)
			.misses(fight.misses)
			.maxHit(fight.maxHit)
			.damageTaken(fight.damageTaken)
			.healingReceived(fight.healingReceived)
			.supplyCostGp(allocatedSupplyCost)
			.primaryWeaponName(fight.getPrimaryWeaponName())
			.completedAtEpochMs(nowEpochMs)
			.build();

		sessionKills++;
		recentFights.addFirst(record);
		while (recentFights.size() > MAX_RECENT_FIGHTS)
		{
			recentFights.removeLast();
		}

		TargetSummary summary = targetSummaries.computeIfAbsent(fight.npcName,
			name -> TargetSummary.builder()
				.npcName(name)
				.combatLevel(fight.combatLevel)
				.build());
		summary.mergeFight(record);
		return record;
	}

	public void loadPersistedSummaries(List<TargetSummary> summaries)
	{
		targetSummaries.clear();
		if (summaries == null)
		{
			return;
		}
		for (TargetSummary summary : summaries)
		{
			if (summary != null && summary.getNpcName() != null && !summary.getNpcName().trim().isEmpty())
			{
				targetSummaries.put(summary.getNpcName(), summary);
			}
		}
	}

	public List<TargetSummary> getTargetSummariesSorted()
	{
		List<TargetSummary> list = new ArrayList<>(targetSummaries.values());
		list.sort(Comparator.comparingLong(TargetSummary::getLastKilledEpochMs).reversed()
			.thenComparingInt(TargetSummary::getKills).reversed());
		return Collections.unmodifiableList(list);
	}

	public void resetSessionOnly()
	{
		sessionStarted = false;
		paused = false;
		activeTicks = 0;
		sessionTicks = 0;
		lastCombatTick = -10000;
		totalDirectDamage = 0;
		totalHitsLanded = 0;
		totalMisses = 0;
		sessionKills = 0;
		focusNpcIndex = -1;
		lastTargetName = "";
		lastTargetCombatLevel = 0;
		unattributedSupplyCostGp = 0L;
		hitDistribution.reset();
		statusAndDefense.reset();
		weaponStatsById.clear();
		activeNpcs.clear();
		recentFights.clear();
	}

	public void clearPersistedHistory()
	{
		targetSummaries.clear();
		recentFights.clear();
	}

	public SessionSnapshot snapshot()
	{
		StatusDamageBreakdown.Snapshot statusSnap = statusAndDefense.toSnapshot();
		List<WeaponStats.Snapshot> weapons = new ArrayList<>(weaponStatsById.size());
		for (WeaponStats ws : weaponStatsById.values())
		{
			weapons.add(ws.toSnapshot());
		}
		weapons.sort(Comparator.comparingLong(WeaponStats.Snapshot::getTotalDamage).reversed());

		ActiveNpcFight focus = activeNpcs.get(focusNpcIndex);
		String activeTargetName = focus != null ? focus.npcName : lastTargetName;
		int activeTargetLvl = focus != null ? focus.combatLevel : lastTargetCombatLevel;
		int activeFightTicks = focus != null ? Math.max(1, (currentTick - focus.firstHitTick) + 1) : 0;
		long activeFightDamage = focus != null ? (focus.directDamage + focus.statusDamage) : 0L;

		return new SessionSnapshot(
			sessionStarted,
			paused,
			isInActiveCombat(),
			activeTicks,
			sessionTicks,
			activeTargetName,
			activeTargetLvl,
			activeFightTicks,
			activeFightDamage,
			totalDirectDamage,
			statusSnap.getTotalStatusDealt(),
			totalHitsLanded,
			totalMisses,
			hitDistribution.getMaxHit(),
			hitDistribution.getNativeMaxSplats(),
			sessionKills,
			Collections.unmodifiableList(hitDistribution.toBuckets()),
			statusSnap,
			Collections.unmodifiableList(weapons),
			Collections.unmodifiableList(new ArrayList<>(recentFights)),
			getTargetSummariesSorted()
		);
	}

	public String formatSummaryText(long sessionSupplyCostGp, boolean showGp)
	{
		SessionSnapshot s = snapshot();
		StringBuilder sb = new StringBuilder();
		sb.append("Combat Breakdown Summary\n");
		if (!s.getActiveTargetName().isEmpty())
		{
			sb.append("Target: ").append(s.getActiveTargetName());
			if (s.getActiveTargetCombatLevel() > 0)
			{
				sb.append(" (lvl ").append(s.getActiveTargetCombatLevel()).append(")");
			}
			sb.append('\n');
		}
		sb.append(String.format(Locale.US,
			"Active DPS: %.2f (%s) | Session DPS: %.2f (%s)\n",
			s.getActiveDps(), formatDuration(s.getActiveSeconds()),
			s.getSessionDps(), formatDuration(s.getSessionSeconds())));
		sb.append(String.format(Locale.US,
			"Damage: %,d (Direct %,d + Status %,d) | Accuracy: %.1f%% (%d/%d) | Max Hit: %d (%d max splats)\n",
			s.getTotalDamage(), s.getTotalDirectDamage(), s.getTotalStatusDamage(),
			s.getAccuracyPercent(), s.getTotalHitsLanded(), s.getTotalSwings(),
			s.getMaxHit(), s.getNativeMaxSplats()));
		sb.append(String.format(Locale.US,
			"Kills: %d | Damage Taken: %,d | Healed: %,d\n",
			s.getSessionKills(), s.getStatusAndDefense().getTotalDamageTaken(),
			s.getStatusAndDefense().getHealingReceived()));
		if (showGp && sessionSupplyCostGp > 0)
		{
			sb.append(String.format(Locale.US, "Supply Cost: %,d gp\n", sessionSupplyCostGp));
		}
		if (!s.getWeapons().isEmpty())
		{
			sb.append("Weapons:\n");
			for (WeaponStats.Snapshot w : s.getWeapons())
			{
				sb.append(String.format(Locale.US,
					"  - %s: %.2f DPS | %.1f%% acc | avg %.1f | max %d (%d swings)\n",
					w.getWeaponName(), w.getActiveDps(), w.getAccuracyPercent(),
					w.getAverageLandedHit(), w.getMaxHit(), w.getTotalSwings()));
			}
		}
		return sb.toString().trim();
	}

	public String formatHistoryCsv()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("NPC,CombatLevel,Kills,AvgKillSec,FastestKillSec,AvgDPS,AccuracyPct,MaxHit,TotalDamage,DamageTaken,SupplyCostPerKillGp\n");
		for (TargetSummary t : getTargetSummariesSorted())
		{
			sb.append(escapeCsv(t.getNpcName())).append(',')
				.append(t.getCombatLevel()).append(',')
				.append(t.getKills()).append(',')
				.append(String.format(Locale.US, "%.1f", t.getAverageKillSeconds())).append(',')
				.append(String.format(Locale.US, "%.1f", t.getFastestKillSeconds())).append(',')
				.append(String.format(Locale.US, "%.2f", t.getAverageDps())).append(',')
				.append(String.format(Locale.US, "%.1f", t.getAccuracyPercent())).append(',')
				.append(t.getMaxHit()).append(',')
				.append(t.getTotalDamage()).append(',')
				.append(t.getTotalDamageTaken()).append(',')
				.append(t.getSupplyCostPerKillGp()).append('\n');
		}
		return sb.toString();
	}

	public static String formatDuration(double totalSeconds)
	{
		int secs = Math.max(0, (int) Math.round(totalSeconds));
		int h = secs / 3600;
		int m = (secs % 3600) / 60;
		int s = secs % 60;
		if (h > 0)
		{
			return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
		}
		return String.format(Locale.US, "%d:%02d", m, s);
	}

	private static String escapeCsv(String value)
	{
		if (value == null)
		{
			return "";
		}
		if (value.contains(",") || value.contains("\"") || value.contains("\n"))
		{
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}

	private void markCombatActivity(int tick)
	{
		if (!sessionStarted)
		{
			sessionStarted = true;
			activeTicks = 1;
			sessionTicks = 1;
		}
		lastCombatTick = tick;
	}

	@Value
	public static class SessionSnapshot
	{
		boolean sessionStarted;
		boolean paused;
		boolean inActiveCombat;
		int activeTicks;
		int sessionTicks;
		String activeTargetName;
		int activeTargetCombatLevel;
		int activeFightTicks;
		long activeFightDamage;
		long totalDirectDamage;
		long totalStatusDamage;
		int totalHitsLanded;
		int totalMisses;
		int maxHit;
		int nativeMaxSplats;
		int sessionKills;
		List<HitDistribution.Bucket> hitBuckets;
		StatusDamageBreakdown.Snapshot statusAndDefense;
		List<WeaponStats.Snapshot> weapons;
		List<FightRecord> recentFights;
		List<TargetSummary> targetSummaries;

		public long getTotalDamage()
		{
			return totalDirectDamage + totalStatusDamage;
		}

		public int getTotalSwings()
		{
			return totalHitsLanded + totalMisses;
		}

		public double getActiveSeconds()
		{
			return activeTicks * WeaponStats.SECONDS_PER_TICK;
		}

		public double getSessionSeconds()
		{
			return sessionTicks * WeaponStats.SECONDS_PER_TICK;
		}

		public double getActiveDps()
		{
			if (getTotalDamage() <= 0 || activeTicks <= 0)
			{
				return 0.0;
			}
			return getTotalDamage() / Math.max(WeaponStats.SECONDS_PER_TICK, getActiveSeconds());
		}

		public double getSessionDps()
		{
			if (getTotalDamage() <= 0 || sessionTicks <= 0)
			{
				return 0.0;
			}
			return getTotalDamage() / Math.max(WeaponStats.SECONDS_PER_TICK, getSessionSeconds());
		}

		public double getAccuracyPercent()
		{
			int total = getTotalSwings();
			return total > 0 ? (totalHitsLanded * 100.0) / total : 0.0;
		}

		public double getAverageLandedHit()
		{
			return totalHitsLanded > 0 ? ((double) totalDirectDamage) / totalHitsLanded : 0.0;
		}

		public double getKillsPerHour()
		{
			if (sessionKills <= 0 || sessionTicks <= 0)
			{
				return 0.0;
			}
			double hours = Math.max(WeaponStats.SECONDS_PER_TICK, getSessionSeconds()) / 3600.0;
			return sessionKills / hours;
		}
	}
}
