package com.combatbreakdown;

import com.combatbreakdown.logic.SupplyBurnTracker;
import com.combatbreakdown.logic.SupplyBurnTracker.BurnSnapshot;
import com.combatbreakdown.logic.SupplyBurnTracker.ItemMetaLookup;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SupplyBurnTrackerTest
{
	private static final ItemMetaLookup META = new ItemMetaLookup()
	{
		@Override
		public String getName(int itemId)
		{
			switch (itemId)
			{
				case ItemID.SHARK:
					return "Shark";
				case ItemID.TBWT_COOKED_KARAMBWAN:
					return "Cooked karambwan";
				case ItemID._4DOSE2RESTORE:
					return "Super restore(4)";
				case ItemID._3DOSE2RESTORE:
					return "Super restore(3)";
				case ItemID._1DOSE2RESTORE:
					return "Super restore(1)";
				case ItemID.RING_OF_DUELING_4:
					return "Ring of dueling(4)";
				case ItemID.RING_OF_DUELING_3:
					return "Ring of dueling(3)";
				case ItemID.DRAGON_ARROW:
					return "Dragon arrow";
				case ItemID.DEATHRUNE:
					return "Death rune";
				case ItemID.MCANNONBALL:
					return "Cannonball";
				default:
					return "Item #" + itemId;
			}
		}

		@Override
		public long getUnitPrice(int itemId)
		{
			switch (itemId)
			{
				case ItemID.SHARK:
					return 1000;
				case ItemID.TBWT_COOKED_KARAMBWAN:
					return 700;
				case ItemID._4DOSE2RESTORE:
					return 12000;
				case ItemID._3DOSE2RESTORE:
					return 9000;
				case ItemID._1DOSE2RESTORE:
					return 3000;
				case ItemID.DRAGON_ARROW:
					return 1500;
				case ItemID.DEATHRUNE:
					return 200;
				case ItemID.MCANNONBALL:
					return 180;
				default:
					return 0;
			}
		}
	};

	@Test
	public void tracksFoodPotionDosesAmmoCannonballsAndRunesWhileIgnoringBankAndDrops()
	{
		SupplyBurnTracker tracker = new SupplyBurnTracker();

		// Initial baseline
		Map<Integer, Integer> inv0 = new HashMap<>();
		inv0.put(ItemID.SHARK, 10);
		inv0.put(ItemID.TBWT_COOKED_KARAMBWAN, 4);
		inv0.put(ItemID._4DOSE2RESTORE, 2);
		inv0.put(ItemID._1DOSE2RESTORE, 1);
		inv0.put(ItemID.RING_OF_DUELING_4, 1);
		inv0.put(ItemID.MCANNONBALL, 200);

		Map<Integer, Integer> pouch0 = Collections.singletonMap(ItemID.DEATHRUNE, 500);
		assertEquals(0L, tracker.onStateUpdate(inv0, ItemID.DRAGON_ARROW, 100, -1, 0, pouch0, false, true, false, 10, META));

		// Tick 11: Combo-eat 1 Shark + 1 Karambwan, drink 1 dose Super restore (4->3), fire 1 arrow, cast 2 Death runes, load 15 cannonballs
		// Also use Ring of Dueling (4->3) which must NOT count as a potion!
		Map<Integer, Integer> inv1 = new HashMap<>(inv0);
		inv1.put(ItemID.SHARK, 9);
		inv1.put(ItemID.TBWT_COOKED_KARAMBWAN, 3);
		inv1.put(ItemID._4DOSE2RESTORE, 1);
		inv1.put(ItemID._3DOSE2RESTORE, 1);
		inv1.remove(ItemID.RING_OF_DUELING_4);
		inv1.put(ItemID.RING_OF_DUELING_3, 1);
		inv1.put(ItemID.MCANNONBALL, 185);
		Map<Integer, Integer> pouch1 = Collections.singletonMap(ItemID.DEATHRUNE, 498);

		long cost1 = tracker.onStateUpdate(inv1, ItemID.DRAGON_ARROW, 99, -1, 0, pouch1, false, true, false, 11, META);
		// Shark (1000) + Karambwan (700) + 1 dose restore (3000) + 1 Dragon arrow (1500) + 15 cballs (2700) + 2 deaths (400) = 9300
		assertEquals(9300L, cost1);

		// Tick 12: Drop a (1) dose Super restore and 1 Shark on self tile -> MUST NOT count as consumed
		tracker.recordDroppedOnSelfTile(ItemID._1DOSE2RESTORE, 12);
		tracker.recordDroppedOnSelfTile(ItemID.SHARK, 12);
		Map<Integer, Integer> inv2 = new HashMap<>(inv1);
		inv2.remove(ItemID._1DOSE2RESTORE);
		inv2.put(ItemID.SHARK, 8);
		assertEquals("Dropped food/potion must not count as consumed",
			0L, tracker.onStateUpdate(inv2, ItemID.DRAGON_ARROW, 99, -1, 0, pouch1, false, true, false, 12, META));

		// Tick 13: Open bank and deposit everything -> MUST NOT count as consumed
		assertEquals("Depositing in bank must not count as consumed",
			0L, tracker.onStateUpdate(Collections.emptyMap(), -1, 0, -1, 0, Collections.emptyMap(), true, false, false, 13, META));

		BurnSnapshot snap = tracker.snapshot(60.0, 2);
		assertEquals(9300L, snap.getTotalCostGp());
		assertEquals(4650L, snap.getGpPerKill());
		assertEquals(2L, snap.getFoodEaten());
		assertEquals(1L, snap.getPotionDoses());
		assertEquals(16L, snap.getAmmoUsed());
		assertEquals(2L, snap.getRunesUsed());
	}

	@Test
	public void excludesJewelryAndChargeToolsFromPotionDetection()
	{
		assertTrue(SupplyBurnTracker.isConsumablePotionName("Super restore(4)"));
		assertTrue(SupplyBurnTracker.isConsumablePotionName("Divine super combat potion(1)"));
		assertTrue(SupplyBurnTracker.isConsumablePotionName("Prayer regeneration potion(3)"));
		assertFalse(SupplyBurnTracker.isConsumablePotionName("Ring of dueling(4)"));
		assertFalse(SupplyBurnTracker.isConsumablePotionName("Amulet of glory(4)"));
		assertFalse(SupplyBurnTracker.isConsumablePotionName("Watering can(4)"));
		assertFalse(SupplyBurnTracker.isConsumablePotionName("Waterskin(3)"));
		assertFalse(SupplyBurnTracker.isConsumablePotionName("Fungicide spray 4"));
	}
}
