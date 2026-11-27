package com.combatbreakdown;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CombatBreakdownPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CombatBreakdownPlugin.class);
		RuneLite.main(args);
	}
}
