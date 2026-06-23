package ru.voidrp.asyncai.mixin;

import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Guards LevelChunk.unloadCallback() against indefinite main-thread deadlock caused by
 * the Citizens plugin calling CraftChunk.getEntities() during chunk unload.
 *
 * Root cause (Watchdog dump 2026-05-24, server frozen 160-200+ seconds):
 *   DistanceManager.runAllUpdates
 *   → ChunkHolder.callEventIfUnloading
 *   → LevelChunk.unloadCallback          ← this method (Mohist/Paper patch)
 *   → ChunkUnloadEvent (Bukkit)
 *   → Citizens EventListen.onChunkUnload(EventListen.java:278)
 *   → CraftChunk.getEntities(CraftChunk.java:165)
 *   → LockSupport.parkNanos              ← BLOCKS INDEFINITELY (3+ minutes)
 *
 * CraftChunk.getEntities() in Paper's async entity loading waits for entity region data
 * that never arrives because the main thread is the gating thread for that very load.
 *
 * Fix: when unloadCallback() is called on the main server thread, offload it to a daemon
 * thread and block with a 2-second timeout. Citizens can call getEntities() without
 * deadlocking the server; if it still blocks past the timeout, the server continues and
 * the background thread finishes cleanup when the entity loader eventually unblocks.
 *
 * Note: unloadCallback() is a Mohist/Paper patch on LevelChunk — it does not exist in
 * vanilla NeoForge. We use require=0 (silently skip if absent) and reflection for the
 * background thread invocation to avoid compile-time dependency on the Mohist API.
 */
@Mixin(LevelChunk.class)
public abstract class CitizensChunkUnloadGuardMixin {

    @Unique
    private static final long MAX_WAIT_MS = 2_000;

    @Unique
    private static final ExecutorService VOIDRP_UNLOAD_EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "voidrp-chunk-unload");
        t.setDaemon(true);
        return t;
    });

    /**
     * Per-thread flag: true when unloadCallback() is executing on our background thread.
     * Prevents the inject from intercepting the re-invocation on that thread.
     */
    @Unique
    private static final ThreadLocal<Boolean> VOIDRP_IN_ASYNC =
            ThreadLocal.withInitial(() -> false);

    @Shadow public net.minecraft.world.level.Level level;

    @Inject(method = "unloadCallback", at = @At("HEAD"), cancellable = true, require = 0)
    private void voidrp_asyncChunkUnloadCallback(CallbackInfo ci) {
        // Already executing on our background thread — let the real body proceed.
        if (VOIDRP_IN_ASYNC.get()) return;

        // Only intercept calls from the main server thread.
        // Use thread name: reliable without holding a MinecraftServer reference.
        if (!"Server thread".equals(Thread.currentThread().getName())) return;

        final LevelChunk self = (LevelChunk)(Object)this;
        final int cx = self.getPos().x;
        final int cz = self.getPos().z;

        // Reflective lookup is cached by JVM after first call; no meaningful overhead.
        Method unloadMethod;
        try {
            unloadMethod = self.getClass().getMethod("unloadCallback");
        } catch (NoSuchMethodException e) {
            // Not running on Mohist/Paper hybrid — the mixin should not have fired,
            // but if it did, skip gracefully without cancelling.
            return;
        }

        final Method method = unloadMethod;

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            VOIDRP_IN_ASYNC.set(true);
            try {
                method.invoke(self);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                String msg = cause != null ? cause.getMessage() : e.getMessage();
                // Citizens rejects async event invocation — this is expected; suppress the noise.
                if (msg != null && msg.contains("may only be triggered synchronously")) return;
                VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] CitizensChunkUnloadGuard — exception in unloadCallback chunk [{},{}]: {}",
                        cx, cz, msg);
            } catch (Exception e) {
                VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] CitizensChunkUnloadGuard — reflection error for chunk [{},{}]: {}",
                        cx, cz, e.getMessage());
            } finally {
                VOIDRP_IN_ASYNC.remove();
            }
        }, VOIDRP_UNLOAD_EXEC);

        try {
            future.get(MAX_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] CitizensChunkUnloadGuard — chunk [{},{}] unload callback blocked >{}ms " +
                    "(Citizens/CraftChunk.getEntities deadlock) — releasing main thread. " +
                    "Background thread will finish when entity loader unblocks.",
                    cx, cz, MAX_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] CitizensChunkUnloadGuard — wait error for chunk [{},{}]: {}",
                    cx, cz, e.getMessage());
        }

        // Prevent the original body from also running on the main thread.
        ci.cancel();
    }
}
