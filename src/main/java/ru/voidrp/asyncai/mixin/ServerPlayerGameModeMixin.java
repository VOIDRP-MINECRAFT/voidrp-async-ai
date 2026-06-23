package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
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
 * Guards ServerPlayerGameMode.tick() against main-thread chunk-load deadlock.
 *
 * Root cause (Watchdog dump 2026-06-02 13:54):
 *   During entity ticking a ServerPlayer.tick() → ServerPlayerGameMode.tick() calls
 *   Level.getBlockState(delayedDestroyPos) to check if the block being mined is still there.
 *   If the chunk containing that position is not yet fully loaded, the call falls through to
 *   ServerChunkCache.getChunk(FULL, true) → MainThreadExecutor.managedBlock()
 *   → LockSupport.parkNanos() — main thread parks indefinitely. Watchdog fires after 10 s.
 *
 * Fix: @Redirect replaces the blocking Level.getBlockState() call with a non-blocking version.
 * We try getChunkNow() (immediate cache lookup). If the chunk is available, read blockState
 * directly from the LevelChunk (plain array access, guaranteed non-blocking).
 * If the chunk is not loaded, return AIR — tick() treats this as the block having been removed
 * and aborts the delayed destroy for that tick. Safe: the player's mining progress is simply
 * paused for one tick until the chunk loads.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0
    )
    private BlockState voidrp_safeGetBlockStateInGameModeTick(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] ServerPlayerGameMode.tick guard — chunk [{},{}] not immediately available " +
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
