package com.combatbreakdown.model;

import lombok.Getter;

@Getter
public enum SupplyCategory
{
	FOOD("Food", "eaten"),
	POTION("Potions", "doses"),
	AMMO("Ammo & Cannon", "used"),
	RUNES("Runes", "cast");

	private final String displayName;
	private final String unitLabel;

	SupplyCategory(String displayName, String unitLabel)
	{
		this.displayName = displayName;
		this.unitLabel = unitLabel;
	}
}
