package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Entity.spawnSprintParticle() against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-05-26 20:13):
 *   ServerPlayer.doTick → Player.tick → Entity.baseTick → Entity.spawnSprintParticle(1764)
 *   → Level.getBlockState → Level.getChunk(398) → ServerChunkCache.getChunk(202)
 *   → ServerChunkCache$MainThreadExecutor.managedBlock → LockSupport.parkNanos — HANGS.
 *
 * Trigger: player sprinting after a /back teleport to chunk [1256,2498] which had not yet
 * finished generating. spawnSprintParticle reads the block below the player feet to pick a
 * particle texture; it calls Level.getBlockState with require=true, blocking the main thread.
 *
 * Fix: redirect the single Level.getBlockState() call inside spawnSprintParticle to use
 * getChunkNow() (immediate, non-blocking). If the chunk is not available, return AIR —
 * the particle simply won't spawn for that tick, which is a purely cosmetic miss.
 */
@Mixin(Entity.class)
public abstract class SprintParticleChunkGuardMixin {

    @Redirect(
        method = "spawnSprintParticle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0
    )
    private BlockState voidrp_safeGetBlockStateInSprintParticle(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    if (suppressed > 0) {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] spawnSprintParticle guard — chunk [{},{}] not immediately available " +
                            "at block pos {},{},{} — returning AIR to prevent main-thread deadlock (+{} suppressed)",
                            cx, cz, pos.getX(), pos.getY(), pos.getZ(), suppressed);
                    } else {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] spawnSprintParticle guard — chunk [{},{}] not immediately available " +
                            "at block pos {},{},{} — returning AIR to prevent main-thread deadlock",
                            cx, cz, pos.getX(), pos.getY(), pos.getZ());
                    }
                }
                return Blocks.AIR.defaultBlockState();
            }
            return chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }
}
