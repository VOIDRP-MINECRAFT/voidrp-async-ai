package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Swet.checkSwetSpawnRules() against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-05-30 20:21):
 *   NaturalSpawner.spawnCategoryForChunk() → spawnCategoryForPosition()
 *   → SpawnPlacements.checkSpawnRules() → Swet.checkSwetSpawnRules()
 *   → Swet.inRadiusOfBanner() → Level.getChunk(int,int)
 *   → ServerChunkCache.getChunk(FULL, true) → MainThreadExecutor.managedBlock()
 *   → LockSupport.parkNanos()
 *   Swet.inRadiusOfBanner() scans a ~32-block radius for Aether banners;
 *   for chunks near the boundary of loaded/unloaded area getChunk() blocks
 *   the main thread waiting for async chunk generation.
 *
 * Fix: @Inject at HEAD of checkSwetSpawnRules checks getChunkNow() for all
 * chunks in a 3-chunk radius around the spawn position (covering the 32-block
 * banner scan radius). If any chunk is not immediately available, cancel with
 * false — the Swet spawn attempt is skipped for this tick. Swets will still
 * spawn on the next tick once all surrounding chunks are loaded.
 */
@Mixin(targets = "com.aetherteam.aether.entity.monster.Swet", remap = false)
public abstract class SwetSpawnChunkGuardMixin {

    @Inject(
        method = "checkSwetSpawnRules",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void voidrp_guardSwetSpawnChunks(
            EntityType<?> type,
            LevelAccessor level,
            MobSpawnType spawnType,
            BlockPos pos,
            RandomSource random,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        ServerChunkCache cache = serverLevel.getChunkSource();
        int spawnCX = pos.getX() >> 4;
        int spawnCZ = pos.getZ() >> 4;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int cx = spawnCX + dx;
                int cz = spawnCZ + dz;
                LevelChunk chunk = cache.getChunkNow(cx, cz);
                if (chunk == null) {
                    long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                    if (suppressed >= 0) {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] Swet spawn chunk guard — chunk [{},{}] not loaded " +
                            "near spawn pos {},{},{} — skipping to prevent main-thread deadlock{}",
                            cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                            suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
                    }
                    cir.setReturnValue(false);
                    return;
                }
            }
        }
    }
}
