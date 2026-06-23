package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
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
 * Guards PlayerList.respawn() against main-thread chunk-load deadlock.
 *
 * Root cause (Watchdog dump 2026-05-31 18:19:02):
 *   Player clicked Respawn after death → ServerboundClientCommandPacket.handle()
 *   → PlayerList.respawn(line 898) → PlayerList.respawn(line 989)
 *   → Level.getBlockState(Level.java:788) → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → LockSupport.parkNanos() — server frozen 14+ s.
 *
 *   This is a DIFFERENT call site from findRespawnAndUseSpawnBlock (guarded by
 *   FindRespawnChunkGuardMixin). PlayerList.respawn() itself calls Level.getBlockState()
 *   at line ~989 to verify the respawn block after the spawn position is determined.
 *
 * Fix: @Redirect all Level.getBlockState() calls inside respawn() to use getChunkNow().
 * If the chunk is not loaded, return AIR — respawn treats unknown blocks as passable,
 * placing the player at world spawn instead of hanging the server indefinitely.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnChunkGuardMixin {

    @Redirect(
        method = "respawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0
    )
    private BlockState voidrp_safeGetBlockStateInRespawn(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] PlayerList.respawn chunk guard — chunk [{},{}] not loaded " +
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
