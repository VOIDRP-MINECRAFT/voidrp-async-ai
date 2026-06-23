package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * Guards ServerPlayer.findRespawnAndUseSpawnBlock() against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-05-27 23:28):
 *   Player ran /home (EssentialsX), triggering PaperLib.getBedSpawnLocationAsync().
 *   On Youer/NeoForge 1.21.1, PaperLib falls back to BedSpawnLocationSync (synchronous path)
 *   which calls CraftPlayer.getBedSpawnLocation() → getRespawnLocation() on the main thread.
 *   This eventually calls ServerPlayer.findRespawnAndUseSpawnBlock() → Level.getBlockState()
 *   → ServerChunkCache.getChunk(FULL, true) → MainThreadExecutor.managedBlock()
 *   → LockSupport.parkNanos() — the main thread parks waiting for the respawn chunk to load
 *   from disk. Watchdog fires after 10+ s; server fully unresponsive for 30+ s.
 *
 * Fix: @Redirect replaces Level.getBlockState() inside findRespawnAndUseSpawnBlock with a
 * non-blocking version. We call getChunkNow() (immediate cache lookup only). If the chunk is
 * loaded, we read blockState directly from the LevelChunk (plain array access, never blocks).
 * If the chunk is not loaded, we return AIR — findRespawnAndUseSpawnBlock treats an
 * unrecognised block as "no valid respawn here" and returns Optional.empty(). EssentialsX
 * then falls back to teleporting the player to world spawn rather than hanging the server.
 *
 * findRespawnAndUseSpawnBlock is a static method in vanilla/NeoForge 1.21.1, so the
 * redirect handler is declared static accordingly. require=0 prevents hard crash if the
 * method signature differs in this build.
 */
@Mixin(ServerPlayer.class)
public abstract class FindRespawnChunkGuardMixin {

    @Redirect(
        method = "findRespawnAndUseSpawnBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0
    )
    private static BlockState voidrp_safeGetBlockStateInFindRespawn(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] findRespawnAndUseSpawnBlock guard — respawn chunk [{},{}] not loaded " +
                        "at block pos {},{},{} — returning AIR to prevent main-thread deadlock{}",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                        suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
                }
                return Blocks.AIR.defaultBlockState();
            }
            return chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }
}
