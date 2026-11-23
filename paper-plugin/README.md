# Coberto's Utilities (Paper plugin)

Server-side version of Coberto's Utilities for Paper servers. Players don't need any mods, and it works for Bedrock players joining through Geyser. So far it has **Classes**; more Utilities features can be added here later.

## Classes
Players get one of 15 classes, the same as in the Fabric mod and the Bedrock add-on: Warrior, Acrobat, Ghoul, Pyro, Aquarian, Glider, Paladin, Sentinel, Miner, Tank, Plaguebearer, Bard, Druid, Necromancer and Vampire. Each gives lasting effects and some have abilities. Players have one class at a time; picking another replaces it. The class is saved on the player and kept through logging out and dying.

- `/class` opens a menu to pick your own class (hover over a class to read it, click to pick it).
- `/setclass <players> <class>` sets anyone's class (`none` removes it). It works from the console, command blocks (`/setclass @p pyro`) and Citizens NPCs: `/npc command add setclass <p> pyro` makes an NPC that turns whoever clicks it into a Pyro.

Permission: `cobertoutilities.classes.admin` (op by default) for both commands. Players get classes from NPCs or command blocks.

## Build
```
./gradlew build
```
Needs JDK 21 or newer (JDK 25 is fine). The jar ends up in `build/libs/`.
