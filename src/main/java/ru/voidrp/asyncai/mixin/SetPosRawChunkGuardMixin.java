package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Entity.setPosRaw() against main-thread deadlock from NeoForge's "ensure chunk loaded" patch.
 *
 * Root cause (Watchdog dump 2026-05-27 17:03):
 *   SkinsRestorer.applySkin → CraftPlayer.setPlayerProfile → CraftPlayer.refreshPlayer
 *   → ServerGamePacketListenerImpl.internalTeleport → Entity.moveTo → Entity.setPosRaw
 *   → this.level.getChunk(cx, cz)   NeoForge patch at Entity.java:3384:
 *       "Forge - ensure target chunk is loaded"
 *   → Level.getChunk(int,int) → ServerChunkCache.getChunk(FULL, require=true)
 *   → managedBlock() → parkNanos() → BLOCKS.
 *
 * The NeoForge patch calls Level.getChunk() as a fire-and-forget side-effect to ensure the
 * destination chunk is loaded. The return value is DISCARDED in setPosRaw, so returning null
 * here is completely safe — no NPE, no broken entity tracking.
 *
 * Trigger: player IncX logged in at X=-26236 Z=11122 (chunk -1640,695, ~26 km from spawn).
 * SkinsRestorer applied skin on login, calling refreshPlayer → internalTeleport which tried
 * to block-load that distant ungenerated chunk, hanging the server thread for >15 s.
 *
 * Fix: redirect Level.getChunk(int,int) inside setPosRaw to use getChunkNow() (non-blocking).
 * If the chunk is not immediately available, return null — safe because Entity.setPosRaw
 * discards the return value entirely. The Forge chunk-preload side-effect is skipped.
 */
@Mixin(Entity.class)
public abstract class SetPosRawChunkGuardMixin {

    @Redirect(
        method = "setPosRaw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
        ),
        require = 0
    )
    private LevelChunk voidrp_safeGetChunkInSetPosRaw(Level level, int cx, int cz) {
        if (level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    if (suppressed > 0) {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] setPosRaw chunk guard — chunk [{},{}] not immediately available" +
                            " — skipping Forge chunk preload to prevent main-thread deadlock (+{} suppressed)",
                            cx, cz, suppressed);
                    } else {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] setPosRaw chunk guard — chunk [{},{}] not immediately available" +
                            " — skipping Forge chunk preload to prevent main-thread deadlock",
                            cx, cz);
                    }
                }
            }
            return chunk; // null is safe — return value is discarded in Entity.setPosRaw:3384
        }
        return level.getChunk(cx, cz);
    }
}
