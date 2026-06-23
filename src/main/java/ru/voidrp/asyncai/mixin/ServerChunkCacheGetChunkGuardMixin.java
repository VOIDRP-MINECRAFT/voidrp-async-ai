package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards ServerChunkCache.getChunk() from non-main threads against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-05-26 23:59):
 *   Supplementaries pool-33-thread-1 called ServerChunkCache.getChunk(x, z, FULL, true)
 *   for a structure search from BlockPos{x=-26123, y=83, z=11285}.
 *   Vanilla getChunk() on a non-main thread submits lambda$getChunk$0 to MainThreadExecutor
 *   and blocks the background thread via CF.join(). The main server thread later picks up
 *   the lambda during tickServer → runAllTasks → doRunTask, then re-enters getChunk() on
 *   the main thread which calls managedBlock() → waitForTasks() → parkNanos() waiting for
 *   the distant unloaded chunk to generate. Generation for a never-visited chunk 26 km out
 *   may take 10–60+ seconds (terrain + FeatureRecycler + structure placement), causing the
 *   Watchdog to fire.
 *
 * Fix: intercept getChunk() at HEAD when called from a non-main thread. Use getChunkNow()
 * (immediate, non-blocking) instead. If the chunk is loaded at full status, return it.
 * Otherwise return null — the CF lambda is never submitted to MainThreadExecutor, so the
 * main server thread is never burdened with the blocking chunk load. Callers doing structure
 * searches (Supplementaries, etc.) must handle null chunks (unloaded → no structure there),
 * which is semantically valid for background searches.
 *
 * Existing guards that cover related paths but NOT this one:
 *   BlockCollisionsChunkGuardMixin  — guards BlockCollisions.getChunk → getChunkForCollisions
 *   MahouChunkGuardMixin            — guards Mahou sendChunkMahouPackets
 *   PlayerTravelChunkGuardMixin     — guards Entity.getOnPos → Level.getBlockState
 *   LivingEntityTravelGuardMixin    — guards LivingEntity.travel → Level.getBlockState
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheGetChunkGuardMixin {

    @Shadow
    public abstract LevelChunk getChunkNow(int chunkX, int chunkZ);

    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardNonMainThreadGetChunk(
            int x, int z, ChunkStatus status, boolean create,
            CallbackInfoReturnable<ChunkAccess> cir) {
        // Only guard non-main thread calls. The server main thread is always named
        // "Server thread" in vanilla, Paper, Mohist, and Youer.
        if ("Server thread".equals(Thread.currentThread().getName())) {
            return;
        }
        // Non-blocking immediate lookup — never blocks the calling thread.
        LevelChunk chunk = this.getChunkNow(x, z);
        if (chunk != null) {
            // LevelChunk is always at FULL status; satisfies any status requirement.
            cir.setReturnValue(chunk);
            return;
        }
        long suppressed = ChunkWarnRateLimit.acquire(x, z);
        if (suppressed >= 0) {
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] ServerChunkCache non-main-thread getChunk guard — chunk [{},{}] not immediately available" +
                " — returning null to prevent main-thread deadlock{}",
                x, z, suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
        }
        cir.setReturnValue(null);
    }
}
