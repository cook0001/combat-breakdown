package com.combatbreakdown.logic;

import com.combatbreakdown.model.SupplyCategory;
import com.combatbreakdown.model.SupplyUsage;
import com.combatbreakdown.model.WeaponStats;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.api.gameval.ItemID;

public final class SupplyBurnTracker
{
	public interface ItemMetaLookup
	{
		String getName(int itemId);

		long getUnitPrice(int itemId);
	}

	private static final Pattern DOSE_SUFFIX = Pattern.compile("^(.+)\\(([1-4])\\)$");

	private static final Set<Integer> RUNE_IDS;
	private static final Set<Integer> COMMON_FOOD_IDS;
	private static final Set<Integer> CANNONBALL_IDS;

	static
	{
		Set<Integer> runes = new HashSet<>();
		runes.add(ItemID.AIRRUNE);
		runes.add(ItemID.WATERRUNE);
		runes.add(ItemID.EARTHRUNE);
		runes.add(ItemID.FIRERUNE);
		runes.add(ItemID.MINDRUNE);
		runes.add(ItemID.BODYRUNE);
		runes.add(ItemID.COSMICRUNE);
		runes.add(ItemID.CHAOSRUNE);
		runes.add(ItemID.NATURERUNE);
		runes.add(ItemID.LAWRUNE);
		runes.add(ItemID.DEATHRUNE);
		runes.add(ItemID.ASTRALRUNE);
		runes.add(ItemID.BLOODRUNE);
		runes.add(ItemID.SOULRUNE);
		runes.add(ItemID.WRATHRUNE);
		runes.add(ItemID.MISTRUNE);
		runes.add(ItemID.DUSTRUNE);
		runes.add(ItemID.MUDRUNE);
		runes.add(ItemID.SMOKERUNE);
		runes.add(ItemID.STEAMRUNE);
		runes.add(ItemID.LAVARUNE);
		runes.add(ItemID.SUNFIRERUNE);
		runes.add(ItemID.AETHERRUNE);
		RUNE_IDS = Collections.unmodifiableSet(runes);

		Set<Integer> food = new HashSet<>();
		food.add(ItemID.ANGLERFISH);
		food.add(ItemID.MANTARAY);
		food.add(ItemID.DARK_CRAB);
		food.add(ItemID.SEATURTLE);
		food.add(ItemID.SHARK);
		food.add(ItemID.TBWT_COOKED_KARAMBWAN);
		food.add(ItemID.MONKFISH);
		food.add(ItemID.SWORDFISH);
		food.add(ItemID.LOBSTER);
		food.add(ItemID.TUNA);
		food.add(ItemID.SALMON);
		food.add(ItemID.TROUT);
		food.add(ItemID.POTATO_TUNA_SWEETCORN);
		food.add(ItemID.POTATO_MUSHROOM_ONION);
		food.add(ItemID.POTATO_CHEESE);
		food.add(ItemID.SUMMER_PIE);
		food.add(ItemID.PINEAPPLE_PIZZA);
		food.add(ItemID.BASS);
		food.add(ItemID.JUG_WINE);
		food.add(ItemID.CAKE);
		food.add(ItemID.BLIGHTED_ANGLERFISH);
		food.add(ItemID.BLIGHTED_MANTARAY);
		food.add(ItemID.BLIGHTED_KARAMBWAN);
		COMMON_FOOD_IDS = Collections.unmodifiableSet(food);

		Set<Integer> cballs = new HashSet<>();
		cballs.add(ItemID.MCANNONBALL);
		cballs.add(ItemID.GRANITE_CANNONBALL);
		CANNONBALL_IDS = Collections.unmodifiableSet(cballs);
	}

	private boolean initialized;
	private int storageGraceUntilTick = -1;
	private Map<Integer, Integer> prevInv = Collections.emptyMap();
	private Map<Integer, Integer> prevRunes = Collections.emptyMap();
	private int prevAmmoId = -1;
	private int prevAmmoQty;
	private int prevWeaponId = -1;
	private int prevWeaponQty;

	private int lastEatTick = -100;
	private int lastEatItemId = -1;
	private int lastDrinkTick = -100;
	private final Map<Integer, Integer> recentDroppedTickByItem = new HashMap<>();

	private final Map<String, SupplyUsage> usageByKey = new LinkedHashMap<>();
	private final Map<Integer, LootItemEntry> lootByItemId = new LinkedHashMap<>();
	private long totalCostGp;
	private long totalLootGp;

	@Value
	public static class LootSnapshot
	{
		int itemId;
		String displayName;
		long quantity;
		long totalValueGp;
	}

	private static final class LootItemEntry
	{
		final int itemId;
		final String displayName;
		long quantity;
		long totalValueGp;

		LootItemEntry(int itemId, String displayName)
		{
			this.itemId = itemId;
			this.displayName = displayName;
		}
	}

	public void recordConsumeAction(String menuOption, int itemId, int tick)
	{
		if (menuOption == null)
		{
			return;
		}
		String lower = menuOption.toLowerCase(Locale.ROOT);
		if ("eat".equals(lower))
		{
			lastEatTick = tick;
			lastEatItemId = itemId;
		}
		else if ("drink".equals(lower))
		{
			lastDrinkTick = tick;
		}
	}

	public void recordDroppedOnSelfTile(int itemId, int tick)
	{
		if (itemId > 0)
		{
			recentDroppedTickByItem.put(itemId, tick);
		}
	}

	public void invalidateBaseline()
	{
		initialized = false;
		prevInv = Collections.emptyMap();
		prevRunes = Collections.emptyMap();
		prevAmmoId = -1;
		prevAmmoQty = 0;
		prevWeaponId = -1;
		prevWeaponQty = 0;
	}

	public void reset()
	{
		usageByKey.clear();
		lootByItemId.clear();
		totalCostGp = 0L;
		totalLootGp = 0L;
		recentDroppedTickByItem.clear();
		invalidateBaseline();
	}

	public long recordLootDrop(int itemId, int quantity, ItemMetaLookup meta)
	{
		if (itemId <= 0 || quantity <= 0)
		{
			return 0L;
		}
		String name = cleanName(meta.getName(itemId), itemId);
		long unitPrice = itemId == ItemID.COINS ? 1L : Math.max(0L, meta.getUnitPrice(itemId));
		long value = unitPrice * quantity;
		LootItemEntry entry = lootByItemId.computeIfAbsent(itemId, id -> new LootItemEntry(itemId, name));
		entry.quantity += quantity;
		entry.totalValueGp += value;
		totalLootGp += value;
		return value;
	}

	/**
	 * Processes an updated inventory/equipment/rune-pouch state and returns newly consumed GP cost.
	 */
	public long onStateUpdate(
		Map<Integer, Integer> invCounts,
		int ammoId,
		int ammoQty,
		int weaponId,
		int weaponQty,
		Map<Integer, Integer> runePouchCounts,
		boolean storageUiOpen,
		boolean inCombat,
		boolean paused,
		int tick,
		ItemMetaLookup meta)
	{
		Map<Integer, Integer> safeInv = invCounts != null ? invCounts : Collections.emptyMap();
		Map<Integer, Integer> currentRunes = collectTotalRunes(safeInv, runePouchCounts);

		if (storageUiOpen)
		{
			storageGraceUntilTick = tick + 1;
			captureBaseline(safeInv, currentRunes, ammoId, ammoQty, weaponId, weaponQty);
			return 0L;
		}

		if (!initialized || paused || tick <= storageGraceUntilTick)
		{
			captureBaseline(safeInv, currentRunes, ammoId, ammoQty, weaponId, weaponQty);
			return 0L;
		}

		long deltaGp = 0L;
		boolean recentEat = (tick - lastEatTick) <= 3;
		boolean recentDrink = (tick - lastDrinkTick) <= 3;

		// 1. Track potion doses by base potion name across (1)-(4) variants.
		if (inCombat || recentDrink)
		{
			deltaGp += detectPotionDoses(prevInv, safeInv, tick, meta);
		}

		// 2. Track food eaten (1-2 per tick for single or combo eats).
		if (inCombat || recentEat)
		{
			for (Map.Entry<Integer, Integer> entry : prevInv.entrySet())
			{
				int itemId = entry.getKey();
				int prevQty = entry.getValue();
				int nowQty = safeInv.getOrDefault(itemId, 0);
				int diff = prevQty - nowQty;
				if (diff <= 0 || diff > 2 || wasRecentlyDropped(itemId, tick))
				{
					continue;
				}
				boolean matchedFood = COMMON_FOOD_IDS.contains(itemId)
					|| (recentEat && itemId == lastEatItemId && !isConsumablePotionName(meta.getName(itemId)));
				if (matchedFood)
				{
					String name = cleanName(meta.getName(itemId), itemId);
					long cost = (long) Math.max(0, meta.getUnitPrice(itemId)) * diff;
					recordUsage("food:" + itemId, name, itemId, SupplyCategory.FOOD, diff, cost);
					deltaGp += cost;
				}
			}
		}

		// 3. Track equipped ammo, stackable thrown weapons, cannonballs, and runes strictly during active combat.
		if (inCombat)
		{
			if (ammoId > 0 && ammoId == prevAmmoId && prevAmmoQty > ammoQty)
			{
				int spent = prevAmmoQty - ammoQty;
				if (spent <= 20)
				{
					String name = cleanName(meta.getName(ammoId), ammoId);
					long cost = (long) Math.max(0, meta.getUnitPrice(ammoId)) * spent;
					recordUsage("ammo:" + ammoId, name, ammoId, SupplyCategory.AMMO, spent, cost);
					deltaGp += cost;
				}
			}

			if (weaponId > 0 && weaponId == prevWeaponId && prevWeaponQty > 1 && prevWeaponQty > weaponQty)
			{
				int spent = prevWeaponQty - weaponQty;
				if (spent <= 10)
				{
					String name = cleanName(meta.getName(weaponId), weaponId);
					long cost = (long) Math.max(0, meta.getUnitPrice(weaponId)) * spent;
					recordUsage("ammo:" + weaponId, name, weaponId, SupplyCategory.AMMO, spent, cost);
					deltaGp += cost;
				}
			}

			for (int cballId : CANNONBALL_IDS)
			{
				int prevQty = prevInv.getOrDefault(cballId, 0);
				int nowQty = safeInv.getOrDefault(cballId, 0);
				int spent = prevQty - nowQty;
				if (spent > 0 && spent <= 30 && !wasRecentlyDropped(cballId, tick))
				{
					String name = cleanName(meta.getName(cballId), cballId);
					long cost = (long) Math.max(0, meta.getUnitPrice(cballId)) * spent;
					recordUsage("ammo:" + cballId, name, cballId, SupplyCategory.AMMO, spent, cost);
					deltaGp += cost;
				}
			}

			for (Map.Entry<Integer, Integer> entry : prevRunes.entrySet())
			{
				int runeId = entry.getKey();
				int prevQty = entry.getValue();
				int nowQty = currentRunes.getOrDefault(runeId, 0);
				int spent = prevQty - nowQty;
				if (spent > 0 && spent <= 40 && !wasRecentlyDropped(runeId, tick))
				{
					String name = cleanName(meta.getName(runeId), runeId);
					long cost = (long) Math.max(0, meta.getUnitPrice(runeId)) * spent;
					recordUsage("rune:" + runeId, name, runeId, SupplyCategory.RUNES, spent, cost);
					deltaGp += cost;
				}
			}
		}

		captureBaseline(safeInv, currentRunes, ammoId, ammoQty, weaponId, weaponQty);
		return deltaGp;
	}

	private long detectPotionDoses(
		Map<Integer, Integer> before,
		Map<Integer, Integer> after,
		int tick,
		ItemMetaLookup meta)
	{
		Map<String, PotionTally> beforePotions = tallyPotions(before, tick, meta);
		if (beforePotions.isEmpty())
		{
			return 0L;
		}
		Map<String, PotionTally> afterPotions = tallyPotions(after, tick, meta);

		long costAdded = 0L;
		for (Map.Entry<String, PotionTally> entry : beforePotions.entrySet())
		{
			String baseName = entry.getKey();
			PotionTally prev = entry.getValue();
			PotionTally now = afterPotions.get(baseName);
			int nowDoses = now != null ? now.totalDoses : 0;
			int consumed = prev.totalDoses - nowDoses;
			if (consumed <= 0 || consumed > 2 || prev.anyVariantDropped)
			{
				continue;
			}
			long cost = prev.estimatedDosePriceGp * consumed;
			recordUsage("potion:" + baseName, baseName, prev.sampleItemId, SupplyCategory.POTION, consumed, cost);
			costAdded += cost;
		}
		return costAdded;
	}

	private Map<String, PotionTally> tallyPotions(Map<Integer, Integer> inv, int tick, ItemMetaLookup meta)
	{
		Map<String, PotionTally> map = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : inv.entrySet())
		{
			int itemId = entry.getKey();
			int qty = entry.getValue();
			if (qty <= 0)
			{
				continue;
			}
			String name = meta.getName(itemId);
			if (!isConsumablePotionName(name))
			{
				continue;
			}
			Matcher m = DOSE_SUFFIX.matcher(name.trim());
			if (!m.matches())
			{
				continue;
			}
			String base = m.group(1).trim();
			int dosesPerItem = Integer.parseInt(m.group(2));
			long unitPrice = Math.max(0L, meta.getUnitPrice(itemId));
			long perDose = dosesPerItem > 0 ? Math.round((double) unitPrice / dosesPerItem) : 0L;

			PotionTally tally = map.computeIfAbsent(base, k -> new PotionTally());
			tally.totalDoses += qty * dosesPerItem;
			if (wasRecentlyDropped(itemId, tick))
			{
				tally.anyVariantDropped = true;
			}
			if (dosesPerItem >= tally.bestDoseVariant)
			{
				tally.bestDoseVariant = dosesPerItem;
				tally.sampleItemId = itemId;
				tally.estimatedDosePriceGp = perDose;
			}
		}
		return map;
	}

	public static boolean isConsumablePotionName(String name)
	{
		if (name == null)
		{
			return false;
		}
		String trimmed = name.trim();
		if (!DOSE_SUFFIX.matcher(trimmed).matches())
		{
			return false;
		}
		String lower = trimmed.toLowerCase(Locale.ROOT);
		// Exclude teleport jewelry, watering cans, waterskins, and charge-based tools that also use (1)-(4).
		return !lower.contains("ring")
			&& !lower.contains("amulet")
			&& !lower.contains("necklace")
			&& !lower.contains("bracelet")
			&& !lower.contains("pendant")
			&& !lower.contains("watering can")
			&& !lower.contains("waterskin")
			&& !lower.contains("crystal")
			&& !lower.contains("rod of ivandis")
			&& !lower.contains("pharaoh")
			&& !lower.contains("sceptre")
			&& !lower.contains("sack")
			&& !lower.contains("basket")
			&& !lower.contains("fungicide");
	}

	private void recordUsage(
		String key,
		String displayName,
		int iconItemId,
		SupplyCategory category,
		long units,
		long costGp)
	{
		SupplyUsage usage = usageByKey.computeIfAbsent(key,
			k -> new SupplyUsage(key, displayName, iconItemId, category));
		usage.add(units, costGp);
		totalCostGp += Math.max(0L, costGp);
	}

	private boolean wasRecentlyDropped(int itemId, int tick)
	{
		Integer droppedTick = recentDroppedTickByItem.get(itemId);
		return droppedTick != null && (tick - droppedTick) <= 2;
	}

	private void captureBaseline(
		Map<Integer, Integer> inv,
		Map<Integer, Integer> runes,
		int ammoId,
		int ammoQty,
		int weaponId,
		int weaponQty)
	{
		this.prevInv = new HashMap<>(inv);
		this.prevRunes = new HashMap<>(runes);
		this.prevAmmoId = ammoId;
		this.prevAmmoQty = ammoQty;
		this.prevWeaponId = weaponId;
		this.prevWeaponQty = weaponQty;
		this.initialized = true;
	}

	private static Map<Integer, Integer> collectTotalRunes(
		Map<Integer, Integer> inv,
		Map<Integer, Integer> pouch)
	{
		Map<Integer, Integer> total = new HashMap<>();
		for (int runeId : RUNE_IDS)
		{
			int qty = inv.getOrDefault(runeId, 0);
			if (pouch != null)
			{
				qty += pouch.getOrDefault(runeId, 0);
			}
			if (qty > 0)
			{
				total.put(runeId, qty);
			}
		}
		return total;
	}

	private static String cleanName(String name, int itemId)
	{
		return (name != null && !name.trim().isEmpty()) ? name.trim() : ("Item #" + itemId);
	}

	public BurnSnapshot snapshot(double sessionSeconds, int kills)
	{
		List<SupplyUsage.Snapshot> items = new ArrayList<>(usageByKey.size());
		long foodUnits = 0;
		long potionDoses = 0;
		long ammoUnits = 0;
		long runeUnits = 0;
		for (SupplyUsage u : usageByKey.values())
		{
			SupplyUsage.Snapshot snap = u.toSnapshot();
			items.add(snap);
			switch (snap.getCategory())
			{
				case FOOD:
					foodUnits += snap.getUnitsConsumed();
					break;
				case POTION:
					potionDoses += snap.getUnitsConsumed();
					break;
				case AMMO:
					ammoUnits += snap.getUnitsConsumed();
					break;
				case RUNES:
					runeUnits += snap.getUnitsConsumed();
					break;
				default:
					break;
			}
		}
		items.sort(Comparator.comparingLong(SupplyUsage.Snapshot::getTotalCostGp).reversed()
			.thenComparingLong(SupplyUsage.Snapshot::getUnitsConsumed).reversed());

		long gpPerHour = 0L;
		long lootGpPerHour = 0L;
		long netProfitGp = totalLootGp - totalCostGp;
		long netProfitPerHour = 0L;
		if (sessionSeconds > 0)
		{
			double hours = Math.max(WeaponStats.SECONDS_PER_TICK, sessionSeconds) / 3600.0;
			if (totalCostGp > 0)
			{
				gpPerHour = Math.round(totalCostGp / hours);
			}
			if (totalLootGp > 0)
			{
				lootGpPerHour = Math.round(totalLootGp / hours);
			}
			if (netProfitGp != 0L)
			{
				netProfitPerHour = Math.round(netProfitGp / hours);
			}
		}
		long gpPerKill = kills > 0 ? totalCostGp / kills : 0L;
		long lootPerKill = kills > 0 ? totalLootGp / kills : 0L;
		long netPerKill = kills > 0 ? netProfitGp / kills : 0L;

		List<LootSnapshot> lootItems = new ArrayList<>(lootByItemId.size());
		for (LootItemEntry e : lootByItemId.values())
		{
			lootItems.add(new LootSnapshot(e.itemId, e.displayName, e.quantity, e.totalValueGp));
		}
		lootItems.sort(Comparator.comparingLong(LootSnapshot::getTotalValueGp).reversed()
			.thenComparingLong(LootSnapshot::getQuantity).reversed());

		return new BurnSnapshot(
			totalCostGp,
			gpPerHour,
			gpPerKill,
			totalLootGp,
			lootGpPerHour,
			lootPerKill,
			netProfitGp,
			netProfitPerHour,
			netPerKill,
			foodUnits,
			potionDoses,
			ammoUnits,
			runeUnits,
			Collections.unmodifiableList(items),
			Collections.unmodifiableList(lootItems)
		);
	}

	private static final class PotionTally
	{
		int totalDoses;
		int bestDoseVariant;
		int sampleItemId;
		long estimatedDosePriceGp;
		boolean anyVariantDropped;
	}

	@Value
	public static class BurnSnapshot
	{
		long totalCostGp;
		long gpPerHour;
		long gpPerKill;
		long totalLootGp;
		long lootGpPerHour;
		long lootPerKillGp;
		long netProfitGp;
		long netProfitPerHour;
		long netProfitPerKillGp;
		long foodEaten;
		long potionDoses;
		long ammoUsed;
		long runesUsed;
		List<SupplyUsage.Snapshot> items;
		List<LootSnapshot> lootItems;

		public boolean isEmpty()
		{
			return items.isEmpty() && lootItems.isEmpty();
		}
	}
}
