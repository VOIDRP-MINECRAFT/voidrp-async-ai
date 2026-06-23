package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards PlayerRespawnLogic.getOverworldRespawnPos() against main-thread chunk-load deadlock.
 *
 * Root cause (Watchdog dump 2026-06-02 21:17):
 *   Citizens plugin loads NPCs on chunk arrival → EntityHumanNPC.<init> → ServerPlayer.<init>
 *   → ServerPlayer.adjustSpawnLocation → getSpawnPosInChunk → getOverworldRespawnPos
 *   → ServerLevel.getChunk(sectionX, sectionZ) → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → parkNanos — server frozen 10+ s.
 *
 * Fix: if the target chunk is not immediately available (getChunkNow == null),
 * return null — getSpawnPosInChunk skips nulls, adjustSpawnLocation falls back to
 * world spawn; the server doesn't hang.
 */
@Mixin(PlayerRespawnLogic.class)
public abstract class PlayerRespawnLogicChunkGuardMixin {

    @Inject(
        method = "getOverworldRespawnPos",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void voidrp_guardGetOverworldRespawnPos(
            ServerLevel level, int x, int z,
            CallbackInfoReturnable<BlockPos> cir) {

        int cx = SectionPos.blockToSectionCoord(x);
        int cz = SectionPos.blockToSectionCoord(z);
        ServerChunkCache cache = level.getChunkSource();
        LevelChunk chunk = cache.getChunkNow(cx, cz);
        if (chunk == null) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] PlayerRespawnLogic chunk guard — chunk [{},{}] not loaded at {},{}" +
                    " — returning null to prevent main-thread deadlock{}",
                    cx, cz, x, z,
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(null);
        }
    }
}
