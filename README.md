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

All settings are saved in the world.

World Cleanup, Teams and the Slot Machine, also part of the Bedrock bundle, are separate Java mods: [world-cleanup-minecraft-java](https://github.com/TheCoberto/world-cleanup-minecraft-java), [teams-minecraft-java](https://github.com/TheCoberto/teams-minecraft-java) and [slot-machine-minecraft-java](https://github.com/TheCoberto/slot-machine-minecraft-java). Transfer Player isn't needed: Java has a built-in `/transfer` command.

Requires Minecraft Java **1.21.1**, [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api). Server-side: it has to be installed on the server (or in single player). Players who join don't need it.

## Differences from the Bedrock version
- Commands have no `ev:` prefix: `/creepergriefing` and `/gmc` instead of `/ev:creepergriefing` and `/ev:gmc`.
- Sounds use Java names: `/activitysound entity.experience_orb.pickup` rather than `random.orb`. The message doesn't need quotes.
- Creeper anti-grief only affects creepers. Java's `mobGriefing` game rule still works as normal for every mob, creepers included.

## Build
```
./gradlew build
```
Building needs JDK 25 or newer to run Gradle (the mod itself targets Java 21, which Gradle downloads automatically). The jar ends up in `build/libs/`. Put it in your `.minecraft/mods` folder along with Fabric API. `./gradlew runClient` starts a test game with the mod loaded.
