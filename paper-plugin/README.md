# Coberto's Utilities (Paper plugin)

Server-side version of Coberto's Utilities for Paper servers. Players don't need any mods, and it works for Bedrock players joining through Geyser.

It has **Classes**, **Activity Rewards**, **Loot Crates**, the **Music Selector**, the **Prank Button** and **World Cleanup**, working like the Fabric mod. Money comes from the server economy through [Vault](https://www.spigotmc.org/resources/vault.34315/) (the same balance as `/bal`), so an economy plugin like EssentialsX is needed for Activity Rewards and song prices.

Left out because Paper servers already have plugins for them: gamemode shortcuts and AFK detection (EssentialsX), Mob Money (Jobs), creeper anti-grief and Adventure Zones (WorldGuard).

## Special blocks
Players have no mods, so the Fabric mod's new blocks are vanilla blocks placed from special items. Operators get them with `/utilitiesgive <block> [player] [amount]`:

| Block | Looks like |
| --- | --- |
| `crate` | Barrel |
| `spooky_crate` | Carved pumpkin |
| `holiday_crate` | Snow block |
| `music_selector` | Jukebox |
| `prank_button` | Crimson button |

Change the looks under `blocks` in `config.yml`. Special blocks can't be blown up or moved by pistons. Breaking a Music Selector gives the item back; breaking a crate gives nothing. Only admins (`cobertoutilities.admin`) can break a Prank Button or the block it hangs on, so players who find one out in the world can't take it; water and lava can't wash it away either.

## Classes
Players get one of 15 classes, the same as in the Fabric mod and the Bedrock add-on: Warrior, Acrobat, Ghoul, Pyro, Aquarian, Glider, Paladin, Sentinel, Miner, Tank, Plaguebearer, Bard, Druid, Necromancer and Vampire. One class at a time; it's saved on the player and kept through logging out and dying.

- `/class` opens a menu to pick your own class.
- `/setclass <players> <class>` sets anyone's class (`none` removes it). It works from the console, command blocks (`/setclass @p pyro`) and Citizens NPCs: `/npc command add setclass <p> pyro` makes an NPC that turns whoever clicks it into a Pyro.

## Activity Rewards
Players earn Money for every so many minutes they're active. Off by default. The timer pauses while EssentialsX has a player marked AFK.
- `/activity true|false` turns it on or off. With no value it shows the settings.
- `/activityreward <amount> <minutes>` sets the reward (default 100 every 10 minutes).
- `/activitymessage <message>` sets the message. `{amount}`, `{player}`, `{minutes}` and `&` colors work. `reset` goes back to the default.
- `/activitysound <sound>` sets the sound (default `entity.player.levelup`), or `none`.

## Loot Crates
Hide crates around the map for events. Using one pops out random loot and the crate disappears. Finding an Elytra or Heavy Core is announced to everyone. `/crates` lists them in chat; click one to see its loot with each item's chance, then click to change or remove items. Or type:
- `/crates <crate> add <item> <weight> [min] [max]`, `/crates <crate> remove <item>`, `/crates <crate> items <least> <most>`, `/crates <crate> reset`.

## Music Selector
Players use it to pay and queue a song. It plays like a jukebox, heard only nearby, with a floating "Now Playing" label. Each selector has its own queue; each player waits 2:30 between songs. Operators get Settings, Skip and Stop in the menu, or use `/music` (price, per-song prices, cooldown, queue size, which songs can be queued, and an autoplay loop) and `/musicskip`. The songs are the vanilla music discs.

## Prank Button
Whoever pushes it gets pranked: an anvil shower (the anvils vanish after 15 seconds), a launch into the sky, lightning, a cobweb trap, a killer chicken swarm, fake TNT, a teleport, a scrambled hotbar, spooky darkness, or a random one. The player who placed it (or an operator) sneaks and uses it with an empty hand to change the sign, the prank and its settings in clickable chat, or uses `/prankbutton <x y z> <setting> <value>`. Nothing a prank leaves behind drops anything (killer chickens, their eggs, anvils, cobwebs), so a Prank Button can't be farmed.

## World Cleanup
`/cleanup` clears hostile mobs (zombies, skeletons, creepers, spiders, endermen, slimes, witches, pillagers and more), arrows, XP orbs and dropped items in every world, to cut lag. Everyone sees `[Server] Clearing entities...` and then `[Server] MOBs & dropped items cleared!`. Name-tagged mobs and Citizens NPCs are left alone, and cleared mobs drop nothing. Works from command blocks and the console too, like the World Cleanup mod.

## Kingdom Board (Discord)
Two Discord messages, one for **Factions** and one for **Clans**, that a webhook keeps editing in place, so the channel always shows the current standings. Factions come from the LuckPerms `faction` meta (or the faction's groups); clans from SimpleClans (name, leaders, members, kills, deaths, KDR, bank, allies, rivals, founding date). It checks every 2 minutes and only contacts Discord when something changed. With a thread set, it also posts what changed: faction joins and switches, new and disbanded clans, members joining and leaving, new leaders, alliances and rivalries.

Setup: make a webhook for the channel, run `/kingdomboard webhook <url>` in the console (stored in `kingdom-board-data.yml`, kept out of `kingdom-board.yml` because it's a secret), and pin the two messages. Settings, factions and the events thread are in `kingdom-board.yml`. `/kingdomboard status|refresh|repost|reload` (admin).

## Market Board (Discord)
The shop's Discord channel, for EconomyShopGUI. A board message a webhook keeps editing: today's trades and totals, most bought and sold items, top traders, and the items dynamic pricing has pushed down or up (compared with the prices in the shop files). **New in the Shop** posts go out by themselves when items are added, removed or repriced in the shop files (checked on start and whenever the shop reloads); `/market note <text>` adds your own words on top of the next one, and `/market announce <text>` posts a Shop Announcement. With a thread set, every trade goes to a feed there, gathered into one message a minute.

Setup: make a webhook for the channel, run `/market webhook <url>` in the console (stored in `market-board-data.yml`), put the trade thread's ID in `market-board.yml`, and pin the board. EconomyShopGUI is read through its API by name, so it's optional at build and run time.

## Permissions
`cobertoutilities.admin` (op by default) for `/utilitiesgive`, `/activity*`, `/crates`, `/music`, `/musicskip`, `/cleanup`, `/kingdomboard`, `/market` and changing any Prank Button. `cobertoutilities.classes.admin` (op by default) for `/class` and `/setclass`.

If another plugin uses the same command name, use the `cobertoutilities:` prefix, like `/cobertoutilities:crates`.

## Build
```
./gradlew build
```
Needs JDK 21 or newer (JDK 25 is fine). The jar ends up in `build/libs/`.
