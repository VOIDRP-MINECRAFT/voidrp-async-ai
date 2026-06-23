package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Level.getBlockState() against main-thread deadlock when an unloaded chunk is accessed.
 *
 * Root cause (Watchdog dump 2026-06-23 19:49):
 *   Create DrillBlockEntity at x=-99985,y=-15,z=-100027 (local x=15 — east chunk boundary)
 *   calls optimiseCobbleGen() → CobbleGenOptimisation.determineOutput() which fires
 *   FluidPlaceBlockEvent. The event constructor calls WrappedLevel.getBlockState() for the
 *   adjacent block at x=-99984 (chunk -6249) which is not loaded.
 *   WrappedLevel delegates to Level.getBlockState() → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → LockSupport.parkNanos — server hangs.
 *
 * Fix: intercept getBlockState() at HEAD. If the target chunk is not immediately available
 * (getChunkNow == null), return AIR. Callers checking for lava/water/cobblestone patterns
 * treat AIR as "no fluid interaction" and fall back to normal drilling — no data loss.
 * Returning AIR for an unloaded chunk is safe because ticking blocks are always in loaded
 * chunks; only cross-chunk reads from chunk-boundary entities reach this path.
 *
 * WorldGenLevel (world generation) is NOT ServerLevel, so this guard does not affect
 * terrain generation or structure placement.
 *
 * Related guards already present:
 *   LevelFluidStateGuardMixin           — Level.getFluidState() → returns EMPTY
 *   LevelGetBlockEntityChunkGuardMixin  — Level.getBlockEntity() → returns null
 *   BlockCollisionsChunkGuardMixin      — BlockCollisions.getChunk() → returns null
 *   ServerPlayerGameModeMixin           — ServerPlayerGameMode.tick() delayedDestroyPos
 */
@Mixin(Level.class)
public abstract class LevelGetBlockStateChunkGuardMixin {

    @Inject(
        method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] getBlockState guard — chunk [{},{}] not loaded " +
                    "at block pos {},{},{} — returning AIR to prevent main-thread deadlock{}",
                    cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
