package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.MainThreadChunkLoad;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Level.isRainingAt() against main-thread deadlock when an unloaded chunk is accessed.
 *
 * Root cause (Watchdog dump 2026-07-20 19:30, player SigmaStep22800 login at
 *   x=-381,y=70,z=-18029 — far region, chunk [-24,-1127] still loading):
 *     mi_tweaks VeryHotItems.inventoryTick(VeryHotItems.java:28)
 *       → Entity.isInWaterRainOrBubble → Entity.isInRain
 *       → Level.isRainingAt(pos)
 *       → Level.getHeightmapPos → Level.getHeight → Level.getChunk(FULL, true)
 *       → ServerChunkCache.MainThreadExecutor.managedBlock → LockSupport.parkNanos — server hangs.
 *
 * isRainingAt() also calls Level.getBiome() (precipitation lookup) which likewise dereferences
 * the chunk, so guarding at HEAD short-circuits the whole path in one place.
 *
 * Fix: when the block's chunk is not resident and cannot be served within the small budget
 *   ({@link MainThreadChunkLoad#shouldServeUnloaded}), return false ("not raining here").
 *   Benign — the entity is simply treated as not-in-rain for that tick; once the chunk finishes
 *   loading the vanilla check resumes. Weather has no gameplay-critical dependence on this.
 *
 * WorldGenLevel is NOT ServerLevel, so terrain generation is unaffected.
 *
 * Related guards: LevelGetBlockStateChunkGuardMixin, LevelFluidStateGuardMixin,
 *   LevelGetBlockEntityChunkGuardMixin, StructureManagerChunkGuardMixin.
 */
@Mixin(Level.class)
public abstract class LevelIsRainingAtChunkGuardMixin {

    @Inject(
        method = "isRainingAt(Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardIsRainingAt(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (MainThreadChunkLoad.shouldServeUnloaded(serverLevel, cx, cz)) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] isRainingAt guard — chunk [{},{}] not loaded " +
                    "at block pos {},{},{} — returning false to prevent main-thread deadlock{}",
                    cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(false);
        }
    }
}
