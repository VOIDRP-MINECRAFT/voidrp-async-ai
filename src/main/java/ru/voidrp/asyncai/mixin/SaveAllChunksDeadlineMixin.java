package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.function.BooleanSupplier;

/**
 * Limits total time spent in the per-chunk managedBlock loop inside ChunkMap.saveAllChunks.
 *
 * Root cause (Watchdog dump 2026-06-05 20:13):
 *   /save-all triggers ChunkMap.saveAllChunks(flush=true) which iterates 127K+ overworld
 *   chunks, calling ServerChunkCache$MainThreadExecutor.managedBlock(future::isDone) for
 *   each one. With 127K chunks this blocks the main thread for ~15 s before even reaching
 *   ChunkStorage.flushWorker(). ChunkFlushWorkerTimeoutMixin guards flushWorker (8 s), but
 *   the chunk loop itself pushed total save time to 24 s — triggering the Youer Watchdog
 *   stop at the 30 s threshold before the server could recover.
 *
 * Fix: track a per-invocation deadline (5 s from start of saveAllChunks). Each call to
 * managedBlock inside lambda$saveAllChunks$8 checks the deadline; once exceeded it returns
 * immediately. Remaining chunks are serialised asynchronously and the IO worker (guarded by
 * ChunkFlushWorkerTimeoutMixin) flushes them. Data is not lost — chunks are still queued
 * for the IO worker, the main thread just stops waiting synchronously.
 *
 * Expected max blocking time after this fix:
 *   chunk loop: ≤5 s + flushWorker: ≤8 s = ≤13 s total — well under 30 s watchdog limit.
 */
@Mixin(ChunkMap.class)
public abstract class SaveAllChunksDeadlineMixin {

    private static final ThreadLocal<Long> SAVE_ALL_DEADLINE_NS = new ThreadLocal<>();

    @Inject(
        method = "saveAllChunks",
        at = @At("HEAD"),
        require = 0
    )
    private void voidrp_setSaveAllDeadline(boolean flush, CallbackInfo ci) {
        if (flush) {
            SAVE_ALL_DEADLINE_NS.set(System.nanoTime() + 5_000_000_000L);
        }
    }

    @Inject(
        method = "saveAllChunks",
        at = @At("RETURN"),
        require = 0
    )
    private void voidrp_clearSaveAllDeadline(boolean flush, CallbackInfo ci) {
        SAVE_ALL_DEADLINE_NS.remove();
    }

    /**
     * Redirects the managedBlock call inside lambda$saveAllChunks$8.
     * If the 5-second deadline has passed, skip the blocking wait and return immediately.
     */
    @Redirect(
        method = "lambda$saveAllChunks$8",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/thread/BlockableEventLoop;managedBlock(Ljava/util/function/BooleanSupplier;)V"
        ),
        require = 0
    )
    private void voidrp_timedChunkSaveManagedBlock(BlockableEventLoop<?> executor, BooleanSupplier condition) {
        Long deadline = SAVE_ALL_DEADLINE_NS.get();
        if (deadline != null && System.nanoTime() > deadline) {
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] save-all: chunk loop deadline exceeded (5 s) — " +
                "skipping managedBlock, remaining chunks will flush via IO worker"
            );
            return;
        }
        executor.managedBlock(condition);
    }
}
