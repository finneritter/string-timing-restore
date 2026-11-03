package io.github.finneritter.stringtiming.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.finneritter.stringtiming.StringTiming;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TripWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Option A (see DIFF.md): revert the 26.2 tripwire change (MC-305475) to 1.21.11 logic.
 * Both injectors only change control flow; no states, ticks or data beyond what vanilla produces.
 */
@Mixin(TripWireBlock.class)
public abstract class TripWireBlockMixin {
	/** Remove the "tick pending" guard so entities can always power unpowered string, as in 1.21.11. */
	@ModifyExpressionValue(
			method = "entityInside",
			require = 1,
			allow = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/ticks/LevelTickAccess;hasScheduledTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;)Z"))
	private boolean stringtiming$ignoreScheduledTickGuard(boolean scheduled) {
		return StringTiming.DISABLED && scheduled;
	}

	/** Drop the 1-tick depower schedule (ordinal 1; ordinal 0 is the 10-tick recheck, kept). */
	@WrapOperation(
			method = "checkPressed(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ljava/util/List;)V",
			require = 1,
			allow = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;I)V",
					ordinal = 1))
	private void stringtiming$skipDepowerTick(Level level, BlockPos pos, Block block, int delay, Operation<Void> original) {
		if (StringTiming.DISABLED) {
			original.call(level, pos, block, delay);
		}
	}
}
