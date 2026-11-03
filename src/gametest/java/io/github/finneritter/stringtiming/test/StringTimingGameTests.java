package io.github.finneritter.stringtiming.test;

import io.github.finneritter.stringtiming.StringTiming;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Run with the fix active (runGameTest) and with -Dstringtiming.disable=true (runGametestDisabled, T10).
 * Tests that differ between 1.21.11 and 26.3 assert the vanilla 26.3 result when the kill switch is set.
 */
public class StringTimingGameTests {
	private static final BlockPos STRING = new BlockPos(1, 1, 1);
	private static final Block POWDER = Blocks.CONCRETE_POWDER.pick(DyeColor.WHITE);

	/**
	 * T7: an entity touching string right after a depower powers it again, and depowering schedules no
	 * extra tick (the 26.2 1-tick depower tick is what MC-310372 was about). With the kill switch: vanilla
	 * 26.3, so a tick is pending and the touch is ignored.
	 */
	@GameTest(maxTicks = 60)
	public void repowerRightAfterDepower(GameTestHelper h) {
		h.setBlock(STRING.below(), Blocks.STONE);
		h.setBlock(STRING, Blocks.TRIPWIRE);
		h.setBlock(new BlockPos(5, 0, 5), Blocks.STONE);
		// Not inside the string, so the 10-tick recheck finds nobody; we call entityInside directly.
		ArmorStand stand = h.spawn(EntityTypes.ARMOR_STAND, new BlockPos(5, 1, 5));
		ServerLevel level = h.getLevel();
		BlockPos abs = h.absolutePos(STRING);
		boolean[] poweredOnce = {false};

		h.onEachTick(() -> {
			BlockState state = level.getBlockState(abs);
			if (!poweredOnce[0]) {
				state.entityInside(level, abs, stand, InsideBlockEffectApplier.NOOP, true);
				h.assertBlockProperty(STRING, TripWireBlock.POWERED, true);
				h.assertTrue(level.getBlockTicks().hasScheduledTick(abs, Blocks.TRIPWIRE), "10-tick recheck scheduled on power");
				poweredOnce[0] = true;
			} else if (!state.getValue(TripWireBlock.POWERED)) {
				h.assertValueEqual(level.getBlockTicks().hasScheduledTick(abs, Blocks.TRIPWIRE), StringTiming.DISABLED,
						"tick pending right after depower");
				level.getBlockState(abs).entityInside(level, abs, stand, InsideBlockEffectApplier.NOOP, true);
				h.assertValueEqual(level.getBlockState(abs).getValue(TripWireBlock.POWERED), !StringTiming.DISABLED,
						"string powered by a touch right after depower");
				h.succeed();
			}
		});
	}

	/**
	 * T12: worlds saved by vanilla 26.2 (0-tick) or 26.3 (1-tick) can hold a pending depower tick on a powered
	 * string. It must act as a normal recheck (depower, nobody inside) and leave no tick behind (no loop).
	 */
	@GameTest(maxTicks = 20)
	public void leftoverVanillaTickIsNormalRecheck(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		BlockPos[] strings = {new BlockPos(1, 1, 1), new BlockPos(3, 1, 1)};
		int[] delays = {0, 1};
		for (int i = 0; i < strings.length; i++) {
			h.setBlock(strings[i].below(), Blocks.STONE);
			h.setBlock(strings[i], Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.POWERED, true));
			level.scheduleTick(h.absolutePos(strings[i]), Blocks.TRIPWIRE, delays[i]);
		}
		h.runAtTickTime(8, () -> {
			for (BlockPos s : strings) {
				h.assertBlockProperty(s, TripWireBlock.POWERED, false);
				h.assertFalse(level.getBlockTicks().hasScheduledTick(h.absolutePos(s), Blocks.TRIPWIRE), "no tick left behind at " + s);
			}
			h.succeed();
		});
	}

	/** T5 (mob): powers, then depowers within one recheck of the mob leaving. */
	@GameTest(maxTicks = 60)
	public void mobPowersThenDepowers(GameTestHelper h) {
		h.setBlock(STRING.below(), Blocks.STONE);
		h.setBlock(STRING, Blocks.TRIPWIRE);
		powerThenRelease(h, STRING, TripWireBlock.POWERED, h.spawnWithNoFreeWill(EntityTypes.PIG, STRING));
	}

	/** T5 (item): powers, then depowers within one recheck of the item leaving. */
	@GameTest(maxTicks = 60)
	public void itemPowersThenDepowers(GameTestHelper h) {
		h.setBlock(STRING.below(), Blocks.STONE);
		h.setBlock(STRING, Blocks.TRIPWIRE);
		powerThenRelease(h, STRING, TripWireBlock.POWERED, h.spawnItem(Items.STONE, STRING));
	}

	/**
	 * T4: one concrete powder falling through string (no portal) gives exactly one rise and one fall on
	 * the string, and an observer watching it pulses twice. Same result in 1.21.11 and vanilla 26.3.
	 */
	@GameTest(maxTicks = 80)
	public void fallingPowderPulsesOnce(GameTestHelper h) {
		BlockPos string = new BlockPos(1, 2, 1);
		BlockPos observer = new BlockPos(2, 2, 1);
		h.setBlock(new BlockPos(1, 0, 1), Blocks.STONE); // powder lands at y=1, below the string
		h.setBlock(string, Blocks.TRIPWIRE);
		// placed after the string so its own placement is not observed
		h.setBlock(observer, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
		h.setBlock(new BlockPos(1, 6, 1), POWDER); // falls on its own

		int[] stringRises = {0}, stringFalls = {0}, observerRises = {0};
		boolean[] last = {false, false};
		h.onEachTick(() -> {
			boolean s = h.getBlockState(string).getValue(TripWireBlock.POWERED);
			boolean o = h.getBlockState(observer).getValue(ObserverBlock.POWERED);
			if (s && !last[0]) stringRises[0]++;
			if (!s && last[0]) stringFalls[0]++;
			if (o && !last[1]) observerRises[0]++;
			last[0] = s;
			last[1] = o;
		});
		h.runAtTickTime(70, () -> {
			h.assertBlockState(new BlockPos(1, 1, 1), st -> st.is(POWDER),
					st -> net.minecraft.network.chat.Component.literal("powder should have landed below the string"));
			h.assertValueEqual(stringRises[0], 1, "string rising edges");
			h.assertValueEqual(stringFalls[0], 1, "string falling edges");
			h.assertValueEqual(observerRises[0], 2, "observer pulses");
			h.succeed();
		});
	}

	/** T6: hook - string - string - hook; hooks power while a mob is on the string and release after. */
	@GameTest(maxTicks = 80)
	public void hookedTripwireSignals(GameTestHelper h) {
		for (int x = 0; x <= 5; x++) h.setBlock(new BlockPos(x, 0, 1), Blocks.STONE);
		h.setBlock(new BlockPos(0, 1, 1), Blocks.STONE);
		h.setBlock(new BlockPos(5, 1, 1), Blocks.STONE);
		h.setBlock(new BlockPos(1, 1, 1), Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.EAST));
		h.setBlock(new BlockPos(4, 1, 1), Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.WEST));
		h.setBlock(new BlockPos(2, 1, 1), Blocks.TRIPWIRE);
		h.setBlock(new BlockPos(3, 1, 1), Blocks.TRIPWIRE);
		h.assertBlockProperty(new BlockPos(1, 1, 1), TripWireHookBlock.ATTACHED, true);
		powerThenRelease(h, new BlockPos(1, 1, 1), TripWireHookBlock.POWERED, h.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(2, 1, 1)));
	}

	/**
	 * Waits until {@code watch} is powered, removes the entity, then requires it unpowered within 11 ticks:
	 * at most one 10-tick recheck, +1 because test callbacks and block ticks run in different tick phases.
	 */
	private static void powerThenRelease(GameTestHelper h, BlockPos watch,
			net.minecraft.world.level.block.state.properties.BooleanProperty powered, Entity entity) {
		long[] leftAt = {-1};
		h.onEachTick(() -> {
			boolean on = h.getBlockState(watch).getValue(powered);
			if (leftAt[0] < 0) {
				if (on) {
					entity.discard();
					leftAt[0] = h.getTick();
				}
			} else if (!on) {
				long waited = h.getTick() - leftAt[0];
				h.assertTrue(waited <= 11, "depowered " + waited + " ticks after the entity left (max 11)");
				h.succeed();
			}
		});
	}
}
