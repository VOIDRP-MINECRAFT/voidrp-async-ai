package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards BlockCollisions.getChunk() against main-thread deadlock.
 *
 * Root cause (visible in Watchdog 25-second dump):
 *   TeleportCommand.performTeleport(293) → Entity.setOnGround → Entity.checkSupportingBlock
 *   → CollisionGetter.findSupportingBlock → BlockCollisions.computeNext
 *   → BlockCollisions.getChunk(62) → Level.getChunkForCollisions(1219)
 *   → Level.getChunk(398) → ServerChunkCache.getChunk(202) → managedBlock → BLOCKS.
 *
 * In Youer, Level.getChunkForCollisions calls ServerChunkCache.getChunk with require=true,
 * which parks the main thread until the chunk finishes generating. After a teleport to a
 * distant unloaded region, the destination chunk and its neighbors may still be loading
 * (FeatureRecycler can take 800+ ms per chunk), triggering Watchdog after 15 s.
 *
 * Fix: redirect the CollisionGetter.getChunkForCollisions() call inside BlockCollisions.getChunk
 * to use ServerChunkCache.getChunkNow() — an immediate, non-blocking lookup. If the chunk
 * is not yet available, return null. BlockCollisions.computeNext() already handles null by
 * skipping that chunk column (vanilla null-check at computeNext line ~81), so no NPE occurs
 * and the entity simply has no collision shapes from the unloaded chunk for that tick.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsChunkGuardMixin<T> {

    @Redirect(
        method = "getChunk",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/CollisionGetter;getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;"
        ),
        require = 0
    )
    private BlockGetter voidrp_nonBlockingGetChunkForCollisions(CollisionGetter source, int cx, int cz) {
        if (source instanceof ServerLevel serverLevel) {
            ServerChunkCache cache = serverLevel.getChunkSource();
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
                if (suppressed >= 0) {
                    if (suppressed > 0) {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] BlockCollisions chunk guard — chunk [{},{}] not immediately available, " +
                            "skipping collision shapes to prevent main-thread deadlock (+{} suppressed)",
                            cx, cz, suppressed);
                    } else {
                        VoidRpAsyncAI.LOGGER.warn(
                            "[VoidRP] BlockCollisions chunk guard — chunk [{},{}] not immediately available, " +
                            "skipping collision shapes to prevent main-thread deadlock",
                            cx, cz);
                    }
                }
                return null; // computeNext() null-checks this and skips safely
            }
            return chunk;
        }
        return source.getChunkForCollisions(cx, cz);
    }
}
