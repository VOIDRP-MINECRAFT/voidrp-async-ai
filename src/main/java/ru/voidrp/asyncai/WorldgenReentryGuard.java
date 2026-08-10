package ru.voidrp.asyncai;

/**
 * Thread-local re-entrancy marker for "we are currently inside a synchronous
 * world-generation fluid tick on the main server thread".
 *
 * Set by {@code PostProcessFluidGuardMixin} around each {@code FluidState.tick()}
 * call it drives during {@code LevelChunk.postProcessGeneration()}. Read by
 * {@code SablePhysicsWorldgenGuardMixin} to decide whether a block change must
 * skip Sable physics handling.
 *
 * Root cause (Watchdog HUNG_TICK dumps 2026-07-20 19:04 / 19:06):
 *   ChunkMap.prepareTickingChunk → LevelChunk.postProcessGeneration → FluidState.tick
 *   → FlowingFluid.spreadTo → Level.setBlock → LiquidBlock.neighborChanged
 *   → NeoForge FluidInteractionRegistry → Level.setBlockAndUpdate
 *   → Sable SableCommonEvents.handleBlockChange → RapierPhysicsPipeline.handleBlockChange
 *   → LevelAccelerator.grabChunkFast → ServerChunkCache.getChunk(...,create=true)
 *   → managedBlock() → parkNanos().
 *
 * That last getChunk is a BLOCKING, re-entrant main-thread chunk load issued from
 * inside the chunk-generation pipeline. It can only complete once the pipeline
 * unwinds — which it cannot, because the pipeline is blocked on it. The server
 * thread parks for 10-13 s (long enough to trip the Paper/Youer Watchdog) before
 * the load happens to resolve, then re-hangs on the next freshly generated chunk.
 *
 * Fix: while this marker is active, Sable's physics block-change handling is skipped.
 * Physics for transient world-gen fluid settling on brand-new chunks is irrelevant —
 * no player-built physics structures exist there yet — so dropping it is safe and
 * the block itself is still placed normally.
 *
 * A depth counter (not a boolean) is used so nested fluid ticks unwind correctly.
 */
public final class WorldgenReentryGuard {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private WorldgenReentryGuard() {
    }

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] d = DEPTH.get();
        if (d[0] > 0) {
            d[0]--;
        }
    }

    public static boolean active() {
        return DEPTH.get()[0] > 0;
    }
}
