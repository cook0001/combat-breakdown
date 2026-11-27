# Combat Breakdown

Real-time PvM and combat training analytics for RuneLite. Tracks active and continuous session DPS side-by-side, hit distribution, per-weapon comparisons, damage taken and status damage, combat supply burn rates, and persistent per-target fight history.

## Features

- **Dual DPS Timers:** Tracks **Active DPS** (auto-pauses after a configurable idle gap between hits) alongside **Session DPS** (continuous from first hit until Pause or Reset), plus total damage, accuracy %, average landed hit, max hit, native max-hit splat counts, and kills/hr.
- **Hit Distribution & Per-Weapon Comparison:** Visual bar chart splitting swings into Miss, Low, Mid, High, and Max buckets, paired with per-weapon DPS, accuracy, average hit, and swing count cards so you can compare weapons live across a trip.
- **Defense, Healing & Status Damage:** Separates direct and status damage taken, blocked/evaded hit %, healing received, prayer drained, and outgoing Poison, Venom, Burn, Bleed, Corruption, and Doom damage on engaged targets.
- **Supply Burn Rate (Bank & Drop Safe):** Tracks food eaten, potion doses drunk across `(1)`–`(4)` variants, equipped ammo, stackable thrown weapons, cannonballs, and inventory + Rune Pouch runes spent in combat. Automatically pauses while Bank, Deposit Box, Grand Exchange, Trade, Shop, or Shared Bank interfaces are open, ignores dropped items on your tile, and adapts cleanly for Ironman/UIM accounts (optionally hiding GE GP values).
- **Per-Target History & Exports:** Records kills, average and fastest kill time (TTK), average DPS, accuracy, max hit, damage taken, and supply cost per kill for every NPC/boss, with one-click **Copy** summary and **Copy CSV** export.
- **Compact On-Screen HUD:** Optional top-left HUD (`In combat only`, `While session active`, or `Hidden`) with right-click **Pause / Resume** and **Reset Session**.

## Building & Running Locally

Requires **Java 11** (Eclipse Temurin 11 recommended).

```bash
./gradlew test
./gradlew run
```

To log in with a Jagex Account on the development client, follow the [RuneLite Using Jagex Accounts guide](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).
