# Testing String Timing Restore

All testing uses `run/`, `build/` or **copies** of worlds. Never point anything at the real `.minecraft/saves`.

| # | What | How | Status |
| --- | --- | --- | --- |
| T1 | Concrete factory, 60 min | Manual (below) | manual |
| T2 | Rejoin the End with duper running | Manual (below) | manual |
| T3 | Minimal portal repro | Manual (below) | manual |
| T4 | Powder through string, no portal | GameTest `fallingPowderPulsesOnce` | automated |
| T5 | Item / mob through string | GameTests `itemPowersThenDepowers`, `mobPowersThenDepowers`; player is manual (below) | automated + manual |
| T6 | Hooked tripwire | GameTest `hookedTripwireSignals` | automated |
| T7 | Clock across chunk border (MC-310372) | GameTest `repowerRightAfterDepower` checks the cause; manual clock below | automated + manual |
| T8 | Player jumps under string (MC-305475) | Manual (below) | manual |
| T9 | Reversibility | Manual (below) | manual |
| T10 | Kill switch | `./gradlew runGametestDisabled` | automated |
| T11 | Version pin / missing mixin target | Procedure below (run once on 2026-11-02, both aborted startup) | procedure |
| T12 | Leftover vanilla 0/1-tick tick | GameTest `leftoverVanillaTickIsNormalRecheck` | automated |

## Automated

```
./gradlew runGameTest           # fix active.   Report: build/gametest-active.xml
./gradlew runGametestDisabled   # kill switch.  Report: build/gametest-disabled.xml
```

Both runs must end with `All 7 required tests passed :)`. That is 6 String Timing tests plus vanilla's `minecraft:always_pass`. A failing test fails the Gradle task with a non-zero exit code; this was checked by temporarily breaking an assertion.

The tests run the same code in both modes. When the result differs between 1.21.11 and vanilla 26.3, the test asserts the vanilla 26.3 result if `-Dstringtiming.disable=true` is set. So T10 passes only if the kill switch really restores vanilla.

- `repowerRightAfterDepower` (T7): right after a depower, the string has **no pending tick** with the fix, and a touch powers it again at once. With the kill switch, a tick is pending and the touch is ignored, as in vanilla 26.3.
  - This also covers T7 at the code level. MC-310372 came from the depower tick, and option A never schedules one.
- `mobPowersThenDepowers`, `itemPowersThenDepowers` (T5): the string powers, the entity is removed, and the string depowers within 11 ticks (one 10-tick recheck, plus 1 tick because test callbacks and block ticks run in different phases).
- `fallingPowderPulsesOnce` (T4): one white concrete powder falls through string. The string rises exactly once and falls exactly once, and an observer facing it pulses exactly twice.
- `hookedTripwireSignals` (T6): hook, string, string, hook. Both hooks attach, power while a pig stands on the string, and release afterwards.
- `leftoverVanillaTickIsNormalRecheck` (T12): powered string with a pending 0-tick (as a 26.2 save leaves it) or 1-tick (26.3 save) tick. It depowers and leaves no tick behind.

### T11: fail closed

Run once to confirm each part. Use a throwaway branch or revert afterwards.

1. **Version pin.**
   - Change `"minecraft": "26.3"` to `"26.2"` in `src/main/resources/fabric.mod.json` and run `./gradlew runServer`.
   - Expected: Fabric Loader stops with `Incompatible mods found!`, saying the mod requires Minecraft 26.2 but 26.3 is present. No world loads. This is the same check that rejects the 26.3 jar on 26.2 or 26.4.
   - Revert the change.
2. **Missing mixin target.**
   - In `TripWireBlockMixin`, change `method = "entityInside"` to `"entityInsideRenamed"` and run `./gradlew runServer`.
   - Expected: `InvalidInjectionException: Critical injection failure: @ModifyExpressionValue ... could not find any targets`, thrown during `Bootstrap.bootStrap`, before any world loads.
   - Revert the change.
   - Gradle may still print `BUILD SUCCESSFUL` because the game's crash handler exits normally. Check the log, not the Gradle status.

## Manual

### 0. Backup first. Do this before anything touches the real world.

- [ ] Close Minecraft.
- [ ] Zip the real world folder to `<world>-backup-YYYYMMDD-HHMM.zip`, somewhere outside `.minecraft`.
- [ ] Open the zip once and check that `level.dat`, `region/`, `DIM1/` (the End) and `DIM-1/` are inside and the sizes look right.
- [ ] Make a **copy** of the world for testing, for example `<world>-stringtiming-test`. Every test below uses only this copy.

Install for testing:
- Fabric Loader ≥ 0.19.5 for Minecraft 26.3.
- `stringtiming-<version>.jar` in that instance's `mods/` folder. Fabric API is not needed.
- Check `logs/latest.log` for `String Timing Restore: fix ACTIVE`.

### T3: minimal portal repro

- [ ] In a creative copy, build one End portal, falling concrete powder that goes through it, string under the arrival point, an observer facing the string, and a redstone lamp. No duper, no TNT.
- [ ] Drop 20 powder, one at a time.
- [ ] Pass: the lamp flashes **twice** per powder (once when the string powers, once when it depowers). F3 never shows the string `powered=true` for more than about 10 ticks with nothing on it.

### T1: concrete factory, 60 minutes

- [ ] On the test copy, run the duper for 60 minutes (real time) in the End.
- [ ] Pass: the string is never stuck at `powered=true`, TNT fires every cycle, and no powder pops off the waterlogged chest as an item.
- [ ] Optional, performance: use Carpet `/tick health` or the F3 tick graph, mod on vs off. The difference should be under 0.1 ms per tick.

### T2: rejoin with duper running

- [ ] With the duper running, leave the End through the exit portal and come back, or disconnect and reconnect.
- [ ] Pass: the farm keeps cycling with no manual reset.

### T5: player

- [ ] Walk through a single string with an observer and lamp.
- [ ] Pass: it powers, and depowers within about 0.5 s (10 ticks) after you step off.

### T7: clock across a chunk border

- [ ] With F3 + G showing chunk borders, build a repeater clock whose loop crosses a chunk border, with string on one side wired into the loop as in the MC-310372 report. Walk through the string a few times.
- [ ] Pass: the clock never stops.

### T8: player jumps under string (MC-305475, expected regression)

- [ ] Stand under string hung at head height and jump repeatedly, with an observer and lamp on the string.
- [ ] Expected **with the mod**: double triggers are possible. That is the 1.21.11 behavior, and it is the known trade-off of option A.
- [ ] Launch with `-Dstringtiming.disable=true` and repeat: no double triggers (vanilla 26.3).

### T9: reversibility

- [ ] On a fresh copy of the world: load with the mod, play for 5 minutes with the duper running, then save and quit.
- [ ] Remove `stringtiming-<version>.jar` from `mods/`, or launch vanilla 26.3.
- [ ] Load the same copy. Pass:
  - no errors or warnings about the mod or about tripwire in `logs/latest.log`
  - the world loads
  - string and hooks behave as vanilla 26.3
  - nothing in the world changed beyond normal gameplay

After all manual tests pass, install on the real world only if you have the backup zip from step 0.
