package com.combatbreakdown.logic;

import com.combatbreakdown.model.HitCategory;
import net.runelite.api.HitsplatID;

public final class HitsplatClassifier
{
	private HitsplatClassifier()
	{
	}

	public static HitCategory classifyOutgoing(int hitsplatType, int amount, boolean mine)
	{
		switch (hitsplatType)
		{
			case HitsplatID.BLOCK_ME:
				return HitCategory.DIRECT_MISS;
			case HitsplatID.DAMAGE_MAX_ME:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return amount > 0 ? HitCategory.DIRECT_MAX_HIT : HitCategory.DIRECT_MISS;
			case HitsplatID.DAMAGE_ME:
			case HitsplatID.DAMAGE_ME_CYAN:
			case HitsplatID.DAMAGE_ME_ORANGE:
			case HitsplatID.DAMAGE_ME_YELLOW:
			case HitsplatID.DAMAGE_ME_WHITE:
			case HitsplatID.DAMAGE_ME_POISE:
				return amount > 0 ? HitCategory.DIRECT_HIT : HitCategory.DIRECT_MISS;
			case HitsplatID.POISON:
				return amount > 0 ? HitCategory.STATUS_POISON : HitCategory.OTHER;
			case HitsplatID.VENOM:
				return amount > 0 ? HitCategory.STATUS_VENOM : HitCategory.OTHER;
			case HitsplatID.BURN:
				return amount > 0 ? HitCategory.STATUS_BURN : HitCategory.OTHER;
			case HitsplatID.BLEED:
				return amount > 0 ? HitCategory.STATUS_BLEED : HitCategory.OTHER;
			case HitsplatID.CORRUPTION:
				return amount > 0 ? HitCategory.STATUS_CORRUPTION : HitCategory.OTHER;
			case HitsplatID.DOOM:
				return amount > 0 ? HitCategory.STATUS_DOOM : HitCategory.OTHER;
			default:
				if (mine && amount > 0)
				{
					return HitCategory.DIRECT_HIT;
				}
				return HitCategory.OTHER;
		}
	}

	public static HitCategory classifyIncoming(int hitsplatType, int amount)
	{
		switch (hitsplatType)
		{
			case HitsplatID.BLOCK_ME:
			case HitsplatID.BLOCK_OTHER:
				return HitCategory.DIRECT_MISS;
			case HitsplatID.DAMAGE_MAX_ME:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return amount > 0 ? HitCategory.DIRECT_MAX_HIT : HitCategory.DIRECT_MISS;
			case HitsplatID.DAMAGE_ME:
			case HitsplatID.DAMAGE_OTHER:
			case HitsplatID.DAMAGE_ME_CYAN:
			case HitsplatID.DAMAGE_OTHER_CYAN:
			case HitsplatID.DAMAGE_ME_ORANGE:
			case HitsplatID.DAMAGE_OTHER_ORANGE:
			case HitsplatID.DAMAGE_ME_YELLOW:
			case HitsplatID.DAMAGE_OTHER_YELLOW:
			case HitsplatID.DAMAGE_ME_WHITE:
			case HitsplatID.DAMAGE_OTHER_WHITE:
			case HitsplatID.DAMAGE_ME_POISE:
			case HitsplatID.DAMAGE_OTHER_POISE:
				return amount > 0 ? HitCategory.DIRECT_HIT : HitCategory.DIRECT_MISS;
			case HitsplatID.POISON:
				return amount > 0 ? HitCategory.STATUS_POISON : HitCategory.OTHER;
			case HitsplatID.VENOM:
				return amount > 0 ? HitCategory.STATUS_VENOM : HitCategory.OTHER;
			case HitsplatID.BURN:
				return amount > 0 ? HitCategory.STATUS_BURN : HitCategory.OTHER;
			case HitsplatID.BLEED:
				return amount > 0 ? HitCategory.STATUS_BLEED : HitCategory.OTHER;
			case HitsplatID.CORRUPTION:
				return amount > 0 ? HitCategory.STATUS_CORRUPTION : HitCategory.OTHER;
			case HitsplatID.DOOM:
				return amount > 0 ? HitCategory.STATUS_DOOM : HitCategory.OTHER;
			case HitsplatID.HEAL:
				return amount > 0 ? HitCategory.HEAL : HitCategory.OTHER;
			case HitsplatID.PRAYER_DRAIN:
				return amount > 0 ? HitCategory.PRAYER_DRAIN : HitCategory.OTHER;
			default:
				return HitCategory.OTHER;
		}
	}
}
