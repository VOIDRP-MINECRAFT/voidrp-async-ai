package ru.voidrp.asyncai.mixin;

import com.aetherteam.aether.block.portal.AetherPortalForcer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
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
 * Guards AetherPortalForcer.createPortal() against main-thread deadlock.
 *
 * Root cause (Watchdog 2026-05-26 14:10:38):
 *   Player.tick → handlePortal → AetherPortalBlock.getPortalDestination
 *   → AetherPortalBlock.getExitPortal(108) → AetherPortalForcer.createPortal(74)
 *   → LevelReader.isEmptyBlock → Level.getBlockState(730)
 *   → ServerChunkCache.getChunk(202) → managedBlock → LockSupport.parkNanos → BLOCKS.
 *
 * When a player enters an Aether portal, createPortal() scans nearby blocks to find
 * a suitable exit location. If destination chunks are not yet loaded, getBlockState()
 * parks the main thread waiting for chunk generation, triggering Watchdog.
 *
 * Fix: redirect Level.getBlockState() inside createPortal() to use getChunkNow().
 * If the chunk is not immediately available, return STONE (non-empty) so the forcer
 * skips that position. Portal creation defers naturally to the next tick; no deadlock.
 */
@Mixin(AetherPortalForcer.class)
public abstract class AetherPortalChunkGuardMixin {

    @Redirect(
        method = "createPortal",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0
    )
    private BlockState voidrp_safeGetBlockStateInCreatePortal(Level level, BlockPos pos) {
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
                            "[VoidRP] AetherPortalForcer.createPortal chunk guard — chunk [{},{}] not immediately available " +
                            "at block pos {},{},{} — returning STONE to prevent main-thread deadlock (+{} suppressed)",
                            cx, cz, pos.getX(), pos.getY(), pos.getZ(), suppressed);
                    } else {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] AetherPortalForcer.createPortal chunk guard — chunk [{},{}] not immediately available " +
                            "at block pos {},{},{} — returning STONE to prevent main-thread deadlock",
                            cx, cz, pos.getX(), pos.getY(), pos.getZ());
                    }
                }
                // Non-empty state causes the forcer to skip this position;
                // portal creation retries next tick once chunks are loaded.
                return Blocks.STONE.defaultBlockState();
            }
            return chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }
}
