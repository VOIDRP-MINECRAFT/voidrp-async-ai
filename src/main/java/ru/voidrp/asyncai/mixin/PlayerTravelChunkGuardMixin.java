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
 * Guards Entity.getOnPos(float) against main-thread deadlock during LivingEntity.travel().
 *
 * Root cause: travel() → getBlockPosBelowThatAffectsMyMovement() → getOnPos(float)
 * calls Level.getBlockState() → ServerChunkCache.getChunk(FULL, true) → managedBlock().
 * If the chunk is still being generated, managedBlock() parks the main thread via
 * parkNanos() until the chunk finishes — watchdog fires after 10 s.
 *
 * Previous approach (@Inject HEAD with getChunkNow() guard) had a gap: getChunkNow()
 * returned non-null (chunk appeared FULL in one cache) but the subsequent getChunk(FULL,true)
 * still blocked because it uses a different code path / status check in Paper/Youer.
 *
 * Fix: @Redirect replaces the Level.getBlockState() call inside getOnPos() entirely.
 * We call getChunkNow() and, if the chunk is immediately available, call chunk.getBlockState()
 * DIRECTLY on the LevelChunk object — this is a plain array read, guaranteed non-blocking.
 * If getChunkNow() returns null, we return AIR (movement modifier skipped for one tick).
 * The blocking ServerChunkCache.getChunk(FULL, true) path is never reached.
 */
@Mixin(Entity.class)
public abstract class PlayerTravelChunkGuardMixin {

    @Redirect(
        method = "getOnPos(F)Lnet/minecraft/core/BlockPos;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0
    )
    private BlockState voidrp_safeGetBlockStateInGetOnPos(Level level, BlockPos pos) {
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
                            "[VoidRP] getOnPos redirect guard — chunk [{},{}] not immediately available " +
                            "at block pos {},{},{} — returning AIR to prevent main-thread deadlock (+{} suppressed)",
                            cx, cz, pos.getX(), pos.getY(), pos.getZ(), suppressed);
                    } else {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] getOnPos redirect guard — chunk [{},{}] not immediately available " +
                            "at block pos {},{},{} — returning AIR to prevent main-thread deadlock",
                            cx, cz, pos.getX(), pos.getY(), pos.getZ());
                    }
                }
                return Blocks.AIR.defaultBlockState();
            }
            // Direct read from the LevelChunk object — never blocks.
            return chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }
}
