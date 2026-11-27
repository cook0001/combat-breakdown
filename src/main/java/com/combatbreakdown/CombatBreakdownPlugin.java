package com.combatbreakdown;

import com.combatbreakdown.logic.CombatSessionTracker;
import com.combatbreakdown.logic.CombatSessionTracker.SessionSnapshot;
import com.combatbreakdown.logic.HistorySerializer;
import com.combatbreakdown.logic.HitsplatClassifier;
import com.combatbreakdown.logic.SupplyBurnTracker;
import com.combatbreakdown.logic.SupplyBurnTracker.BurnSnapshot;
import com.combatbreakdown.model.FightRecord;
import com.combatbreakdown.model.HitCategory;
import com.combatbreakdown.ui.CombatBreakdownPanel;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
	name = "Combat Breakdown",
	description = "Live active & session DPS, sparkline, hit distribution, per-weapon and A/B comparisons, spec & XP/hr, loot vs. supply burn, and per-target fight history",
	tags = {"combat", "dps", "pvm", "bossing", "slayer", "analytics", "meter", "accuracy", "supplies", "profit", "history"}
)
public class CombatBreakdownPlugin extends Plugin
{
	private static final String KEY_TARGET_HISTORY = "targetHistoryJson";

	private static final Set<Skill> TRACKED_COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK,
		Skill.STRENGTH,
		Skill.DEFENCE,
		Skill.RANGED,
		Skill.MAGIC,
		Skill.HITPOINTS,
		Skill.SLAYER
	);

	private static final int[] RUNE_POUCH_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1,
		VarbitID.RUNE_POUCH_TYPE_2,
		VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4,
		VarbitID.RUNE_POUCH_TYPE_5,
		VarbitID.RUNE_POUCH_TYPE_6
	};

	private static final int[] RUNE_POUCH_QTY_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1,
		VarbitID.RUNE_POUCH_QUANTITY_2,
		VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4,
		VarbitID.RUNE_POUCH_QUANTITY_5,
		VarbitID.RUNE_POUCH_QUANTITY_6
	};

	private static final Set<Integer> RUNE_POUCH_IDS = new HashSet<>();
	private static final Set<Integer> RUNE_POUCH_VARBITS = new HashSet<>();

	static
	{
		RUNE_POUCH_IDS.add(ItemID.BH_RUNE_POUCH);
		RUNE_POUCH_IDS.add(ItemID.BH_RUNE_POUCH_TROUVER);
		RUNE_POUCH_IDS.add(ItemID.DIVINE_RUNE_POUCH);
		RUNE_POUCH_IDS.add(ItemID.DIVINE_RUNE_POUCH_TROUVER);
		for (int v : RUNE_POUCH_TYPE_VARBITS)
		{
			RUNE_POUCH_VARBITS.add(v);
		}
		for (int v : RUNE_POUCH_QTY_VARBITS)
		{
			RUNE_POUCH_VARBITS.add(v);
		}
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	@Inject
	private CombatBreakdownConfig config;

	@Inject
	private com.combatbreakdown.overlay.CombatHudOverlay hudOverlay;

	private final CombatSessionTracker sessionTracker = new CombatSessionTracker();
	private final SupplyBurnTracker supplyTracker = new SupplyBurnTracker();
	private final Map<Skill, Integer> lastSkillXp = new EnumMap<>(Skill.class);
	private int lastSpecEnergy = -1;

	private HistorySerializer historySerializer;
	private NavigationButton navButton;
	private CombatBreakdownPanel panel;

	private volatile boolean ironmanAccount;
	private volatile boolean panelDirty;

	@Getter
	private volatile SessionSnapshot latestSession;

	@Getter
	private volatile BurnSnapshot latestBurn;

	private final SupplyBurnTracker.ItemMetaLookup itemMetaLookup = new SupplyBurnTracker.ItemMetaLookup()
	{
		@Override
		public String getName(int itemId)
		{
			if (itemId <= 0)
			{
				return "";
			}
			ItemComposition comp = itemManager.getItemComposition(itemId);
			return comp != null && comp.getName() != null ? comp.getName() : "";
		}

		@Override
		public long getUnitPrice(int itemId)
		{
			return itemId > 0 ? Math.max(0L, itemManager.getItemPrice(itemId)) : 0L;
		}
	};

	@Provides
	CombatBreakdownConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CombatBreakdownConfig.class);
	}

	@Override
	protected void startUp()
	{
		historySerializer = new HistorySerializer(gson);
		sessionTracker.setIdleTimeoutTicks(config.idleTimeoutTicks());

		if (config.persistTargetHistory())
		{
			String saved = configManager.getConfiguration(CombatBreakdownConfig.GROUP, KEY_TARGET_HISTORY);
			sessionTracker.loadPersistedSummaries(historySerializer.deserialize(saved));
		}

		publishSnapshots();

		panel = new CombatBreakdownPanel(this, configManager, itemManager);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/combatbreakdown/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Combat Breakdown")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(hudOverlay);

		clientThread.invokeLater(this::syncAccountAndEquipmentOnClientThread);
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		overlayManager.remove(hudOverlay);
		if (panel != null)
		{
			panel.dispose();
			panel = null;
		}
		synchronized (this)
		{
			sessionTracker.resetSessionOnly();
			sessionTracker.clearPinnedComparison();
			supplyTracker.reset();
			lastSkillXp.clear();
			lastSpecEnergy = -1;
		}
		latestSession = null;
		latestBurn = null;
		ironmanAccount = false;
		panelDirty = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			synchronized (this)
			{
				supplyTracker.invalidateBaseline();
				lastSkillXp.clear();
				lastSpecEnergy = -1;
			}
		}
		else if (state == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::syncAccountAndEquipmentOnClientThread);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CombatBreakdownConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		synchronized (this)
		{
			sessionTracker.setIdleTimeoutTicks(config.idleTimeoutTicks());
		}
		publishSnapshots();
		notifyPanel();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (!TRACKED_COMBAT_SKILLS.contains(skill))
		{
			return;
		}
		int newXp = event.getXp();
		synchronized (this)
		{
			Integer prev = lastSkillXp.put(skill, newXp);
			if (prev != null && newXp > prev)
			{
				sessionTracker.recordSkillXp(skill, newXp - prev);
				panelDirty = true;
			}
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (event.getNpc() == null || event.getItems() == null || event.getItems().isEmpty())
		{
			return;
		}
		long totalDropGp = 0L;
		synchronized (this)
		{
			for (ItemStack stack : event.getItems())
			{
				if (stack == null || stack.getId() <= 0 || stack.getQuantity() <= 0)
				{
					continue;
				}
				int canonicalId = itemManager.canonicalize(stack.getId());
				totalDropGp += supplyTracker.recordLootDrop(canonicalId, stack.getQuantity(), itemMetaLookup);
			}
			if (totalDropGp > 0)
			{
				sessionTracker.recordNpcLootValue(event.getNpc().getName(), totalDropGp);
				if (config.persistTargetHistory())
				{
					persistHistoryLocked();
				}
			}
		}
		publishSnapshots();
		panelDirty = true;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.trackSupplyBurn())
		{
			return;
		}
		String option = event.getMenuOption();
		if (option == null)
		{
			return;
		}
		if ("Eat".equalsIgnoreCase(option) || "Drink".equalsIgnoreCase(option))
		{
			int rawId = event.getItemId();
			int canonicalId = rawId > 0 ? itemManager.canonicalize(rawId) : -1;
			synchronized (this)
			{
				supplyTracker.recordConsumeAction(option, canonicalId, client.getTickCount());
			}
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		if (!config.trackSupplyBurn() || event.getTile() == null || event.getItem() == null)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		WorldPoint playerLoc = local.getWorldLocation();
		WorldPoint tileLoc = event.getTile().getWorldLocation();
		if (playerLoc != null && playerLoc.equals(tileLoc))
		{
			int canonicalId = itemManager.canonicalize(event.getItem().getId());
			synchronized (this)
			{
				supplyTracker.recordDroppedOnSelfTile(canonicalId, client.getTickCount());
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();
		Hitsplat splat = event.getHitsplat();
		Player local = client.getLocalPlayer();
		if (actor == null || splat == null || local == null)
		{
			return;
		}

		int tick = client.getTickCount();
		if (actor == local)
		{
			HitCategory cat = HitsplatClassifier.classifyIncoming(splat.getHitsplatType(), splat.getAmount());
			if (cat != HitCategory.OTHER)
			{
				synchronized (this)
				{
					sessionTracker.onIncomingHitsplat(cat, splat.getAmount(), tick);
				}
				publishSnapshots();
				panelDirty = true;
			}
			return;
		}

		if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			HitCategory cat = HitsplatClassifier.classifyOutgoing(splat.getHitsplatType(), splat.getAmount(), splat.isMine());
			if (cat != HitCategory.OTHER)
			{
				synchronized (this)
				{
					sessionTracker.onOutgoingHitsplat(
						npc.getIndex(),
						npc.getName(),
						npc.getCombatLevel(),
						cat,
						splat.getAmount(),
						tick
					);
				}
				publishSnapshots();
				panelDirty = true;
			}
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		FightRecord completed;
		synchronized (this)
		{
			completed = sessionTracker.onNpcDeath(npc.getIndex(), client.getTickCount(), System.currentTimeMillis());
			if (completed != null && config.persistTargetHistory())
			{
				persistHistoryLocked();
			}
		}
		if (completed != null)
		{
			publishSnapshots();
			panelDirty = true;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id != InventoryID.INV && id != InventoryID.WORN)
		{
			return;
		}
		if (id == InventoryID.WORN)
		{
			syncWeaponOnClientThread();
		}
		if (config.trackSupplyBurn())
		{
			processSupplyDeltaOnClientThread();
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varbit = event.getVarbitId();
		if (varbit == VarbitID.IRONMAN)
		{
			ironmanAccount = client.getVarbitValue(VarbitID.IRONMAN) != 0;
			panelDirty = true;
		}
		else if (event.getVarpId() == VarPlayerID.SA_ENERGY)
		{
			int nowSpec = client.getVarpValue(VarPlayerID.SA_ENERGY);
			synchronized (this)
			{
				if (lastSpecEnergy >= 0 && nowSpec < lastSpecEnergy)
				{
					sessionTracker.recordSpecialAttackUsed(client.getTickCount());
					panelDirty = true;
				}
				lastSpecEnergy = nowSpec;
			}
		}
		else if (config.trackSupplyBurn() && RUNE_POUCH_VARBITS.contains(varbit))
		{
			processSupplyDeltaOnClientThread();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		boolean activeBefore;
		boolean started;
		synchronized (this)
		{
			activeBefore = sessionTracker.isInActiveCombat();
			sessionTracker.onGameTick(client.getTickCount());
			started = sessionTracker.snapshot().isSessionStarted();
		}
		if (started || activeBefore || panelDirty)
		{
			publishSnapshots();
			panelDirty = false;
			notifyPanel();
		}
	}

	public boolean shouldDisplayGpValues()
	{
		return !(ironmanAccount && config.hideGpOnIronman());
	}

	public boolean isIronmanAccount()
	{
		return ironmanAccount;
	}

	public void togglePaused()
	{
		synchronized (this)
		{
			sessionTracker.setPaused(!sessionTracker.isPaused());
		}
		publishSnapshots();
		notifyPanel();
	}

	public void pinCurrentRunAndReset()
	{
		synchronized (this)
		{
			SessionSnapshot s = sessionTracker.snapshot();
			if (!s.isSessionStarted())
			{
				return;
			}
			BurnSnapshot b = supplyTracker.snapshot(s.getSessionSeconds(), s.getSessionKills());
			sessionTracker.pinCurrentComparison(b.getGpPerHour(), b.getNetProfitPerHour());
			sessionTracker.resetSessionOnly();
			supplyTracker.reset();
		}
		publishSnapshots();
		notifyPanel();
		clientThread.invokeLater(this::syncAccountAndEquipmentOnClientThread);
	}

	public void unpinComparison()
	{
		synchronized (this)
		{
			sessionTracker.clearPinnedComparison();
		}
		publishSnapshots();
		notifyPanel();
	}

	public void resetSession()
	{
		synchronized (this)
		{
			sessionTracker.resetSessionOnly();
			supplyTracker.reset();
		}
		publishSnapshots();
		notifyPanel();
		clientThread.invokeLater(this::syncAccountAndEquipmentOnClientThread);
	}

	public void clearHistory()
	{
		synchronized (this)
		{
			sessionTracker.clearPersistedHistory();
			configManager.unsetConfiguration(CombatBreakdownConfig.GROUP, KEY_TARGET_HISTORY);
		}
		publishSnapshots();
		notifyPanel();
	}

	public String buildSummaryText()
	{
		synchronized (this)
		{
			SessionSnapshot s = sessionTracker.snapshot();
			BurnSnapshot b = supplyTracker.snapshot(s.getSessionSeconds(), s.getSessionKills());
			return sessionTracker.formatSummaryText(b.getTotalCostGp(), b.getTotalLootGp(), shouldDisplayGpValues());
		}
	}

	public String buildHistoryCsv()
	{
		synchronized (this)
		{
			return sessionTracker.formatHistoryCsv();
		}
	}

	private void syncAccountAndEquipmentOnClientThread()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		ironmanAccount = client.getVarbitValue(VarbitID.IRONMAN) != 0;
		synchronized (this)
		{
			lastSpecEnergy = client.getVarpValue(VarPlayerID.SA_ENERGY);
			for (Skill skill : TRACKED_COMBAT_SKILLS)
			{
				lastSkillXp.put(skill, client.getSkillExperience(skill));
			}
		}
		syncWeaponOnClientThread();
		if (config.trackSupplyBurn())
		{
			processSupplyDeltaOnClientThread();
		}
		publishSnapshots();
		notifyPanel();
	}

	private void syncWeaponOnClientThread()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			synchronized (this)
			{
				sessionTracker.updateEquippedWeapon(-1, "Unarmed");
			}
			return;
		}
		Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null || weapon.getId() <= 0 || weapon.getQuantity() <= 0)
		{
			synchronized (this)
			{
				sessionTracker.updateEquippedWeapon(-1, "Unarmed");
			}
			return;
		}
		int canonicalId = itemManager.canonicalize(weapon.getId());
		ItemComposition comp = itemManager.getItemComposition(canonicalId);
		String name = comp != null && comp.getName() != null ? comp.getName() : ("Weapon #" + canonicalId);
		synchronized (this)
		{
			sessionTracker.updateEquippedWeapon(canonicalId, name);
		}
	}

	private void processSupplyDeltaOnClientThread()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
		ItemContainer wornContainer = client.getItemContainer(InventoryID.WORN);
		Map<Integer, Integer> invCounts = readContainer(invContainer);

		int ammoId = -1;
		int ammoQty = 0;
		int weaponId = -1;
		int weaponQty = 0;
		if (wornContainer != null)
		{
			Item ammo = wornContainer.getItem(EquipmentInventorySlot.AMMO.getSlotIdx());
			if (ammo != null && ammo.getId() > 0 && ammo.getQuantity() > 0)
			{
				ammoId = itemManager.canonicalize(ammo.getId());
				ammoQty = ammo.getQuantity();
			}
			Item wep = wornContainer.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
			if (wep != null && wep.getId() > 0 && wep.getQuantity() > 0)
			{
				weaponId = itemManager.canonicalize(wep.getId());
				weaponQty = wep.getQuantity();
			}
		}

		Map<Integer, Integer> pouchRunes = hasRunePouch(invCounts) ? readRunePouchRunes() : Collections.emptyMap();
		boolean storageOpen = isStorageUiOpen();
		int tick = client.getTickCount();

		long deltaGp;
		synchronized (this)
		{
			deltaGp = supplyTracker.onStateUpdate(
				invCounts,
				ammoId,
				ammoQty,
				weaponId,
				weaponQty,
				pouchRunes,
				storageOpen,
				sessionTracker.isInActiveCombat(),
				sessionTracker.isPaused(),
				tick,
				itemMetaLookup
			);
			if (deltaGp > 0)
			{
				sessionTracker.addSupplyCostGp(deltaGp);
			}
		}
		if (deltaGp > 0)
		{
			publishSnapshots();
			panelDirty = true;
		}
	}

	private boolean isStorageUiOpen()
	{
		return isWidgetVisible(InterfaceID.Bankmain.UNIVERSE)
			|| isWidgetVisible(InterfaceID.BankDepositbox.UNIVERSE)
			|| isWidgetVisible(InterfaceID.GeOffers.UNIVERSE)
			|| isWidgetVisible(InterfaceID.Trademain.UNIVERSE)
			|| isWidgetVisible(InterfaceID.Shopmain.UNIVERSE)
			|| isWidgetVisible(InterfaceID.SharedBank.UNIVERSE);
	}

	private boolean isWidgetVisible(int packedWidgetId)
	{
		Widget widget = client.getWidget(packedWidgetId);
		return widget != null && !widget.isHidden();
	}

	private Map<Integer, Integer> readContainer(ItemContainer container)
	{
		if (container == null || container.getItems() == null)
		{
			return Collections.emptyMap();
		}
		Map<Integer, Integer> counts = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			int canonicalId = itemManager.canonicalize(item.getId());
			counts.merge(canonicalId, item.getQuantity(), Integer::sum);
		}
		return counts;
	}

	private boolean hasRunePouch(Map<Integer, Integer> invCounts)
	{
		for (int pouchId : RUNE_POUCH_IDS)
		{
			if (invCounts.getOrDefault(pouchId, 0) > 0)
			{
				return true;
			}
		}
		return false;
	}

	private Map<Integer, Integer> readRunePouchRunes()
	{
		EnumComposition runeEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runeEnum == null)
		{
			return Collections.emptyMap();
		}
		Map<Integer, Integer> runes = new HashMap<>(8);
		for (int i = 0; i < RUNE_POUCH_TYPE_VARBITS.length; i++)
		{
			int typeId = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[i]);
			int qty = client.getVarbitValue(RUNE_POUCH_QTY_VARBITS[i]);
			if (typeId <= 0 || qty <= 0)
			{
				continue;
			}
			int runeItemId = runeEnum.getIntValue(typeId);
			if (runeItemId > 0)
			{
				runes.merge(runeItemId, qty, Integer::sum);
			}
		}
		return runes;
	}

	private void persistHistoryLocked()
	{
		String json = historySerializer.serialize(sessionTracker.getTargetSummariesSorted());
		configManager.setConfiguration(CombatBreakdownConfig.GROUP, KEY_TARGET_HISTORY, json);
	}

	private void publishSnapshots()
	{
		synchronized (this)
		{
			SessionSnapshot s = sessionTracker.snapshot();
			BurnSnapshot b = supplyTracker.snapshot(s.getSessionSeconds(), s.getSessionKills());
			latestSession = s;
			latestBurn = b;
		}
	}

	private void notifyPanel()
	{
		CombatBreakdownPanel current = panel;
		if (current != null)
		{
			SwingUtilities.invokeLater(current::refresh);
		}
	}
}
