package ru.voidrp.asyncai.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Prevents the Minecraft Watchdog from killing the server during /save-all flush.
 *
 * ChunkStorage.flushWorker() calls CompletableFuture.join() on the IO worker
 * while on the main thread. With 100K+ chunks (overworld) this blocks for 2-5 s,
 * exceeding the Youer/NeoForge Watchdog 10 s threshold and causing a forced shutdown.
 *
 * The redirect replaces the blocking join() with get(8s). If the IO worker finishes
 * within the timeout everything is correct. If it exceeds 8 s, main thread is released
 * and the IO worker continues flushing in the background — the chunks are not lost,
 * just not waited on.
 */
@Mixin(ChunkStorage.class)
public abstract class ChunkFlushWorkerTimeoutMixin {

    @Redirect(
        method = "flushWorker",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;"
        ),
        require = 0
    )
    private Object voidrp_joinWithTimeout(CompletableFuture<?> future) {
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] save-all: ChunkStorage.flushWorker().join() exceeded 8 s — " +
                "releasing main thread, IO worker continues in background"
            );
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            VoidRpAsyncAI.LOGGER.warn("[VoidRP] save-all: flushWorker join() error: {}", e.getMessage());
            return null;
        }
    }
}
