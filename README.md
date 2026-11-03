# String Timing Restore

<img src="src/main/resources/assets/stringtiming/icon.png" alt="String Timing Restore icon" width="128" align="right">

A small Fabric mod for **Minecraft 26.3** that makes placed string (tripwire) work the way it did in **1.21.11**.

If a string-based farm or duper broke when you updated past 1.21.11, this mod is probably the fix.

## The problem

Placed string has a simple job. When something passes through it, it turns **on**. It then checks every 10 game ticks whether anything is still inside, and turns **off** once nothing is. Observers and tripwire hooks react to those on/off changes.

Minecraft 26.2 changed this to fix a small bug, MC-305475, where a player jumping under string could trigger it twice:

1. Every time string turns off, it now schedules an extra game tick.
2. While that extra tick is waiting, **string ignores anything passing through it**.

Normal walking is unaffected. Contraptions that send things through string quickly are not. In an End-portal concrete powder duper, powder arrives fast and in a tight rhythm. On 26.2+ the string can end up **stuck on**, showing `powered=true` with nothing inside it. The observer never pulses again and the TNT never fires, so the farm stops.

## How the mod fixes it

The mod undoes exactly that 26.2 change and nothing else:

- String no longer ignores entities right after it turns off.
- String no longer schedules the extra tick when it turns off.

That puts string back on the 1.21.11 timing farms were built for. It is one small mixin on the tripwire block.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) **0.19.5 or newer** for Minecraft **26.3**.
2. Put `stringtiming-<version>.jar` in your `mods` folder. Fabric API is **not** needed.
3. Start the game. The log will show `String Timing Restore: fix ACTIVE`.

It runs on the server side. It works in singleplayer, and on a Fabric server players can join with an unmodded client.

## Good to know

- **Minecraft 26.3 only.** On any other version, Fabric refuses to load the mod rather than run it on code it wasn't checked against.
- **Your world is safe.** The mod changes tripwire logic only. It saves nothing and adds no blocks, items or block states. Remove the jar and the world loads in plain 26.3 as before. Backing up a world before adding any mod is still a good habit.
- **Trade-off:** because the 26.2 change is undone, a player jumping under string can double-trigger it again, as in 1.21.11.
- **Kill switch:** launch with the JVM argument `-Dstringtiming.disable=true` to get vanilla 26.3 behavior without removing the mod.

## For developers

```
./gradlew build                 # jar in build/libs/ (also runs the GameTests)
./gradlew runGameTest           # GameTests with the fix active
./gradlew runGametestDisabled   # GameTests with the kill switch on
```

- [`DIFF.md`](DIFF.md): line-by-line comparison of the tripwire code in 1.21.11, 26.2 and 26.3, and why this fix was chosen.
- [`TESTING.md`](TESTING.md): the automated GameTests and the manual in-game test checklist.

Built with Fabric Loom 1.19.3 and Gradle 9.8.2, targeting Java 25.

## License

[MIT](LICENSE)
