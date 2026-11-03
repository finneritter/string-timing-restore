# TripWireBlock: 1.21.11 vs 26.3

Technical notes from building the mod. **Status: option A shipped, and it fixed the stuck string in the concrete farm it was built for.**

Sources: Vineflower output from Loom 1.19.3 `genSources`.
- 1.21.11: throwaway project, Mojang mappings (`loom.officialMojangMappings()`).
- 26.2 and 26.3: unobfuscated jars.
- 26.2 was decompiled as well, to see what 26.3 changed (MC-310372).

The 26.3 bytecode was checked with `javap` to get the exact injection targets.

## TL;DR

There are **two behavioral changes** in `TripWireBlock`. Both arrived in 26.2 (MC-305475), and 26.3 tweaked one of them. Everything else in the file is renames, a removed `CODEC`, or reformatting.

| # | Where | 1.21.11 | 26.3 |
| --- | --- | --- | --- |
| 1 | `entityInside` | power if `!POWERED` | power if `!POWERED && !level.getBlockTicks().hasScheduledTick(pos, this)` |
| 2 | `checkPressed(Level, BlockPos, List)` | schedule only when pressed (10 ticks) | also on depower: `else if (wasPressed) scheduleTick(pos, this, 1)` (26.2 used delay **0**) |

The tick scheduler (`LevelTicks`, `LevelChunkTicks`, `ScheduledTick`), `Entity.checkInsideBlocks`, `isIgnoringBlockTriggers` and the head of `FallingBlockEntity.tick` (through `handlePortal`) are identical apart from names. One relevant change outside the tripwire exists: **resting items replay block effects every tick** (see below).

## Method by method

### `entityInside` (changed, #1)

```java
// 1.21.11
if (!level.isClientSide()) {
    if (!blockState.getValue(POWERED)) {
        this.checkPressed(level, blockPos, List.of(entity));
    }
}
// 26.3
if (!level.isClientSide()) {
    if (!state.getValue(POWERED) && !level.getBlockTicks().hasScheduledTick(pos, this)) {
        this.checkPressed(level, pos, List.of(entity));
    }
}
```

Runtime effect: while any tripwire tick is pending at this position, entities cannot power the string. When the string is unpowered, the only tick that can be pending is the depower tick from #2. That gives a dead window of about 1 tick after every depower: anything passing through in that window is ignored.

`hasScheduledTick` looks only in the chunk's pending set (`LevelChunkTicks.ticksPerPosition`). A tick that is already running, or polled into `toRunThisTick`, no longer counts. So inside the depower tick's own `tick()` call the guard is already off.

### `checkPressed(Level, BlockPos, List)` (changed, #2)

```java
// 1.21.11
if (bl2) {
    level.scheduleTick(new BlockPos(blockPos), this, 10);
}
// 26.2
if (shouldBePressed) {
    level.scheduleTick(new BlockPos(pos), this, 10);
} else if (wasPressed) {
    level.scheduleTick(new BlockPos(pos), this, 0);
}
// 26.3
if (shouldBePressed) {
    level.scheduleTick(pos, this, 10);
} else if (wasPressed) {
    level.scheduleTick(pos, this, 1);
}
```

Runtime effect: every depower schedules one extra tick, which exists only to arm the guard in #1. When it fires, `tick()` sees `POWERED=false` and does nothing.

On 26.2 the delay was 0. A tick scheduled at the current game time while the tick queue is draining is what broke cross-chunk tick order (MC-310372). 26.3 changed **only the delay, 0 → 1**. The 26.2 guard itself was not reverted.

Also changed: `level.setBlock(pos, state, 3)` became `level.setBlockAndUpdate(pos, state)`. That is the same call, since `LevelWriter.setBlockAndUpdate` is `setBlock(pos, state, 3)`. `new BlockPos(pos)` became `pos`, which makes no difference at runtime because `BlockPos` is immutable here.

### Unchanged in behavior

- `tick`: `if (POWERED) checkPressed(level, pos)`.
- `checkPressed(Level, BlockPos)`: entity search over the string shape's AABB.
- `onPlace`, `affectNeighborsAfterRemoval`, `updateShape`, `getStateForPlacement`, `playerWillDestroy` (shears → `DISARMED`), `updateSource` (hook search, 42 blocks, SOUTH/WEST), `shouldConnectTo`, `rotate`, `mirror`, `createBlockStateDefinition`: unchanged.
- `RECHECK_PERIOD` = 10 in both versions.
- The `CODEC`/`codec()` removal is a registry/codec refactor and changes nothing for placed string.

## Entity-side code

- **`Entity.checkInsideBlocks`** (both overloads), `applyEffectsFromBlocks()` and `applyEffectsFromBlocks(List)`: identical logic. `entityInside` is still called once per block per movement, with the same `isPrecise` rule.
- **`Entity.isIgnoringBlockTriggers`**: `false` by default in both versions.
- **`FallingBlockEntity`**:
  - `tick()` up to `handlePortal()` is identical.
  - `forceTickAfterTeleportToDuplicate` and `teleport()` are the same in both versions.
  - Note: the forced tick runs on the *old* entity, in the *source* level, after the teleport. That continued tick is what places or drops the duplicate there. The copy that arrives in the End is a fresh entity that ticks normally in the End's entity phase; it gets no extra forced tick.
- **`ItemEntity.tick`**: **changed (already in 26.2)**.
  - 1.21.11: a resting item (on the ground and not moving horizontally) skipped `move` and block effects on 3 of every 4 ticks.
  - 26.2+: on those ticks it calls the new `Entity.applyEffectsFromBlocksForLastMovements()`, which replays `entityInside` along its *last recorded movement path*.
  - So a resting item can call `TripWireBlock.entityInside` for string it passed through during its last real movement, even when it is no longer inside the string.
  - How much this matters: the path is refreshed every 4th tick to the item's near-zero resting movement, so stale replays last at most 3 ticks. A stale replay can power the string, but the string's 10-tick recheck (an AABB entity search) still depowers it. **On its own it cannot cause a string that stays stuck.** It can add extra power pulses after powder pops off as an item.
  - This is not reverted by either option below.

## What can leave a string powered with no entity inside

A powered string normally always has a recheck pending, in both versions: powering schedules a 10-tick tick, and the recheck reschedules while pressed. Reading the code, the 26.3 tripwire cannot drop its own recheck:
- `scheduleTick` dedupes per (pos, block). The only tick it can collide with is the earlier 1-tick depower tick, which fires and reruns `checkPressed`.
- In vanilla 26.3, `entityInside` never powers while a tick is pending.

So a string that stays stuck means one of these:

1. **The recheck is pending but never runs.** The report says leaving and rejoining the End clears the stuck state. Reloading the chunks reloads their pending ticks into `LevelTicks`, which fits this case best. This is the MC-310372 family: per-chunk "next tick" bookkeeping across a chunk border, and the farm straddles z = 0.
2. **The recheck was never stored.** `LevelTicks.schedule` silently drops ticks for an unloaded chunk ("Trying to schedule tick in not loaded position"). A duplicate arriving at a chunk edge could hit this.
3. **The string isn't actually stuck.** It repowers right after each depower (for example from item replays or several powder entities), so it reads `powered=true` almost all the time and the observer's pulses merge.

Option A removes the 26.2 machinery (the extra tick and the guard), so string scheduling goes back to exactly what 1.21.11 did, and the farm worked on 1.21.11. That covers case 1 if the extra depower tick is what upsets the scheduler. It covers case 3 only partly. **To confirm in-game (for example with Carpet), check at the moment of sticking:**
- Is a `minecraft:tripwire` tick pending at the string? Use `/tick freeze`, then look at the scheduled ticks; Carpet's `/info block` or a `/script` query works.
- Which chunk holds the string, and does that chunk still process other block ticks?
- Does the server log show "Trying to schedule tick in not loaded position"?
- Does the string ever flip to `powered=false` for 1 to 2 ticks, which would be case 3?

## Proposed injection points

The mixin goes on `net.minecraft.world.level.block.TripWireBlock`. Both options use MixinExtras, which Fabric Loader bundles. Every injector has `require = 1` and checks `StringTiming.DISABLED` first.

Bytecode targets, verified with `javap` on 26.3:

- `entityInside` contains one call: `invokeinterface net/minecraft/world/ticks/LevelTickAccess.hasScheduledTick:(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;)Z`.
- `checkPressed(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ljava/util/List;)V` contains two calls to `Level.scheduleTick:(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;I)V`:
  - ordinal 0: delay 10
  - ordinal 1: delay 1, the depower tick

### Option A: revert to 1.21.11 (recommended)

```java
// 1. Remove the guard: the scheduled-tick check reads as false.
@ModifyExpressionValue(method = "entityInside", require = 1,
    at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/ticks/LevelTickAccess;hasScheduledTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;)Z"))
private boolean stringtiming$noGuard(boolean scheduled) {
    return StringTiming.DISABLED && scheduled; // enabled → always false
}

// 2. Drop the depower tick (second scheduleTick in checkPressed).
@WrapOperation(method = "checkPressed(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ljava/util/List;)V", require = 1,
    at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;I)V",
        ordinal = 1))
private void stringtiming$noDepowerTick(Level level, BlockPos pos, Block block, int delay, Operation<Void> original) {
    if (StringTiming.DISABLED) original.call(level, pos, block, delay);
}
```

The result is byte-for-byte the 1.21.11 logic: no extra ticks, no dead window.

Both parts are needed:
- Without part 2, a repower during the 1-tick window is deduped against the pending 1-tick tick. The recheck then happens after 1 tick instead of 10, which is not 1.21.11 timing.
- Without part 1, the dead window stays.

World safety:
- Block states: the mod sets no new states. Only `checkPressed` writes `POWERED`, as before.
- Leftover ticks: a leftover 0/1-tick tick from a vanilla save fires `tick()`. If the string is powered, that is a normal recheck; if not, nothing happens. It cannot loop, because the modded `checkPressed` never schedules depower ticks. In the other direction, the mod schedules only the 10-tick recheck, which vanilla also schedules.

Trade-off: MC-305475 comes back (a player jumping under string double-triggers it). See T8 in `TESTING.md`.

### Option B: targeted bypass

Same first injection, but stop the guard only for selected entities:

```java
@WrapOperation(method = "entityInside", require = 1, at = @At(value = "INVOKE",
    target = "Lnet/minecraft/world/ticks/LevelTickAccess;hasScheduledTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;)Z"))
private boolean stringtiming$bypassForFallingBlocks(LevelTickAccess<?> ticks, BlockPos pos, Object block, Operation<Boolean> original,
        BlockState state, Level level, BlockPos p, Entity entity) {
    boolean scheduled = original.call(ticks, pos, block);
    return !StringTiming.DISABLED && entity instanceof FallingBlockEntity ? false : scheduled;
}
```

The 1-tick depower schedule stays, which keeps the MC-310372 fix and the MC-305475 fix for players. A powder entity that repowers during the window collides with the pending 1-tick tick, so the recheck comes after 1 tick, not 10. That doesn't break the farm, but it is not 1.21.11 timing. It also does nothing about cases 1 and 2 if the trace shows those.

### Recommendation

**Option A.**
- It is the only option that makes string scheduling identical to 1.21.11, and that is the last version where the farm worked.
- It is two small injectors with no entity-type special cases.
- Its leftover-tick behavior is simple to reason about.
- B is only worth it if you want to keep the MC-305475 fix for players and the trace proves portal-arriving falling blocks are the only trigger.

If the trace shows the recheck is *pending but not running* even with option A applied (case 1 outside the tripwire), that is option C: the fix would have to live outside `TripWireBlock`.
