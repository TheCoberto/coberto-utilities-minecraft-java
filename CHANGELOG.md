# Changelog

Fabric mod versions (0.x) and the Paper plugin (`paper-plugin/`, "Paper x.y") are listed together, newest first.

## Paper 1.12.0
- **Leaderboard** (new): a Discord channel with one message that keeps itself up to date, showing the top 5 players in 💰 Richest, ⚔️ Player Kills, 🎯 Best KDR, 💀 Most Deaths, 🏡 Claim Blocks, ⏱️ Playtime, 🧟 Mob Kills, 🏃 Distance Traveled and 🎣 Fish Caught, with server totals on top. Counts offline players too.
- Optional thread that announces every new #1 ("👑 X took #1 in Richest from Y").
- Players need 30 minutes of playtime to be ranked; staff can be hidden. Categories can be turned off.
- Admin: `/leaderboard status|refresh|repost|reload|webhook <url>`; settings in `leaderboard.yml`.

## Paper 1.11.0
- **Market Board** (new): the shop's Discord channel, replacing the shop-updates and shop-transactions channels.
  - A board message that keeps itself up to date: today's trades and totals, most bought and sold items, top traders, and prices dynamic pricing has pushed down or up.
  - **New in the Shop** posts by themselves when items are added, removed or repriced (on start and whenever the shop reloads, in-game editor included). `/market note <text>` puts your own words on top of the next one.
  - `/market announce <text>` posts a Shop Announcement.
  - Trade feed in a thread: every trade with the player's name, gathered into one message a minute, repeat trades of the same item merged.
- Admin: `/market status|refresh|repost|reload|webhook <url>`; settings in `market-board.yml`.

## Paper 1.10.0
- **Kingdom Board** (new): one Discord channel with a **Factions** message and a **Clans** message that update themselves in place, replacing the daily faction and clan posts. Factions show members (online ones in bold); clans show leaders, members, kills, deaths, KDR, bank, allies, rivals and founding date, biggest clan first.
- Optional event feed in one shared thread, or separate Faction Changes and Clan Changes threads: faction joins and switches, new and disbanded clans, members joining and leaving, new leaders, alliances and rivalries.
- Admin: `/kingdomboard status|refresh|repost|reload|webhook <url>`; settings in `kingdom-board.yml`.

## Paper 1.9.1
- **Classes**: Vampire and Miner Night Vision no longer flickers. It stays steady while it applies and switches off cleanly at dawn (Vampire) or above Y 50 (Miner). Night Vision potions are left alone.

## Paper 1.9.0
- **Compass Menu on Java**: Java players can now right-click a compass too, and get a chest menu with the same buttons as the Bedrock form (each button has a Java `item:` icon in `config.yml`). Players who can use WorldEdit's compass wand (operators) sneak + right-click for the menu, so the wand still works. Lodestone linking is unaffected.

## Paper 1.8.0
- **Classes are open to everyone**: `/class` opens the class menu for all players (Bedrock players get a native form). After picking, you wait **5 minutes** before changing again (your first pick is free; operators don't wait). `/setclass` stays operator-only.
- **Bedrock Menu**: new **Pick a Class** button.
- **Rebalanced classes** so none is too strong, especially in PvP:
  - On-hit abilities (Pyro, Paladin, Plaguebearer, Necromancer, Vampire) only work on fully charged, direct hits, not spam-clicks or sweeps.
  - Warrior: Strength I only (lost Haste). Acrobat: Speed I (was II). Ghoul: Strength I at night (was II), no more night Regeneration.
  - Paladin: lost permanent Resistance; undead now hit them 25% softer. Sentinel: Resistance II while sneaking (was III), thorns 1 damage (was 2).
  - Miner: Haste I, Haste II only below Y 50. Tank: Resistance I (was II) plus half knockback.
  - Plaguebearer: Poison I for 3s (was Poison II for 4s). Necromancer: Wither 3s (was 4s), Absorption 15s (was 30s). Pyro: fire 3s (was 4s).
  - Bard: Regeneration goes to nearby players only, not the bard. Druid: regenerates only after 5 seconds without being hurt. Vampire: lost Strength at night.

## Paper 1.7.2
- **Bedrock Menu**: new **Hotbar Refill On/Off** (`/refill`) and **Best Tools On/Off** (`/bt`) buttons.

## Paper 1.7.1
- Chest sorting (Chestsort+) no longer scrambles plugin menus (server menus, shops, the Bedrock Menu and other GUIs) when they open. Real chests, barrels, shulker boxes, ender chests, minecarts and pack animals still sort.

## Paper 1.7.0
- **Bedrock Menu**: Bedrock players right-click a compass to open a Bedrock menu with a button for each handy command (What's New, Spawn, Home, Balance, PvP, Unload, Dump, Chest Sorting, Lumberjack, Slots). The buttons, their pictures and the title are set in `config.yml` (`bedrock_menu`). Needs Floodgate; Java players and lodestone linking are unaffected.

## Paper 1.6.0
- **Lumberjack**: chop a tree with an axe and the whole tree comes down. Each log is broken as if you broke it (claims and regions respected, normal drops, the axe wears per log and stops one hit before breaking). Sneak to chop one log; `/lumberjack` (or `/lj`) turns it on or off for you. Only natural trees: logs players placed are never chained, so log builds are safe.

## Paper 1.5.3
- The Prank Button is called the **Odd Button** everywhere players see it (item name, chat prefix, setup menu), so pushing one stays a surprise.

## Paper 1.5.2
- Prank Button chat messages start with "Odd Button:".

## Paper 1.5.1
- Prank Buttons are protected: only admins can break one or the block it hangs on, and explosions, pistons, water and lava can't remove one.
- No prank farming: killer chickens drop no loot, XP or eggs and can't be bred; anvils and cobwebs from pranks drop nothing; anvil-shower anvils vanish after 15 seconds (was 30).

## Paper 1.5.0
- A block placed where WorldGuard or GriefPrevention don't allow building is given back instead of vanishing (worst for Bedrock players).

## Paper 1.4.0
- **Area Titles**: `/areatitle create <name> <radius>` makes an area; players see its title when they walk in (once per visit, with a cooldown, default 5 minutes). Saved in `areas.yml`.
- Music Selector: the Tears and Lava Chicken discs.

## Paper 1.3.0
- `/unload` puts your items into nearby chests that already hold the same item; `/dump` empties your inventory into nearby chests. Hotbar, armor and offhand stay put, only chests you could open are used (claims and regions respected), within 10 blocks.

## 0.8.1
- Works with Fabric Loader 0.19 too (it needed 0.20.1).

## 0.8.0
- Now for Minecraft 26.2 (Fabric). No longer works on 1.21.1.
- Settings saved by the 1.21.1 version (zones, crates, classes, music, rewards, mob money) don't carry over.

## Paper 1.2.1
- `/cleanup` also clears mobs named by plugins (like Skeleton Variants); only name-tagged mobs are kept.

## Paper 1.2.0
- **World Cleanup**: `/cleanup` clears hostile mobs, arrows, XP orbs and dropped items in every world. Name-tagged mobs and Citizens NPCs are left alone.

## Paper 1.1.0
- Activity Rewards (Money through Vault, AFK through Essentials), Loot Crates, the Music Selector and the Prank Button.

## Paper 1.0.0
- First Paper plugin version, starting with Classes (`/class`, `/setclass`).

## 0.7.0
- The Prank Button.

## 0.6.0
- The Music Selector.

## 0.5.0
- Classes.

## 0.4.0
- Adventure Zones.

## 0.3.0
- Loot crates (Crate, Spooky Crate, Holiday Crate) and `/crates`.

## 0.2.0
- Mob Money, and Activity Rewards with AFK detection.

## 0.1.0
- First Java (Fabric 1.21.1) version of Coberto's Utilities: creeper anti-grief and gamemode shortcuts.
