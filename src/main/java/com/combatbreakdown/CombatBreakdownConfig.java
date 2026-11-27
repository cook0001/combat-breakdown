package com.combatbreakdown;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(CombatBreakdownConfig.GROUP)
public interface CombatBreakdownConfig extends Config
{
	String GROUP = "combatbreakdown";

	enum HudVisibility
	{
		IN_COMBAT_ONLY("In combat only"),
		WHILE_SESSION_ACTIVE("While session active"),
		OFF("Hidden");

		private final String label;

		HudVisibility(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigSection(
		name = "HUD Overlay",
		description = "Compact on-screen combat analytics HUD",
		position = 0
	)
	String hudSection = "hudSection";

	@ConfigSection(
		name = "Session & Supplies",
		description = "Combat timer, supply burn tracking, and history persistence",
		position = 1
	)
	String sessionSection = "sessionSection";

	@ConfigItem(
		keyName = "hudVisibility",
		name = "HUD visibility",
		description = "Choose when the on-screen Combat Breakdown HUD is displayed",
		section = hudSection,
		position = 0
	)
	default HudVisibility hudVisibility()
	{
		return HudVisibility.IN_COMBAT_ONLY;
	}

	@ConfigItem(
		keyName = "hudShowDamageTaken",
		name = "Show damage taken in HUD",
		description = "Include damage taken and healing received in the compact HUD",
		section = hudSection,
		position = 1
	)
	default boolean hudShowDamageTaken()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hudShowSupplyBurn",
		name = "Show profit / supply burn in HUD",
		description = "Include net profit/hr or supply burn/hr (or item counts on Ironman) in the HUD",
		section = hudSection,
		position = 2
	)
	default boolean hudShowSupplyBurn()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hudShowCombatXp",
		name = "Show combat XP/hr in HUD",
		description = "Include total combat XP/hr in the compact on-screen HUD",
		section = hudSection,
		position = 3
	)
	default boolean hudShowCombatXp()
	{
		return false;
	}

	@Range(min = 3, max = 30)
	@ConfigItem(
		keyName = "idleTimeoutTicks",
		name = "Active combat idle timeout (ticks)",
		description = "Game ticks (0.6s each) without combat hits before the Active DPS timer pauses",
		section = sessionSection,
		position = 0
	)
	default int idleTimeoutTicks()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "trackSupplyBurn",
		name = "Track supply consumption",
		description = "Track food eaten, potion doses drunk, and ammo/runes/cannonballs spent in combat",
		section = sessionSection,
		position = 1
	)
	default boolean trackSupplyBurn()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideGpOnIronman",
		name = "Hide GE GP values on Ironman",
		description = "Show supply counts without GE GP totals when logged into an Ironman or UIM account",
		section = sessionSection,
		position = 2
	)
	default boolean hideGpOnIronman()
	{
		return true;
	}

	@ConfigItem(
		keyName = "persistTargetHistory",
		name = "Save per-target history",
		description = "Persist aggregated per-NPC kill count, DPS, and fastest kill times across sessions",
		section = sessionSection,
		position = 3
	)
	default boolean persistTargetHistory()
	{
		return true;
	}
}
