package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards PoiManager.ensureLoadedAndValid() against main-thread chunk-load deadlock.
 *
 * Root cause (Watchdog dumps 2026-05-30 14:31 and 18:54):
 *   Entity.postTick() → Entity.handlePortal() → PortalProcessor.getPortalDestination()
 *   → [Aether|Vanilla]PortalForcer.findClosestPortalPosition()
 *   → PoiManager.ensureLoadedAndValid(268) → lambda$ensureLoadedAndValid$33
 *   → LevelReader.getChunk(II) → ServerChunkCache.getChunk(202)
 *   → MainThreadExecutor.managedBlock() → LockSupport.parkNanos → BLOCKS.
 *
 * ensureLoadedAndValid iterates SectionPos in a search radius (~17x17 chunks) and
 * calls getChunk() to force-load POI data.  On the main thread this blocks for any
 * chunk that is not yet loaded, causing Watchdog to fire.
 *
 * Previous fix used @Redirect inside lambda$ensureLoadedAndValid$33 but the injection
 * silently failed (require=0 suppressed the error; likely the lambda redirect target
 * cannot be resolved by the Mixin framework without a refmap entry for a synthetic
 * lambda method).
 *
 * Fix: @Inject at HEAD into ensureLoadedAndValid with cancellation on the main thread.
 * For already-loaded chunks, their POI is already tracked and the cancel is a no-op
 * (the !hasLoadedPoi filter would have skipped them anyway).
 * For unloaded chunks, they are skipped — their POI loads naturally when those chunks
 * are loaded by normal game flow.  No portals are missed in loaded areas; new portals
 * may be created for unloaded destination areas, but that avoids the hang.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerChunkGuardMixin {

    @Inject(
        method = "ensureLoadedAndValid",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardEnsureLoadedAndValidOnMainThread(
            LevelReader level, BlockPos pos, int radius, CallbackInfo ci) {
        if (!(level instanceof ServerLevel)) return;
        if (!"Server thread".equals(Thread.currentThread().getName())) return;

        // The lambda inside calls LevelReader.getChunk(II) which blocks via
        // ServerChunkCache.managedBlock() for unloaded chunks on the main thread.
        ci.cancel();

        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
        if (suppressed >= 0) {
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] PoiManager.ensureLoadedAndValid cancelled on main thread " +
                "at pos {},{},{} radius {} — prevented main-thread chunk-load deadlock{}",
                pos.getX(), pos.getY(), pos.getZ(), radius,
                suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
        }
    }
}
