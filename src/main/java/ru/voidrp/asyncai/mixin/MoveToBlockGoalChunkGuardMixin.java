package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Guards MoveToBlockGoal.findNearestBlock() against main-thread chunk loads.
 *
 * Root cause (spark profile 2026-06-02):
 *   Multiple AlexsMobs AI goals extend MoveToBlockGoal and override
 *   isValidTarget(LevelReader, BlockPos) to call Level.getBlockState().
 *   When the mob scans its search radius (up to 32 blocks), it touches block
 *   positions in unloaded chunks, which triggers synchronous chunk loading on
 *   the main thread via ServerChunkCache.getChunk(FULL, true).
 *
 * Fix: Redirect the virtual call to isValidTarget() within findNearestBlock().
 *   Before dispatching to the subclass implementation, check that the target
 *   chunk is already loaded (getChunkNow). If not loaded, return false.
 *
 * For @Redirect on a virtual method call, the first handler parameter must be
 * the receiver object (MoveToBlockGoal). Since isValidTarget is protected, we
 * call it via @Shadow on `this` (same instance as the receiver in practice).
 */
@Mixin(MoveToBlockGoal.class)
public abstract class MoveToBlockGoalChunkGuardMixin {

    @Shadow
    protected abstract boolean isValidTarget(LevelReader levelReader, BlockPos pos);

    @Redirect(
        method = "findNearestBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/goal/MoveToBlockGoal;isValidTarget(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"
        ),
        require = 0
    )
    private boolean voidrp_safeIsValidTarget(MoveToBlockGoal self, LevelReader level, BlockPos pos) {
        if (level instanceof ServerLevel sl) {
            ServerChunkCache cache = sl.getChunkSource();
            if (cache.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                return false;
            }
        }
        return this.isValidTarget(level, pos);
    }
}
