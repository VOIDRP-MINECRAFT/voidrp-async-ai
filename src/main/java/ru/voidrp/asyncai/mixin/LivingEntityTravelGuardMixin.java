package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
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
 * Guards LivingEntity.travel() against main-thread deadlock when the else-branch
 * calls Level.getBlockState(getBlockPosBelowThatAffectsMyMovement()) to obtain
 * friction, and the target chunk is not yet fully loaded — causing
 * ServerChunkCache.getChunk(FULL, true) → managedBlock() → parkNanos() to block
 * the server thread indefinitely (watchdog fires after 10 s).
 *
 * The existing PlayerTravelChunkGuardMixin guards the getOnPos(F)→getBlockState
 * path; this mixin guards the separate direct call inside travel() itself.
 * Returning AIR skips friction for one tick — negligible physics cost vs. freeze.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTravelGuardMixin {

    @Redirect(
        method = "travel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0
    )
    private BlockState voidrp_safeGetBlockStateInTravel(Level level, BlockPos pos) {
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
                            "[VoidRP] travel() getBlockState guard — chunk [{},{}] not loaded " +
                            "at block pos {},{},{} — returning AIR (friction=default) to prevent deadlock (+{} suppressed)",
                            cx, cz, pos.getX(), pos.getY(), pos.getZ(), suppressed);
                    } else {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] travel() getBlockState guard — chunk [{},{}] not loaded " +
                            "at block pos {},{},{} — returning AIR (friction=default) to prevent deadlock",
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
