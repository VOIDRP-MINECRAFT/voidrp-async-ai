package ru.voidrp.asyncai.mixin;

import dev.ryanhcode.sable.SableCommonEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.VoidRpAsyncAI;
import ru.voidrp.asyncai.WorldgenReentryGuard;

/**
 * Prevents a re-entrant main-thread chunk-load deadlock originating in Sable physics.
 *
 * Root cause (Watchdog HUNG_TICK dumps 2026-07-20 19:04 / 19:06, both identical):
 *   ChunkMap.prepareTickingChunk → LevelChunk.postProcessGeneration → FluidState.tick
 *   → FlowingFluid.spreadTo → Level.setBlock → LiquidBlock.neighborChanged
 *   → NeoForge FluidInteractionRegistry.canInteract → Level.setBlockAndUpdate
 *   → SableCommonEvents.handleBlockChange → SubLevelPhysicsSystem.handleBlockChange
 *   → RapierPhysicsPipeline.handleBlockChange → VoxelNeighborhoodState.getState
 *   → LevelAccelerator.getBlockState → grabChunkFast → Level.getChunk(FULL, create=true)
 *   → ServerChunkCache.getChunk → MainThreadExecutor.managedBlock → waitForTasks → park.
 *
 *   Sable reads neighbour block states with a BLOCKING chunk load. When the block change
 *   happens re-entrantly inside chunk post-processing generation, that load can only
 *   complete after the generation pipeline unwinds — which it cannot, because the pipeline
 *   is parked waiting on the load. The server thread freezes ~10-13 s per freshly generated
 *   fluid-carrying chunk, tripping the Watchdog, then repeats on the next chunk.
 *
 * Fix: while {@link WorldgenReentryGuard#active()} (set by PostProcessFluidGuardMixin around
 * each world-gen FluidState.tick), cancel Sable's handleBlockChange at HEAD. The block is
 * still placed by vanilla; only the physics bookkeeping for a transient world-gen fluid
 * settling on a brand-new chunk is dropped — no player-built physics structures exist there,
 * so this is safe. Normal (non-world-gen) block changes are unaffected.
 *
 * remap = false: SableCommonEvents is a third-party class; its method name is not remapped.
 */
@Mixin(value = SableCommonEvents.class, remap = false)
public abstract class SablePhysicsWorldgenGuardMixin {

    @Inject(
        method = "handleBlockChange(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;IIILnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void voidrp_skipPhysicsDuringWorldgen(CallbackInfo ci) {
        if (WorldgenReentryGuard.active()) {
            long suppressed = ChunkWarnRateLimit.acquire(0, 0);
            if (suppressed >= 0) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] Sable handleBlockChange guard — skipping physics for a world-gen " +
                    "fluid-settle block change to prevent re-entrant main-thread chunk-load deadlock{}",
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
            }
            ci.cancel();
        }
    }
}
