package ru.voidrp.asyncai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offloads ChunkActivityTracker's GZIP save off the server thread.
 *
 * Root cause (Watchdog dump 2026-05-29 14:40):
 *   MinecraftServer.saveEverything() → [ChunkActivityTracker mixin endSave]
 *   → ChunkActivityMap.instances.forEach(ChunkActivityMap::save)
 *   → ChunkActivityMap.save() → GZIPOutputStream → Deflater.deflateBytesBytes [native]
 *   Overworld had 106 006 tracked chunks; GZIP compression blocked the main thread >10 s.
 *
 * Fix: cancel synchronous save() on the main thread, re-invoke it from a daemon background
 * thread. ThreadLocal VOIDRP_ASYNC lets the background re-entry bypass cancellation so the
 * original save logic runs unchanged. Per-instance AtomicBoolean prevents save pile-up when
 * autosave fires faster than background saves complete (saves are skipped, not queued).
 */
@Mixin(targets = "toni.chunkactivitytracker.data.ChunkActivityMap", remap = false)
public class ChunkActivityMapAsyncSaveMixin {

    @Unique
    private static final ExecutorService VOIDRP_SAVER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voidrp-chunk-activity-saver");
        t.setDaemon(true);
        return t;
    });

    @Unique
    private static final ThreadLocal<Boolean> VOIDRP_ASYNC = ThreadLocal.withInitial(() -> false);

    @Unique
    private AtomicBoolean voidrp$saveScheduled = new AtomicBoolean(false);

    @Inject(method = "save", at = @At("HEAD"), cancellable = true, require = 0)
    private void voidrp_asyncSave(CallbackInfo ci) {
        if (Boolean.TRUE.equals(VOIDRP_ASYNC.get())) {
            return; // re-entry from background thread: let original save() run normally
        }
        ci.cancel();
        if (!voidrp$saveScheduled.compareAndSet(false, true)) {
            return; // save already scheduled/in-flight for this dimension, skip this trigger
        }
        Object self = this;
        VOIDRP_SAVER.submit(() -> {
            VOIDRP_ASYNC.set(true);
            try {
                self.getClass().getMethod("save").invoke(self);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                VoidRpAsyncAI.LOGGER.error("[VoidRP] ChunkActivityTracker async save failed",
                    cause != null ? cause : e);
            } catch (Exception e) {
                VoidRpAsyncAI.LOGGER.error("[VoidRP] ChunkActivityTracker async save failed", e);
            } finally {
                VOIDRP_ASYNC.set(false);
                voidrp$saveScheduled.set(false);
            }
        });
    }
}
