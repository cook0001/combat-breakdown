package com.combatbreakdown.model;

import lombok.Value;

@Value
public class PinnedComparison
{
	String label;
	String primaryWeapon;
	double activeDps;
	double sessionDps;
	double accuracyPercent;
	int maxHit;
	double killsPerHour;
	double combatXpPerHour;
	long supplyBurnPerHour;
	long netProfitPerHour;
}
