package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.HashSet;
import java.util.Set;

/**
 * Guards Accelerated Decay's leaf flood-fill against a main-thread getBlockState storm.
 *
 * Root cause (Watchdog HUNG_TICK dump 2026-07-22 14:12):
 *   Accelerated Decay records a {@code TimedDimBlockPos} for every broken LOG, then every
 *   ServerLevel tick runs {@code seekLeaves(level, pos)} — a BFS flood-fill that calls
 *   {@code Level.getBlockState} on every SCAN_LOCATIONS neighbor to find connected,
 *   non-persistent, distance-7 leaves to yeet. When logs are broken at a location whose
 *   chunk (and canopy) has since unloaded (e.g. a far-away tree/log farm the player walked
 *   away from), the scan pounds {@code getBlockState} on unloaded chunks. Our existing
 *   {@link LevelGetBlockStateChunkGuardMixin} returns AIR to avoid a hard deadlock, but the
 *   scan is still invoked from {@code levelTick} on a continuous stream of entries, spinning
 *   the main thread at ~1M+ getBlockState/s (observed +275k suppressed per anchor per 5 s,
 *   8 anchors) and overrunning the tick.
 *
 * Fix: intercept {@code seekLeaves} at HEAD. If the origin block's chunk is not currently
 * loaded (non-blocking {@code getChunkNow}), return an empty set immediately — no flood-fill,
 * no getBlockState calls, no leaf destruction attempts. Accelerated leaf decay in an unloaded
 * chunk has no observer and no gameplay value; when the chunk is loaded the vanilla scan runs
 * unchanged.
 *
 * remap = false: AcceleratedDecay is a third-party mod class; seekLeaves is not remapped.
 * require = 0 so a mod update that renames the method degrades gracefully instead of failing boot.
 */
@Mixin(targets = "pro.mikey.accelerateddecay.AcceleratedDecay", remap = false)
public abstract class AcceleratedDecaySeekLeavesChunkGuardMixin {

    @Inject(
        method = "seekLeaves(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ljava/util/Set;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void voidrp_guardSeekLeavesChunkLoad(
            Level level, BlockPos pos, CallbackInfoReturnable<Set<BlockPos>> cir) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (serverLevel.getChunkSource().getChunkNow(cx, cz) == null) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] AcceleratedDecay seekLeaves guard — chunk [{},{}] not loaded at " +
                    "block pos {},{},{} — skipping leaf scan to prevent main-thread getBlockState storm{}",
                    cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            cir.setReturnValue(new HashSet<>());
        }
    }
}
