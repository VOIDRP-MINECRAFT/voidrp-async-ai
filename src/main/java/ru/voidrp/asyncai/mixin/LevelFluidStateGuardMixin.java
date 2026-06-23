package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Level.getFluidState() against main-thread deadlock during entity movement.
 *
 * Root cause (Watchdog dump 2026-05-27 01:47, +15s):
 *   ServerPlayer.doTick → LivingEntity.travel → Entity.move
 *   → Entity.localvar$bnn000$lionfishapi$fluidCollision (LionfishAPI injection at move:10269)
 *   → Level.getFluidState → Level.getChunkAt → Level.getChunk(398)
 *   → ServerChunkCache.getChunk(202) → managedBlock → LockSupport.parkNanos — HANGS.
 *
 * Trigger: Hyuka teleported to 1,000,000 / 100 / 1,000,000. LionfishAPI's fluid-collision
 * hook in Entity.move calls getFluidState() at the player position every tick. The chunk at
 * those coordinates is loaded but neighboring chunks (used for fluid boundary detection) are
 * not yet at FULL status, causing getChunk(FULL, true) to block the server thread.
 *
 * Fix: intercept getFluidState at HEAD. If the target chunk is not immediately available
 * (getChunkNow == null), return Fluids.EMPTY.defaultFluidState() — no fluid for that tick.
 * If the chunk IS loaded, fall through to the normal path (array read, non-blocking).
 * Returning EMPTY skips fluid buoyancy/drag for one tick — negligible physics cost vs. freeze.
 *
 * Related guards already present:
 *   PlayerTravelChunkGuardMixin     — Entity.getOnPos → Level.getBlockState
 *   LivingEntityTravelGuardMixin    — LivingEntity.travel → Level.getBlockState
 *   SprintParticleChunkGuardMixin   — Entity.spawnSprintParticle → Level.getBlockState
 *   BlockCollisionsChunkGuardMixin  — BlockCollisions.getChunk → getChunkForCollisions
 */
@Mixin(Level.class)
public abstract class LevelFluidStateGuardMixin {

    @Inject(
        method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                if (suppressed > 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] getFluidState guard — chunk [{},{}] not loaded " +
                        "at block pos {},{},{} — returning EMPTY fluid to prevent deadlock (+{} suppressed)",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ(), suppressed);
                } else {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] getFluidState guard — chunk [{},{}] not loaded " +
                        "at block pos {},{},{} — returning EMPTY fluid to prevent deadlock",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ());
                }
            }
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }
}
