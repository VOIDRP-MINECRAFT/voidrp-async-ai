package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import ru.voidrp.asyncai.VoidRpAsyncAI;
import ru.voidrp.asyncai.VoidRpBoundedChunkLoader;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

/**
 * Implements {@link VoidRpBoundedChunkLoader} on ServerChunkCache so the main-thread
 * getBlockState / getBlockEntity guards can perform a time-bounded chunk load.
 *
 * Why not just call {@code getChunkFuture(...)}? On the main thread that method blocks
 * UNCONDITIONALLY via {@code mainThreadProcessor.managedBlock(future::isDone)} — i.e. it
 * is exactly the unbounded park that trips the Watchdog on distant/ungenerated chunks.
 * Here we call the (private) {@code getChunkFutureMainThread}, which only schedules the
 * load and returns the pending future, then drive the same executor with a DEADLINE so
 * the block is bounded.
 *
 * {@code mainThreadProcessor} is a private field whose type is a package-private inner
 * class ({@code ServerChunkCache$MainThreadExecutor extends BlockableEventLoop<Runnable>}),
 * so it cannot be @Shadow-ed with a matching descriptor from our package. We read it once
 * via reflection (runtime is Mojang-mapped, so the field name is stable) and cache it.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheBoundedLoadMixin implements VoidRpBoundedChunkLoader {

    @Shadow
    public abstract LevelChunk getChunkNow(int chunkX, int chunkZ);

    @Shadow
    protected abstract CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(
            int chunkX, int chunkZ, ChunkStatus status, boolean create);

    @Unique
    private static volatile Field voidrp$procField;

    @Unique
    private static volatile boolean voidrp$procFieldFailed;

    @Override
    @Unique
    public LevelChunk voidrp$loadChunkBounded(int cx, int cz, long budgetNs) {
        LevelChunk present = this.getChunkNow(cx, cz);
        if (present != null) {
            return present;
        }
        BlockableEventLoop<?> processor = voidrp$mainThreadProcessor();
        if (processor == null) {
            return null; // reflection unavailable — degrade to "not loaded"
        }
        try {
            CompletableFuture<ChunkResult<ChunkAccess>> future =
                    this.getChunkFutureMainThread(cx, cz, ChunkStatus.FULL, true);
            long deadline = System.nanoTime() + budgetNs;
            // Same task-pumping loop vanilla uses in getChunk, but bounded by our deadline.
            processor.managedBlock(() -> future.isDone() || System.nanoTime() >= deadline);
            if (future.isDone()) {
                ChunkResult<ChunkAccess> result = future.getNow(null);
                if (result != null && result.isSuccess()) {
                    ChunkAccess access = result.orElse(null);
                    if (access instanceof LevelChunk levelChunk) {
                        return levelChunk;
                    }
                }
            }
        } catch (Throwable t) {
            // Never let a guard-driven load throw into the caller — fall back to "not loaded".
            VoidRpAsyncAI.LOGGER.debug("[VoidRP] bounded chunk load [{},{}] failed", cx, cz, t);
        }
        return null;
    }

    @Unique
    private BlockableEventLoop<?> voidrp$mainThreadProcessor() {
        Field field = voidrp$procField;
        if (field == null) {
            if (voidrp$procFieldFailed) {
                return null;
            }
            try {
                field = ServerChunkCache.class.getDeclaredField("mainThreadProcessor");
                field.setAccessible(true);
                voidrp$procField = field;
            } catch (Throwable t) {
                voidrp$procFieldFailed = true;
                VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] could not access ServerChunkCache.mainThreadProcessor — " +
                        "bounded chunk load disabled, guards fall back to AIR/null", t);
                return null;
            }
        }
        try {
            Object value = field.get(this);
            return value instanceof BlockableEventLoop<?> loop ? loop : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
