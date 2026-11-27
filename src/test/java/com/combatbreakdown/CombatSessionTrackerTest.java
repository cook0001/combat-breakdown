package com.combatbreakdown;

import com.combatbreakdown.logic.CombatSessionTracker;
import com.combatbreakdown.logic.CombatSessionTracker.SessionSnapshot;
import com.combatbreakdown.logic.HistorySerializer;
import com.combatbreakdown.logic.HitsplatClassifier;
import com.combatbreakdown.model.FightRecord;
import com.combatbreakdown.model.HitCategory;
import com.combatbreakdown.model.HitDistribution;
import com.combatbreakdown.model.TargetSummary;
import com.combatbreakdown.model.WeaponStats;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import net.runelite.api.HitsplatID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CombatSessionTrackerTest
{
	@Test
	public void classifiesOutgoingAndIncomingHitsplatsAccurately()
	{
		assertEquals(HitCategory.DIRECT_MISS, HitsplatClassifier.classifyOutgoing(HitsplatID.BLOCK_ME, 0, true));
		assertEquals(HitCategory.DIRECT_HIT, HitsplatClassifier.classifyOutgoing(HitsplatID.DAMAGE_ME, 24, true));
		assertEquals(HitCategory.DIRECT_MAX_HIT, HitsplatClassifier.classifyOutgoing(HitsplatID.DAMAGE_MAX_ME, 45, true));
		assertEquals(HitCategory.STATUS_VENOM, HitsplatClassifier.classifyOutgoing(HitsplatID.VENOM, 12, false));
		assertEquals(HitCategory.STATUS_BURN, HitsplatClassifier.classifyOutgoing(HitsplatID.BURN, 5, false));
		assertEquals(HitCategory.OTHER, HitsplatClassifier.classifyOutgoing(HitsplatID.DAMAGE_OTHER, 30, false));
		assertEquals(HitCategory.OTHER, HitsplatClassifier.classifyOutgoing(HitsplatID.BLOCK_OTHER, 0, false));

		assertEquals(HitCategory.DIRECT_MISS, HitsplatClassifier.classifyIncoming(HitsplatID.BLOCK_ME, 0));
		assertEquals(HitCategory.DIRECT_HIT, HitsplatClassifier.classifyIncoming(HitsplatID.DAMAGE_ME, 18));
		assertEquals(HitCategory.HEAL, HitsplatClassifier.classifyIncoming(HitsplatID.HEAL, 20));
		assertEquals(HitCategory.PRAYER_DRAIN, HitsplatClassifier.classifyIncoming(HitsplatID.PRAYER_DRAIN, 4));
		assertEquals(HitCategory.STATUS_POISON, HitsplatClassifier.classifyIncoming(HitsplatID.POISON, 6));
	}

	@Test
	public void tracksDualTimersWeaponComparisonStatusDamageAndHistory()
	{
		CombatSessionTracker tracker = new CombatSessionTracker();
		tracker.setIdleTimeoutTicks(5);
		tracker.updateEquippedWeapon(ItemID.ABYSSAL_WHIP, "Abyssal whip");

		// Unrelated NPC #999 taking poison must be ignored
		tracker.onOutgoingHitsplat(999, "Random Goblin", 2, HitCategory.STATUS_POISON, 6, 100);
		assertFalse(tracker.snapshot().isSessionStarted());

		// Engage Vorkath (NPC #42) at tick 100 with Whip
		tracker.onOutgoingHitsplat(42, "Vorkath", 732, HitCategory.DIRECT_HIT, 24, 100);
		tracker.onGameTick(101);
		tracker.onOutgoingHitsplat(42, "Vorkath", 732, HitCategory.DIRECT_MISS, 0, 102);
		tracker.onGameTick(102);
		tracker.onOutgoingHitsplat(42, "Vorkath", 732, HitCategory.STATUS_VENOM, 10, 103);
		tracker.onIncomingHitsplat(HitCategory.DIRECT_HIT, 16, 103);
		tracker.onIncomingHitsplat(HitCategory.DIRECT_MISS, 0, 103);
		tracker.onIncomingHitsplat(HitCategory.HEAL, 20, 103);
		tracker.onGameTick(103);

		// Swap to Osmumten's fang and land a max hit splat
		tracker.updateEquippedWeapon(ItemID.OSMUMTENS_FANG, "Osmumten's fang");
		tracker.onOutgoingHitsplat(42, "Vorkath", 732, HitCategory.DIRECT_MAX_HIT, 48, 104);
		tracker.onGameTick(104);
		tracker.addSupplyCostGp(12_500L);

		FightRecord kill1 = tracker.onNpcDeath(42, 104, 1_700_000_000_000L);
		assertNotNull(kill1);
		assertEquals("Vorkath", kill1.getNpcName());
		assertEquals(5, kill1.getDurationTicks());
		assertEquals(72L, kill1.getDirectDamage());
		assertEquals(10L, kill1.getStatusDamage());
		assertEquals(48, kill1.getMaxHit());
		assertEquals(16L, kill1.getDamageTaken());
		assertEquals(20L, kill1.getHealingReceived());
		assertEquals(12_500L, kill1.getSupplyCostGp());
		assertEquals("Osmumten's fang", kill1.getPrimaryWeaponName());

		// Advance 20 idle ticks: active timer should pause after idleTimeoutTicks (5), session timer keeps running
		for (int t = 105; t <= 124; t++)
		{
			tracker.onGameTick(t);
		}

		SessionSnapshot snap = tracker.snapshot();
		assertTrue(snap.isSessionStarted());
		assertFalse("Should be out of active combat after idle gap", snap.isInActiveCombat());
		assertTrue("Active DPS must exceed continuous Session DPS after idle gap",
			snap.getActiveDps() > snap.getSessionDps());
		assertEquals(82L, snap.getTotalDamage());
		assertEquals(2, snap.getTotalHitsLanded());
		assertEquals(1, snap.getTotalMisses());
		assertEquals(48, snap.getMaxHit());
		assertEquals(1, snap.getNativeMaxSplats());
		assertEquals(1, snap.getSessionKills());
		assertEquals(10L, snap.getStatusAndDefense().getVenomDealt());
		assertEquals(50.0, snap.getStatusAndDefense().getEvadePercent(), 0.01);

		// Verify per-weapon breakdown sorted by damage (Fang 48 > Whip 24)
		List<WeaponStats.Snapshot> weapons = snap.getWeapons();
		assertEquals(2, weapons.size());
		assertEquals("Osmumten's fang", weapons.get(0).getWeaponName());
		assertEquals(48L, weapons.get(0).getTotalDamage());
		assertEquals(100.0, weapons.get(0).getAccuracyPercent(), 0.01);
		assertEquals("Abyssal whip", weapons.get(1).getWeaponName());
		assertEquals(24L, weapons.get(1).getTotalDamage());
		assertEquals(50.0, weapons.get(1).getAccuracyPercent(), 0.01);

		// Verify hit distribution buckets sum to 100%
		double pctSum = 0.0;
		for (HitDistribution.Bucket b : snap.getHitBuckets())
		{
			pctSum += b.getPercent();
		}
		assertEquals(100.0, pctSum, 0.1);

		// Verify CSV and summary exports
		String summaryText = tracker.formatSummaryText(12_500L, true);
		assertTrue(summaryText.contains("Vorkath"));
		assertTrue(summaryText.contains("Osmumten's fang"));
		String csv = tracker.formatHistoryCsv();
		assertTrue(csv.contains("Vorkath,732,1,"));

		// Verify HistorySerializer round-trip and malformed input resilience
		HistorySerializer serializer = new HistorySerializer(RuneLiteAPI.GSON);
		String json = serializer.serialize(snap.getTargetSummaries());
		List<TargetSummary> restored = serializer.deserialize(json);
		assertEquals(1, restored.size());
		assertEquals("Vorkath", restored.get(0).getNpcName());
		assertEquals(1, restored.get(0).getKills());
		assertEquals(12_500L, restored.get(0).getSupplyCostPerKillGp());
		assertTrue(serializer.deserialize("bad json").isEmpty());
		assertTrue(serializer.deserialize("").isEmpty());
	}

	@Test
	public void pluginIconsAreValidCompactPngs() throws IOException
	{
		BufferedImage sidebarIcon = ImageUtil.loadImageResource(CombatBreakdownPlugin.class, "/com/combatbreakdown/icon.png");
		assertNotNull("Sidebar icon missing", sidebarIcon);
		assertEquals(16, sidebarIcon.getWidth());
		assertEquals(16, sidebarIcon.getHeight());

		File rootIconFile = new File("icon.png");
		assertTrue("Repository root icon.png missing", rootIconFile.isFile());
		BufferedImage hubIcon = ImageIO.read(rootIconFile);
		assertNotNull("Repository root icon.png invalid", hubIcon);
		assertTrue("Plugin Hub icon width must be <= 48", hubIcon.getWidth() > 0 && hubIcon.getWidth() <= 48);
		assertTrue("Plugin Hub icon height must be <= 72", hubIcon.getHeight() > 0 && hubIcon.getHeight() <= 72);
	}
}
