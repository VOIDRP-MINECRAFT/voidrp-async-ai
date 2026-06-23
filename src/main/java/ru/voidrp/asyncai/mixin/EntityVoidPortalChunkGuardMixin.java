package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards EntityVoidPortal.clearObstructions() against main-thread chunk-load deadlock.
 *
 * Root cause (Watchdog dump 2026-05-31 17:35):
 *   ServerLevel.tick() → EntityVoidPortal.tick() (EntityVoidPortal.java:175)
 *   → EntityVoidPortal.clearObstructions() (EntityVoidPortal.java:209)
 *   → Level.destroyBlock() → Level.getBlockState()
 *   → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → LockSupport.parkNanos()
 *   The portal entity tries to destroy blocks around itself; if those blocks are in
 *   unloaded chunks, getChunk() parks the main thread waiting for chunk deserialization.
 *
 * Fix: redirect Level.destroyBlock() inside clearObstructions() to a non-blocking
 * version. Check chunk availability with getChunkNow(). If the chunk is not loaded,
 * skip the block destruction (return false) — the portal stays, blocks remain, no hang.
 */
@Mixin(targets = "com.github.alexthe666.alexsmobs.entity.EntityVoidPortal", remap = false)
public abstract class EntityVoidPortalChunkGuardMixin {

    @Redirect(
        method = "clearObstructions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"
        ),
        require = 0,
        remap = true
    )
    private boolean voidrp_safeDestroyBlockInClearObstructions(Level level, BlockPos pos, boolean drop) {
        if (level instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] EntityVoidPortal clearObstructions chunk guard — chunk [{},{}] not loaded " +
                        "at block pos {},{},{} — skipping destroyBlock to prevent main-thread deadlock{}",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                        suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
                }
                return false;
            }
        }
        return level.destroyBlock(pos, drop);
    }
}
