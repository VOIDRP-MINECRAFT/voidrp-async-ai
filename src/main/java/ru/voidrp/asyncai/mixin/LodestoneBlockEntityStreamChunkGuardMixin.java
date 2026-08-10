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

import java.util.stream.Stream;

/**
 * Guards Lodestone's block-entity region stream against a main-thread chunk-load deadlock.
 *
 * Root cause (Watchdog HUNG_TICK dump 2026-07-21 01:13):
 *   Player ran /rtp landing in never-visited far chunks (~-6255,-6888). Malum's
 *   WeepingWellRejectionHandler.entityTick → WeepingWellData.checkForWeepingWell iterates
 *   a region of block entities via lodestone BlockEntityHelper.getBlockEntitiesStream.
 *   The per-chunk mapper lambda$getBlockEntitiesStream$4 does:
 *       level.getChunk(new BlockPos(x, 0, z)).getBlockEntitiesPos().stream()
 *   i.e. Level.getChunk(FULL, create=true) for EVERY chunk in range. For an unloaded
 *   far chunk this hits ServerChunkCache.getChunk → MainThreadExecutor.managedBlock →
 *   waitForTasks → LockSupport.parkNanos on the SERVER THREAD, freezing the tick until
 *   the distant chunk generates (10s+), tripping the Watchdog.
 *
 * Fix: intercept the per-chunk mapper at HEAD. Delegate to
 *   {@link MainThreadChunkLoad#shouldServeUnloaded}: off-thread or when the chunk cannot be
 *   brought to FULL within a small budget, return an empty stream so the region scan simply
 *   skips that chunk instead of force-loading it. A Weeping Well reacting only to already-
 *   loaded block entities near a player is semantically fine — an unloaded far chunk has no
 *   relevant loaded state. Chunks that are already loaded (or cheaply loadable from disk)
 *   proceed through the vanilla path unchanged.
 *
 * The x/z the lambda receives are block coordinates aligned to chunk boundaries; the vanilla
 * Level.getChunk(BlockPos) maps them via >>4 to chunk coords, so we do the same.
 *
 * remap = false: BlockEntityHelper is a third-party class; its (synthetic lambda) method name
 * is not remapped. Targeted at the fixed lodestone 1.8.2 jar.
 */
@Mixin(targets = "team.lodestar.lodestone.helpers.block.BlockEntityHelper", remap = false)
public abstract class LodestoneBlockEntityStreamChunkGuardMixin {

    @Inject(
        method = "lambda$getBlockEntitiesStream$4(Lnet/minecraft/world/level/Level;Ljava/lang/Integer;Ljava/lang/Integer;)Ljava/util/stream/Stream;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void voidrp_guardRegionChunkLoad(
            Level level, Integer x, Integer z, CallbackInfoReturnable<Stream<?>> cir) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = new BlockPos(x, 0, z).getX() >> 4;
        int cz = new BlockPos(x, 0, z).getZ() >> 4;
        if (MainThreadChunkLoad.shouldServeUnloaded(serverLevel, cx, cz)) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] Lodestone block-entity stream guard — chunk [{},{}] not loaded " +
                    "— skipping (empty stream) to prevent main-thread chunk-load deadlock{}",
                    cx, cz, suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(Stream.empty());
        }
    }
}
