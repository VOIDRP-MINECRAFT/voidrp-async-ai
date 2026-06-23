package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;
import stepsword.mahoutsukai.networking.ChunkMahouRequestPacket;

/**
 * Guards ChunkMahouRequestPacket.sendChunkMahouPackets() against main-thread deadlock.
 *
 * Root cause (Watchdog 15-second dump 2026-05-20):
 *   ChunkMahouRequestPacket.sendChunkMahouPackets(44) calls Level.getChunk(x, z)
 *   → ServerChunkCache.getChunk(FULL, require=true) → managedBlock() → parkNanos()
 *   when the player's requested chunk is in an unloaded distant region (observed at
 *   chunk [-2615,-3844], world coords -41833,-61501).
 *
 * Fix: cancel sendChunkMahouPackets() entirely when the chunk is not immediately
 * available. The packet is a client-initiated request; the client will re-request
 * when it needs the data and the chunk will be loaded by then.
 */
@Mixin(value = ChunkMahouRequestPacket.class, remap = false)
public abstract class MahouChunkGuardMixin {

    @Inject(
        method = "sendChunkMahouPackets",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void voidrp_guardSendChunkMahou(
            ServerPlayer player, int x, int z, CallbackInfo ci) {
        if (!(player.level() instanceof ServerLevel sl)) return;
        if (sl.getChunkSource().getChunkNow(x, z) != null) return;

        long suppressed = ChunkWarnRateLimit.acquire(x, z);
        if (suppressed >= 0) {
            if (suppressed > 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] MahouChunkGuard — chunk [{},{}] not immediately available, " +
                    "skipping sendChunkMahouPackets to prevent main-thread deadlock (+{} suppressed)",
                    x, z, suppressed);
            } else {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] MahouChunkGuard — chunk [{},{}] not immediately available, " +
                    "skipping sendChunkMahouPackets to prevent main-thread deadlock",
                    x, z);
            }
        }
        ci.cancel();
    }
}
