package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards TFTeleporter against main-thread hang during portal search.
 *
 * Root cause #1 (Watchdog dump 2026-06-16 23:26:40 — chunk-load deadlock):
 *   Player.tick → Entity.handlePortal → TFPortalBlock.getPortalDestination
 *   → TFTeleporter.createTransition → createPosition → makePortal
 *   → cacheNewPortalCoords(463) → getPortalPosition(148)
 *   → Level.getBlockState → ServerChunkCache.getChunk(FULL, true)
 *   → MainThreadExecutor.managedBlock → LockSupport.parkNanos → BLOCKS 10+ s.
 *   Fix: redirect Level.getBlockState to getChunkNow() (non-blocking).
 *
 * Root cause #2 (Watchdog dump 2026-06-18 13:36:25 — CPU busy-loop in loaded chunks):
 *   Same call chain but all TF chunks already loaded at -10000,-9989.
 *   State=RUNNABLE: PalettedContainer.get spinning over millions of blocks.
 *   getPortalPosition scans the full TF area (radius ~1600 blocks × full height)
 *   which at extreme coordinates can take >10 s of pure CPU time.
 *   Fix: 3-second deadline set at makePortal HEAD; scan returns AIR after timeout
 *   so TF places a portal at a suboptimal but valid position.
 */
@Mixin(targets = "twilightforest.world.TFTeleporter", remap = false)
public abstract class TFPortalSearchChunkGuardMixin {

    @Unique
    private static final ThreadLocal<Long> PORTAL_SCAN_DEADLINE = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<Boolean> PORTAL_SCAN_WARNED = new ThreadLocal<>();

    @Unique
    private static final long SCAN_TIMEOUT_NS = 3_000_000_000L; // 3 s

    // makePortal(TeleporterCache, Entity, ServerLevel, Vec3, boolean) : void
    @Inject(method = "makePortal", at = @At("HEAD"), require = 0)
    private void voidrp$startPortalScanDeadline(CallbackInfo ci) {
        PORTAL_SCAN_DEADLINE.set(System.nanoTime() + SCAN_TIMEOUT_NS);
        PORTAL_SCAN_WARNED.set(false);
    }

    @Inject(method = "makePortal", at = @At("RETURN"), require = 0)
    private void voidrp$clearPortalScanDeadline(CallbackInfo ci) {
        PORTAL_SCAN_DEADLINE.remove();
        PORTAL_SCAN_WARNED.remove();
    }

    @Redirect(
        method = "getPortalPosition",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0,
        remap = true
    )
    private BlockState voidrp$safeGetBlockState_getPortalPosition(Level level, BlockPos pos) {
        return voidrp$nonBlockingGetBlockState(level, pos, "getPortalPosition");
    }

    @Redirect(
        method = "cacheNewPortalCoords",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 0,
        remap = true
    )
    private BlockState voidrp$safeGetBlockState_cacheNewPortalCoords(Level level, BlockPos pos) {
        return voidrp$nonBlockingGetBlockState(level, pos, "cacheNewPortalCoords");
    }

    @Unique
    private static BlockState voidrp$nonBlockingGetBlockState(Level level, BlockPos pos, String site) {
        // Time guard: abort CPU-heavy block scan if running longer than 3 s.
        // Covers the case where chunks ARE loaded but the scan area is huge
        // (e.g. player enters TF portal at extreme coordinates like -10000,-10000).
        Long deadline = PORTAL_SCAN_DEADLINE.get();
        if (deadline != null && System.nanoTime() > deadline) {
            if (!Boolean.TRUE.equals(PORTAL_SCAN_WARNED.get())) {
                PORTAL_SCAN_WARNED.set(true);
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] TFTeleporter.{} portal scan timed out (>3s) at {},y,{}" +
                    " — aborting block scan to prevent main-thread lag",
                    site, pos.getX(), pos.getZ());
            }
            return Blocks.AIR.defaultBlockState();
        }

        // Chunk guard: return AIR for unloaded chunks to avoid chunk-load deadlock.
        if (!(level instanceof ServerLevel serverLevel)) {
            return level.getBlockState(pos);
        }
        ServerChunkCache cache = serverLevel.getChunkSource();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        LevelChunk chunk = cache.getChunkNow(cx, cz);
        if (chunk == null) {
            long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] TFTeleporter.{} chunk guard — chunk [{},{}] not loaded " +
                    "at {},{},{} — returning AIR to prevent main-thread hang{}",
                    site, cx, cz, pos.getX(), pos.getY(), pos.getZ(),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            return Blocks.AIR.defaultBlockState();
        }
        return chunk.getBlockState(pos);
    }
}
