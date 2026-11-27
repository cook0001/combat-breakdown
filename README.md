# Combat Breakdown

Real-time PvM and combat training analytics for RuneLite. Tracks active and continuous session DPS side-by-side, rolling 30s DPS sparklines, hit distribution, special attacks, combat XP/hr, per-weapon & A/B setup comparisons, damage taken and status damage, loot vs. supply burn profit, and persistent per-target fight history.

## Features

- **Exact Dual DPS Timers & Sparkline:** Tracks **Active DPS** (pauses immediately when engaged targets die and discards idle-tail ticks on disengage) alongside continuous **Session DPS**, **Rolling 30s DPS**, and a live **DPS Curve** sparkline chart, plus accuracy %, average landed hit, max hit, native max-hit splats, hit/miss streaks, average killing blow, and kills/hr.
- **Pin & Compare (Setup A vs. B):** Click **Pin A/B** to snapshot your current setup and reset live counters, then switch weapons or gear to see live `+DPS`, `+Acc%`, `+XP/hr`, and `+Net GP/hr` deltas against Setup A.
- **Special Attack & Combat XP Breakdown:** Detects Special Attack energy drops (`VarPlayerID.SA_ENERGY`) for separate Spec Accuracy, Avg/Spec, and Max Spec Hit stats, and tracks live Attack, Strength, Defence, Ranged, Magic, Hitpoints, and Slayer XP and XP/hr.
- **Hit Distribution, Defense & Per-Weapon Comparison:** 5-bucket hit distribution bar chart (`Miss` / `Low` / `Mid` / `High` / `Max`), direct vs. status damage taken, evade %, healing received, prayer drained, outgoing Poison/Venom/Burn/Bleed/Corruption/Doom damage, and per-weapon DPS/accuracy cards.
- **Loot Value vs. Supply Burn (Economy Tab):** Tracks NPC drops (`NpcLootReceived`) alongside food eaten, potion doses across `(1)`–`(4)` variants, ammo, stackable thrown weapons, cannonballs, and inventory + Rune Pouch runes to show **Net Profit/hr** and **Net Profit/kill**. Automatically ignores Bank, Deposit Box, GE, Trade, Shop, and Shared Bank interfaces as well as dropped items, and hides GE GP clutter on Ironman/UIM accounts (configurable).
- **Per-Target History & Compact HUD:** Persists per-NPC kills, average/fastest TTK, DPS, accuracy, max hit, damage taken, loot/kill, supply cost/kill, and net profit/kill with one-click **Copy** summary and **Copy CSV**, plus a compact top-left HUD with right-click **Pause / Resume** and **Reset Session**.

## Building & Running Locally

Requires **Java 11** (Eclipse Temurin 11 recommended).

```bash
./gradlew test
./gradlew run
```

To log in with a Jagex Account on the development client, follow the [RuneLite Using Jagex Accounts guide](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).
