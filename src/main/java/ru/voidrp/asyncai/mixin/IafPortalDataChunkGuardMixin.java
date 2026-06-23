package ru.voidrp.asyncai.mixin;

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
 * Guards IceandFire PortalData.tick() against main-thread chunk-load deadlock.
 *
 * Root cause (Watchdog dump 2026-05-29 16:15):
 *   ServerLevel.tick() → entity tick loop → IafAttachments.onLivingTick()
 *   → IafAttachments.tickAndSync() → PortalData.tick(PortalData.java:60)
 *   → Level.getBlockState() → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → spin-wait for chunk deserialization
 *   PortalData tracks the Ice Dragon / Fire Dragon portal blocks each tick
 *   and queries blocks in potentially unloaded chunks, hanging the main thread.
 *
 * Fix: redirect Level.getBlockState() inside PortalData.tick() to a
 * non-blocking version using getChunkNow(). If the chunk is not loaded,
 * return AIR — portal sees no block, skips safely until chunk loads.
 */
@Mixin(targets = "com.iafenvoy.iceandfire.data.component.PortalData", remap = false)
public abstract class IafPortalDataChunkGuardMixin {

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0,
        remap = true
    )
    private BlockState voidrp_safeGetBlockStateInPortalTick(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] IafPortalData chunk guard — chunk [{},{}] not loaded " +
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
