package com.combatbreakdown.logic;

import com.combatbreakdown.model.FightRecord;
import com.combatbreakdown.model.HitCategory;
import com.combatbreakdown.model.HitDistribution;
import com.combatbreakdown.model.PinnedComparison;
import com.combatbreakdown.model.StatusDamageBreakdown;
import com.combatbreakdown.model.TargetSummary;
import com.combatbreakdown.model.WeaponStats;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.Skill;

public final class CombatSessionTracker
{
	public static final int MAX_RECENT_FIGHTS = 40;
	public static final int ROLLING_WINDOW_TICKS = 50; // 30 seconds
	private static final int SPARKLINE_INTERVAL_TICKS = 5; // 3 seconds
	private static final int MAX_SPARKLINE_POINTS = 40;
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
		int lastDirectDamage;
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

	@Value
	private static class TimedDamage
	{
		int tick;
		int damage;
	}

	@Value
	public static class SpecSnapshot
	{
		int uses;
		int hits;
		int misses;
		long totalDamage;
		int maxHit;

		public int getTotalSplats()
		{
			return hits + misses;
		}

		public double getAccuracyPercent()
		{
			int total = getTotalSplats();
			return total > 0 ? (hits * 100.0) / total : 0.0;
		}

		public double getAveragePerUse()
		{
			return uses > 0 ? ((double) totalDamage) / uses : 0.0;
		}
	}

	@Value
	public static class SkillXpEntry
	{
		Skill skill;
		long xpGained;
		double xpPerHour;
	}

	private int idleTimeoutTicks = 10;
	private boolean paused;
	private boolean sessionStarted;

	// Confirmed active combat ticks (excludes trailing idle cooldowns after combat stops)
	private boolean inActiveBout;
	private int activeTicks;
	private int pendingActiveTicks;
	private final Map<Integer, Integer> pendingWeaponTicks = new HashMap<>();
	private int lastActiveTickCounted = -10000;

	private int sessionTicks;
	private int lastCombatTick = -10000;
	private int currentTick;
	private int currentWeaponId = -1;
	private String currentWeaponName = "Unarmed";

	private long totalDirectDamage;
	private int totalHitsLanded;
	private int totalMisses;
	private int sessionKills;

	// Streaks & finishing hits
	private int currentHitStreak;
	private int bestHitStreak;
	private int currentMissStreak;
	private int worstMissStreak;
	private long totalKillingHitDamage;
	private int killingHitCount;

	// Rolling 30s DPS & sparkline history
	private final Deque<TimedDamage> rollingWindow = new ArrayDeque<>();
	private long rollingWindowDamage;
	private final Deque<Double> dpsSparkline = new ArrayDeque<>();
	private int lastSparklineTick = -10000;

	// Special attack tracking
	private int lastSpecUsedTick = -100;
	private int specUses;
	private int specHits;
	private int specMisses;
	private long specTotalDamage;
	private int specMaxHit;

	// Combat XP tracking (includes a short pre-hit buffer since OSRS XP drops precede hitsplats by 1-2 ticks)
	private final Map<Skill, Long> xpGainedBySkill = new EnumMap<>(Skill.class);
	private final Map<Skill, Long> pendingPreSessionXp = new EnumMap<>(Skill.class);
	private int pendingPreSessionXpTick = -10000;
	private long totalCombatXpGained;

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

	@Nullable
	private PinnedComparison pinnedComparison;

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
		return sessionStarted && !paused && inActiveBout && (currentTick - lastCombatTick) <= idleTimeoutTicks;
	}

	public void updateEquippedWeapon(int weaponItemId, @Nullable String weaponName)
	{
		this.currentWeaponId = weaponItemId;
		this.currentWeaponName = (weaponName != null && !weaponName.trim().isEmpty()) ? weaponName.trim() : "Unarmed";
	}

	public void recordSpecialAttackUsed(int tick)
	{
		if (paused)
		{
			return;
		}
		this.currentTick = tick;
		this.lastSpecUsedTick = tick;
		this.specUses++;
	}

	public void recordSkillXp(Skill skill, long deltaXp, int tick)
	{
		if (paused || skill == null || deltaXp <= 0)
		{
			return;
		}
		this.currentTick = tick;
		if (!sessionStarted)
		{
			// Buffer up to 5 ticks so the first attack's XP drop (which fires before the hitsplat) is never lost.
			if ((tick - pendingPreSessionXpTick) > 5)
			{
				pendingPreSessionXp.clear();
			}
			pendingPreSessionXpTick = tick;
			pendingPreSessionXp.merge(skill, deltaXp, Long::sum);
			return;
		}
		// Require recent combat activity so out-of-combat teleports/alchs do not inflate Combat XP/hr.
		if (!inActiveBout && (tick - lastCombatTick) > 6)
		{
			return;
		}
		xpGainedBySkill.merge(skill, deltaXp, Long::sum);
		totalCombatXpGained += deltaXp;
	}

	public void addSupplyCostGp(long gpCost)
	{
		if (gpCost > 0 && !paused)
		{
			unattributedSupplyCostGp += gpCost;
		}
	}

	public void recordNpcLootValue(String npcName, long lootGp)
	{
		if (lootGp <= 0 || npcName == null || npcName.trim().isEmpty())
		{
			return;
		}
		String cleanName = npcName.trim();
		TargetSummary summary = targetSummaries.get(cleanName);
		if (summary != null)
		{
			summary.addLootValueGp(lootGp);
		}
		for (FightRecord recent : recentFights)
		{
			if (cleanName.equals(recent.getNpcName()))
			{
				recent.setLootValueGp(recent.getLootValueGp() + lootGp);
				break;
			}
		}
	}

	public void pinCurrentComparison(long supplyBurnPerHour, long netProfitPerHour)
	{
		SessionSnapshot s = snapshot();
		String weapon = !s.getWeapons().isEmpty() ? s.getWeapons().get(0).getWeaponName() : currentWeaponName;
		String label = !s.getActiveTargetName().isEmpty()
			? (s.getActiveTargetName() + " (" + weapon + ")")
			: weapon;
		this.pinnedComparison = new PinnedComparison(
			label,
			weapon,
			s.getActiveDps(),
			s.getSessionDps(),
			s.getAccuracyPercent(),
			s.getMaxHit(),
			s.getKillsPerHour(),
			s.getCombatXpPerHour(),
			supplyBurnPerHour,
			netProfitPerHour
		);
	}

	public void clearPinnedComparison()
	{
		this.pinnedComparison = null;
	}

	@Nullable
	public PinnedComparison getPinnedComparison()
	{
		return pinnedComparison;
	}

	public void onGameTick(int tick)
	{
		this.currentTick = tick;
		pruneRollingWindow(tick);

		if (!sessionStarted || paused)
		{
			return;
		}
		sessionTicks++;

		if (inActiveBout)
		{
			if ((tick - lastCombatTick) <= idleTimeoutTicks)
			{
				if (tick > lastActiveTickCounted)
				{
					pendingActiveTicks++;
					pendingWeaponTicks.merge(currentWeaponId, 1, Integer::sum);
					lastActiveTickCounted = tick;
				}
				if ((tick - lastSparklineTick) >= SPARKLINE_INTERVAL_TICKS)
				{
					recordSparklineSample(tick);
				}
			}
			else
			{
				// Idle timeout expired with no follow-up hit: discard dead trailing ticks from Active DPS.
				endActiveBoutDiscardingTail();
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
		pruneRollingWindow(tick);

		if (category.isStatusDamage())
		{
			// Only attribute status splats to NPCs the player has already directly engaged.
			ActiveNpcFight engaged = activeNpcs.get(npcIndex);
			if (engaged == null)
			{
				return;
			}
			int cleanStatus = Math.max(0, amount);
			markCombatActivity(tick);
			engaged.lastActivityTick = tick;
			engaged.statusDamage += cleanStatus;
			statusAndDefense.recordOutgoingStatus(category, cleanStatus);
			addRollingDamage(tick, cleanStatus);
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
		boolean isSpecHit = (tick - lastSpecUsedTick) >= 0 && (tick - lastSpecUsedTick) <= 2;

		if (cleanDmg > 0)
		{
			totalHitsLanded++;
			totalDirectDamage += cleanDmg;
			fight.hitsLanded++;
			fight.directDamage += cleanDmg;
			fight.lastDirectDamage = cleanDmg;
			if (cleanDmg > fight.maxHit)
			{
				fight.maxHit = cleanDmg;
			}
			currentHitStreak++;
			currentMissStreak = 0;
			if (currentHitStreak > bestHitStreak)
			{
				bestHitStreak = currentHitStreak;
			}
			addRollingDamage(tick, cleanDmg);
			if (isSpecHit)
			{
				specHits++;
				specTotalDamage += cleanDmg;
				if (cleanDmg > specMaxHit)
				{
					specMaxHit = cleanDmg;
				}
			}
		}
		else
		{
			totalMisses++;
			fight.misses++;
			currentMissStreak++;
			currentHitStreak = 0;
			if (currentMissStreak > worstMissStreak)
			{
				worstMissStreak = currentMissStreak;
			}
			if (isSpecHit)
			{
				specMisses++;
			}
		}

		hitDistribution.record(cleanDmg, isMaxSplat);

		WeaponStats weapon = weaponStatsById.computeIfAbsent(currentWeaponId,
			id -> new WeaponStats(currentWeaponId, currentWeaponName));
		weapon.recordHit(cleanDmg, isMaxSplat);
		if (lastActiveTickCounted == tick && weapon.toSnapshot().getActiveTicks() == 0)
		{
			weapon.addActiveTick();
		}
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
			if (activeNpcs.isEmpty())
			{
				endActiveBoutDiscardingTail();
			}
			return null;
		}
		// Ignore deaths of NPCs the player abandoned long ago (e.g. finished off later by another player).
		if ((tick - fight.lastActivityTick) > Math.max(idleTimeoutTicks + 5, 20))
		{
			return null;
		}

		// Commit ticks up to the kill tick, then immediately pause Active DPS if no other engaged NPCs remain.
		flushPendingActiveTicks();
		if (activeNpcs.isEmpty())
		{
			recordSparklineSample(tick);
			inActiveBout = false;
			pendingActiveTicks = 0;
			pendingWeaponTicks.clear();
		}

		int effectiveEndTick = Math.min(tick, fight.lastActivityTick + 4);
		int durationTicks = Math.max(1, (effectiveEndTick - fight.firstHitTick) + 1);
		long allocatedSupplyCost = unattributedSupplyCostGp;
		unattributedSupplyCostGp = 0L;

		if (fight.lastDirectDamage > 0)
		{
			totalKillingHitDamage += fight.lastDirectDamage;
			killingHitCount++;
		}

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
			.killingHitDamage(fight.lastDirectDamage)
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
		inActiveBout = false;
		activeTicks = 0;
		pendingActiveTicks = 0;
		pendingWeaponTicks.clear();
		lastActiveTickCounted = -10000;
		sessionTicks = 0;
		lastCombatTick = -10000;
		totalDirectDamage = 0;
		totalHitsLanded = 0;
		totalMisses = 0;
		sessionKills = 0;
		currentHitStreak = 0;
		bestHitStreak = 0;
		currentMissStreak = 0;
		worstMissStreak = 0;
		totalKillingHitDamage = 0;
		killingHitCount = 0;
		rollingWindow.clear();
		rollingWindowDamage = 0;
		dpsSparkline.clear();
		lastSparklineTick = -10000;
		lastSpecUsedTick = -100;
		specUses = 0;
		specHits = 0;
		specMisses = 0;
		specTotalDamage = 0;
		specMaxHit = 0;
		xpGainedBySkill.clear();
		pendingPreSessionXp.clear();
		pendingPreSessionXpTick = -10000;
		totalCombatXpGained = 0;
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
		boolean activelyFighting = isInActiveCombat();
		boolean includePending = activelyFighting && !activeNpcs.isEmpty();
		int effectiveActiveTicks = activeTicks + (includePending ? pendingActiveTicks : 0);

		List<WeaponStats.Snapshot> weapons = new ArrayList<>(weaponStatsById.size());
		for (Map.Entry<Integer, WeaponStats> entry : weaponStatsById.entrySet())
		{
			int extra = includePending ? pendingWeaponTicks.getOrDefault(entry.getKey(), 0) : 0;
			weapons.add(entry.getValue().toSnapshot(extra));
		}
		weapons.sort(Comparator.comparingLong(WeaponStats.Snapshot::getTotalDamage).reversed());

		ActiveNpcFight focus = activeNpcs.get(focusNpcIndex);
		String activeTargetName = focus != null ? focus.npcName : lastTargetName;
		int activeTargetLvl = focus != null ? focus.combatLevel : lastTargetCombatLevel;
		int activeFightTicks = focus != null ? Math.max(1, (currentTick - focus.firstHitTick) + 1) : 0;
		long activeFightDamage = focus != null ? (focus.directDamage + focus.statusDamage) : 0L;

		double sessionSec = sessionTicks * WeaponStats.SECONDS_PER_TICK;
		double rollingSec = Math.min(ROLLING_WINDOW_TICKS * WeaponStats.SECONDS_PER_TICK,
			Math.max(WeaponStats.SECONDS_PER_TICK, Math.max(effectiveActiveTicks, 1) * WeaponStats.SECONDS_PER_TICK));
		double rolling30sDps = rollingWindowDamage > 0 ? (rollingWindowDamage / rollingSec) : 0.0;

		List<SkillXpEntry> skillXp = new ArrayList<>(xpGainedBySkill.size());
		double sessionHours = Math.max(WeaponStats.SECONDS_PER_TICK, sessionSec) / 3600.0;
		for (Map.Entry<Skill, Long> e : xpGainedBySkill.entrySet())
		{
			if (e.getValue() > 0)
			{
				skillXp.add(new SkillXpEntry(e.getKey(), e.getValue(), sessionSec > 0 ? e.getValue() / sessionHours : 0.0));
			}
		}
		skillXp.sort(Comparator.comparingLong(SkillXpEntry::getXpGained).reversed());

		SpecSnapshot specSnap = new SpecSnapshot(specUses, specHits, specMisses, specTotalDamage, specMaxHit);
		double avgKillingHit = killingHitCount > 0 ? ((double) totalKillingHitDamage) / killingHitCount : 0.0;

		return new SessionSnapshot(
			sessionStarted,
			paused,
			activelyFighting,
			effectiveActiveTicks,
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
			rolling30sDps,
			bestHitStreak,
			worstMissStreak,
			avgKillingHit,
			totalCombatXpGained,
			Collections.unmodifiableList(skillXp),
			specSnap,
			Collections.unmodifiableList(new ArrayList<>(dpsSparkline)),
			pinnedComparison,
			Collections.unmodifiableList(hitDistribution.toBuckets()),
			statusSnap,
			Collections.unmodifiableList(weapons),
			Collections.unmodifiableList(new ArrayList<>(recentFights)),
			getTargetSummariesSorted()
		);
	}

	public String formatSummaryText(long sessionSupplyCostGp, long sessionLootGp, boolean showGp)
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
			"Active DPS: %.2f (%s) | Session DPS: %.2f (%s) | 30s DPS: %.2f\n",
			s.getActiveDps(), formatDuration(s.getActiveSeconds()),
			s.getSessionDps(), formatDuration(s.getSessionSeconds()),
			s.getRolling30sDps()));
		sb.append(String.format(Locale.US,
			"Damage: %,d (Direct %,d + Status %,d) | Accuracy: %.1f%% (%d/%d) | Max Hit: %d (%d max splats)\n",
			s.getTotalDamage(), s.getTotalDirectDamage(), s.getTotalStatusDamage(),
			s.getAccuracyPercent(), s.getTotalHitsLanded(), s.getTotalSwings(),
			s.getMaxHit(), s.getNativeMaxSplats()));
		sb.append(String.format(Locale.US,
			"Kills: %d | Best Hit Streak: %d | Worst Miss Streak: %d | Taken: %,d | Healed: %,d\n",
			s.getSessionKills(), s.getBestHitStreak(), s.getWorstMissStreak(),
			s.getStatusAndDefense().getTotalDamageTaken(),
			s.getStatusAndDefense().getHealingReceived()));
		if (s.getTotalCombatXpGained() > 0)
		{
			sb.append(String.format(Locale.US, "Combat XP: %,d (%,.0f/hr)\n",
				s.getTotalCombatXpGained(), s.getCombatXpPerHour()));
		}
		if (s.getSpec().getUses() > 0)
		{
			sb.append(String.format(Locale.US, "Specs: %d uses | %.1f%% acc | avg %.1f | max %d\n",
				s.getSpec().getUses(), s.getSpec().getAccuracyPercent(),
				s.getSpec().getAveragePerUse(), s.getSpec().getMaxHit()));
		}
		if (showGp && (sessionSupplyCostGp > 0 || sessionLootGp > 0))
		{
			sb.append(String.format(Locale.US, "Loot: %,d gp | Burn: %,d gp | Net: %,d gp\n",
				sessionLootGp, sessionSupplyCostGp, sessionLootGp - sessionSupplyCostGp));
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
		sb.append("NPC,CombatLevel,Kills,AvgKillSec,FastestKillSec,AvgDPS,AccuracyPct,MaxHit,TotalDamage,DamageTaken,LootPerKillGp,SupplyCostPerKillGp,NetProfitPerKillGp\n");
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
				.append(t.getLootPerKillGp()).append(',')
				.append(t.getSupplyCostPerKillGp()).append(',')
				.append(t.getNetProfitPerKillGp()).append('\n');
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
			sessionTicks = 1;
			lastSparklineTick = tick;
			if (!pendingPreSessionXp.isEmpty() && (tick - pendingPreSessionXpTick) <= 5)
			{
				for (Map.Entry<Skill, Long> entry : pendingPreSessionXp.entrySet())
				{
					xpGainedBySkill.merge(entry.getKey(), entry.getValue(), Long::sum);
					totalCombatXpGained += entry.getValue();
				}
			}
			pendingPreSessionXp.clear();
		}
		if (inActiveBout)
		{
			flushPendingActiveTicks();
		}
		else
		{
			inActiveBout = true;
			pendingActiveTicks = 0;
			pendingWeaponTicks.clear();
		}
		if (tick > lastActiveTickCounted)
		{
			activeTicks++;
			WeaponStats ws = weaponStatsById.get(currentWeaponId);
			if (ws != null)
			{
				ws.addActiveTick();
			}
			lastActiveTickCounted = tick;
		}
		lastCombatTick = tick;
	}

	private void flushPendingActiveTicks()
	{
		if (pendingActiveTicks > 0)
		{
			activeTicks += pendingActiveTicks;
			for (Map.Entry<Integer, Integer> e : pendingWeaponTicks.entrySet())
			{
				WeaponStats ws = weaponStatsById.get(e.getKey());
				if (ws != null)
				{
					for (int i = 0; i < e.getValue(); i++)
					{
						ws.addActiveTick();
					}
				}
			}
			pendingActiveTicks = 0;
			pendingWeaponTicks.clear();
		}
	}

	private void endActiveBoutDiscardingTail()
	{
		inActiveBout = false;
		pendingActiveTicks = 0;
		pendingWeaponTicks.clear();
	}

	private void addRollingDamage(int tick, int damage)
	{
		if (damage <= 0)
		{
			return;
		}
		rollingWindow.addLast(new TimedDamage(tick, damage));
		rollingWindowDamage += damage;
	}

	private void pruneRollingWindow(int tick)
	{
		int cutoff = tick - ROLLING_WINDOW_TICKS;
		while (!rollingWindow.isEmpty() && rollingWindow.peekFirst().getTick() < cutoff)
		{
			rollingWindowDamage -= rollingWindow.removeFirst().getDamage();
		}
		if (rollingWindowDamage < 0)
		{
			rollingWindowDamage = 0;
		}
	}

	private void recordSparklineSample(int tick)
	{
		lastSparklineTick = tick;
		int effectiveTicks = Math.max(1, activeTicks + pendingActiveTicks);
		double rollingSec = Math.min(ROLLING_WINDOW_TICKS * WeaponStats.SECONDS_PER_TICK,
			effectiveTicks * WeaponStats.SECONDS_PER_TICK);
		double sample = rollingWindowDamage > 0 ? (rollingWindowDamage / rollingSec) : 0.0;
		dpsSparkline.addLast(sample);
		while (dpsSparkline.size() > MAX_SPARKLINE_POINTS)
		{
			dpsSparkline.removeFirst();
		}
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
		double rolling30sDps;
		int bestHitStreak;
		int worstMissStreak;
		double averageKillingHit;
		long totalCombatXpGained;
		List<SkillXpEntry> skillXp;
		SpecSnapshot spec;
		List<Double> dpsSparkline;
		@Nullable
		PinnedComparison pinnedComparison;
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

		public double getCombatXpPerHour()
		{
			if (totalCombatXpGained <= 0 || sessionTicks <= 0)
			{
				return 0.0;
			}
			double hours = Math.max(WeaponStats.SECONDS_PER_TICK, getSessionSeconds()) / 3600.0;
			return totalCombatXpGained / hours;
		}
	}
}
