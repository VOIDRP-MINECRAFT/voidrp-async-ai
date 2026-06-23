package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards CatSpawner.tick() against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-05-29 00:44):
 *   CatSpawner.tick() → SpawnPlacements.isSpawnPositionOk() (ON_GROUND type)
 *   → SpawnPlacementTypes$1.isSpawnPositionOk() → Level.getBlockState(pos.below())
 *   → ServerChunkCache.getChunk(FULL, true) → MainThreadExecutor.managedBlock()
 *   → LockSupport.parkNanos()
 *   The cat spawner checks a position in a chunk that is queued but not yet
 *   fully loaded; the main thread parks waiting for async chunk loading.
 *   Watchdog fires after 10 s; server unresponsive for 15-20 s.
 *
 * Fix: @Redirect replaces SpawnPlacements.isSpawnPositionOk() inside CatSpawner.tick()
 * with a guard that first checks getChunkNow() (non-blocking cache lookup). If the
 * chunk is not immediately available, return false — the cat spawn attempt is skipped
 * for this tick. Cats will still spawn on the next tick once the chunk is loaded.
 * If the chunk IS loaded, delegate to the original SpawnPlacements.isSpawnPositionOk()
 * which will find the chunk in cache immediately without blocking.
 */
@Mixin(CatSpawner.class)
public abstract class CatSpawnerChunkGuardMixin {

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/SpawnPlacements;isSpawnPositionOk(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"
        ),
        require = 0
    )
    private boolean voidrp_safeIsSpawnPositionOk(EntityType<?> entityType, LevelReader levelReader, BlockPos pos) {
        if (levelReader instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] CatSpawner chunk guard — chunk [{},{}] not loaded at pos {},{},{} " +
                        "— skipping spawn check to prevent main-thread deadlock{}",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                        suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
                }
                return false;
            }
        }
        return SpawnPlacements.isSpawnPositionOk(entityType, levelReader, pos);
    }
}
