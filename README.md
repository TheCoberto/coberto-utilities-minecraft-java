# Coberto's Utilities
**Minecraft Java Coberto's Utilities Mod (Fabric)**

Java Edition port of [coberto-utilities](https://github.com/TheCoberto/coberto-utilities) for Bedrock, a bundle of world utilities. Features are being ported a few at a time:

- **Creeper anti-grief:** creeper explosions still hurt players and mobs but don't break blocks. Operators can change this with `/creepergriefing true` (creepers break blocks) or `false`. Run it with no value to see the current setting. Saved in the world.
- **Gamemode shortcuts:** operators can use `/gms`, `/gmc`, `/gma` and `/gmsp` (Survival, Creative, Adventure, Spectator) instead of `/gamemode <mode>`. Add players to change someone else, like `/gmc @a`.

- **Mob Money:** players earn `Money` (the same score the Slot Machine uses) for killing mobs. Off by default. Kills with arrows and tridents count. Operator commands:
  - `/mobmoney true|false` turns it on or off. With no value it shows the current settings.
  - `/mobmoneyrange <min> <max>` sets the default reward, picked at random between min and max (default 5-25).
  - `/mobmoneyadd <mob> [min] [max]` makes a mob pay, optionally with its own range (one number = a fixed amount). `/mobmoneyremove <mob>` stops it.
  - `/mobmoneypreset hostile|all|none` picks which mobs pay. `hostile` is the default list, with bosses paying extra (Elder Guardian 50-100, Warden 100-200, Wither 250-500, Ender Dragon 500-1,000).
  - `/mobmoneynotify chat|actionbar|both|none` chooses how players are told: "You got 25 Money." in chat, "+25 Money" above the hotbar (default), both or neither.
- **Activity Rewards and AFK:** players earn `Money` for every so many minutes they're active. Anyone who doesn't move, look around, chat or use anything for a while is marked AFK: they get the `afk` tag, a big "You are AFK!" title, and their reward timer pauses until they come back. Rewards are off by default; AFK detection is on. Operator commands:
  - `/activity true|false` turns rewards on or off. With no value it shows the current settings.
  - `/activityreward <amount> <minutes>` sets the reward (default 100 Money every 10 minutes).
  - `/activitymessage <message>` sets the chat message. `{amount}`, `{player}` and `{minutes}` are filled in, and `&` color codes work (like `&a`). `reset` goes back to the default.
  - `/activitysound <sound>` sets the sound played with the message (default `entity.player.levelup`), or `none`.
  - `/afk true|false` turns AFK detection on or off. `/afktime <seconds>` sets how long before someone is AFK (default 300).
- **Loot crates:** Crate, Spooky Crate and Holiday Crate blocks (in the creative inventory under Functional Blocks) to hide around the map for events. Using one pops out random loot (diamonds, emeralds, gold, golden carrots, totems, shulker boxes and, very rarely, an Elytra or mace parts; Spooky Crates add pumpkins and jack o'lanterns, Holiday Crates cookies and snowballs) and the crate disappears. Finding an Elytra or Heavy Core is announced to everyone. Breaking a crate gives nothing, and explosions can't break them. Operators change each crate with `/crates`, which lists them in chat; click one to see its loot with each item's chance, then click to change or remove items. Or type the commands:
  - `/crates <crate> add <item> <weight> [min] [max]` adds an item, or changes one the crate already has. Weight is how often it comes up compared to the others (0 = never).
  - `/crates <crate> remove <item>` removes it.
  - `/crates <crate> items <least> <most>` sets how many items a crate gives.
  - `/crates <crate> reset` puts the defaults back.
- **Adventure Zones:** areas where players are switched to Adventure mode, and back to Survival when they leave. This replaces command blocks that change players within a radius. Players inside also get the `inside_adventure_zone` tag, so the Teams mod blocks PvP there. Only players in Survival are changed, so operators in Creative or Spectator are left alone. With no zones, it does nothing. Operator commands:
  - `/zonecreate <name> <radius> [allHeights]` makes a zone centered where you stand (or at a command block). `allHeights` true ignores height, like a column; otherwise it's a sphere.
  - `/zones` lists the zones. `/zonedelete <name>` deletes one.
  - `/zone <name>` shows clickable settings to rename the zone, turn it on or off, change its radius or height setting, move its center to where you stand, set the enter and leave messages (shown above the hotbar; `{player}`, `{zone}` and `&` colors work; `none` for no message) or delete it. Each is also a command, like `/zone spawn radius 40` or `/zone spawn entermessage &aWelcome to {zone}!`.

All settings are saved in the world.

World Cleanup, Teams and the Slot Machine, also part of the Bedrock bundle, are separate Java mods: [world-cleanup-minecraft-java](https://github.com/TheCoberto/world-cleanup-minecraft-java), [teams-minecraft-java](https://github.com/TheCoberto/teams-minecraft-java) and [slot-machine-minecraft-java](https://github.com/TheCoberto/slot-machine-minecraft-java). Transfer Player isn't needed: Java has a built-in `/transfer` command.

Requires Minecraft Java **1.21.1**, [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api). Because it adds blocks (the loot crates), it has to be installed on the server **and** by every player who joins.

## Differences from the Bedrock version
- Commands have no `ev:` prefix: `/creepergriefing` and `/gmc` instead of `/ev:creepergriefing` and `/ev:gmc`.
- Sounds use Java names: `/activitysound entity.experience_orb.pickup` rather than `random.orb`. The message doesn't need quotes.
- `/crates` uses clickable chat instead of a menu (Java has no forms). The block ids are the same: `coberto:crate`, `coberto:spooky_crate` and `coberto:holiday_crate`.
- Adventure Zones only take away the `inside_adventure_zone` tag and Adventure mode they gave out themselves, so they don't fight with command blocks or other tools that use the same tag.
- Creeper anti-grief only affects creepers. Java's `mobGriefing` game rule still works as normal for every mob, creepers included.

## Build
```
./gradlew build
```
Building needs JDK 25 or newer to run Gradle (the mod itself targets Java 21, which Gradle downloads automatically). The jar ends up in `build/libs/`. Put it in your `.minecraft/mods` folder along with Fabric API. `./gradlew runClient` starts a test game with the mod loaded.
