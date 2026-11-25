# Changelog

Fabric mod versions (0.x) and the Paper plugin (`paper-plugin/`, "Paper x.y") are listed together, newest first.

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
