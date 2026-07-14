package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.MainThreadChunkLoad;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards Level.getBlockEntity() against main-thread deadlock.
 *
 * Root cause (Watchdog dump 2026-06-05 20:40):
 *   Create FunnelBlockEntity.tick() → SmartBlockEntity.forEachBehaviour()
 *   → CapManipulationBehaviourBase.tick() → findNewCapability()
 *   → Level.getBlockEntity(neighborPos) — neighbor is in an unloaded chunk.
 *   Level.getBlockEntity calls getChunkAt(pos) → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock() → LockSupport.parkNanos — server hangs.
 *
 * Pattern: a Create Funnel near a chunk border scans adjacent positions for block
 * entities (inventory targets) every tick. If the neighbor chunk is unloaded the
 * main thread parks indefinitely, triggering Watchdog.
 *
 * Fix (updated 2026-07-14): delegate to {@link MainThreadChunkLoad#shouldServeUnloaded}.
 *   - Off the main thread, or when the chunk cannot be brought to FULL within a small budget,
 *     return null (as before — the Create funnel border scan never parks the server).
 *   - On the main thread, a chunk that already exists on disk is loaded within the budget and
 *     the vanilla lookup proceeds, returning the REAL block entity. This unbreaks legitimate
 *     main-thread reads that touch a generated-but-unloaded chunk — most visibly Waystones,
 *     whose WaystoneManagerImpl.getWaystoneAt() reads the destination block entity directly.
 *   getBlockEntity is already @Nullable; all callers must handle null by contract.
 */
@Mixin(Level.class)
public abstract class LevelGetBlockEntityChunkGuardMixin {

    @Inject(
        method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardGetBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (MainThreadChunkLoad.shouldServeUnloaded(serverLevel, cx, cz)) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                if (suppressed > 0) {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] getBlockEntity guard — chunk [{},{}] not loaded " +
                        "at block pos {},{},{} — returning null to prevent deadlock (+{} suppressed)",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ(), suppressed);
                } else {
                    VoidRpAsyncAI.LOGGER.warn(
                        "[VoidRP] getBlockEntity guard — chunk [{},{}] not loaded " +
                        "at block pos {},{},{} — returning null to prevent deadlock",
                        cx, cz, pos.getX(), pos.getY(), pos.getZ());
                }
            }
            cir.setReturnValue(null);
        }
    }
}
