# Coberto's Utilities
**Minecraft Java Coberto's Utilities Mod (Fabric)**

Java Edition port of [coberto-utilities](https://github.com/TheCoberto/coberto-utilities) for Bedrock, a bundle of world utilities. Features are being ported a few at a time:

- **Creeper anti-grief:** creeper explosions still hurt players and mobs but don't break blocks. Operators can change this with `/creepergriefing true` (creepers break blocks) or `false`. Run it with no value to see the current setting. Saved in the world.
- **Gamemode shortcuts:** operators can use `/gms`, `/gmc`, `/gma` and `/gmsp` (Survival, Creative, Adventure, Spectator) instead of `/gamemode <mode>`. Add players to change someone else, like `/gmc @a`.

World Cleanup and Teams, also part of the Bedrock bundle, are separate Java mods: [world-cleanup-minecraft-java](https://github.com/TheCoberto/world-cleanup-minecraft-java) and [teams-minecraft-java](https://github.com/TheCoberto/teams-minecraft-java). Transfer Player isn't needed: Java has a built-in `/transfer` command.

Requires Minecraft Java **1.21.1**, [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api). Server-side: it has to be installed on the server (or in single player). Players who join don't need it.

## Differences from the Bedrock version
- Commands have no `ev:` prefix: `/creepergriefing` and `/gmc` instead of `/ev:creepergriefing` and `/ev:gmc`.
- Creeper anti-grief only affects creepers. Java's `mobGriefing` game rule still works as normal for every mob, creepers included.

## Build
```
./gradlew build
```
Building needs JDK 25 or newer to run Gradle (the mod itself targets Java 21, which Gradle downloads automatically). The jar ends up in `build/libs/`. Put it in your `.minecraft/mods` folder along with Fabric API. `./gradlew runClient` starts a test game with the mod loaded.
