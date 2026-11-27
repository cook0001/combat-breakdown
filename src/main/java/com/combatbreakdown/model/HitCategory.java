package com.combatbreakdown.model;

public enum HitCategory
{
	DIRECT_MISS,
	DIRECT_HIT,
	DIRECT_MAX_HIT,
	STATUS_POISON,
	STATUS_VENOM,
	STATUS_BURN,
	STATUS_BLEED,
	STATUS_CORRUPTION,
	STATUS_DOOM,
	HEAL,
	PRAYER_DRAIN,
	OTHER;

	public boolean isDirectOutgoing()
	{
		return this == DIRECT_MISS || this == DIRECT_HIT || this == DIRECT_MAX_HIT;
	}

	public boolean isStatusDamage()
	{
		return this == STATUS_POISON
			|| this == STATUS_VENOM
			|| this == STATUS_BURN
			|| this == STATUS_BLEED
			|| this == STATUS_CORRUPTION
			|| this == STATUS_DOOM;
	}
}
