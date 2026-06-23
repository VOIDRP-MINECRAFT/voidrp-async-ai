package ru.voidrp.asyncai;

/**
 * Monitors actual server tick duration and exposes a "load factor" that the
 * throttle system uses to become more aggressive during TPS drops.
 *
 * Load factor scale:
 *   1.0  — healthy  (avg tick < 40 ms, ≥ 20 TPS)
 *   1.5  — mild lag (avg tick 40–60 ms, ~15–20 TPS)
 *   2.0  — moderate (avg tick 60–100 ms, ~10–15 TPS)
 *   3.0  — severe   (avg tick > 100 ms, < 10 TPS)
 *
 * Throttle intervals are multiplied by this factor, so at 3× load a mob
 * normally skipped every 8 ticks is skipped every 24 ticks — buying the
 * server even more breathing room under heavy load.
 *
 * Thread safety: written only from main server thread, read from main thread
 * too (throttle decisions). volatile fields make reads safe.
 */
public final class AdaptiveThrottle {

    /** Number of tick samples to average over. */
    private static final int SAMPLE_SIZE = 20;

    /** Recalculate load factor every N ticks (reduces recalculation overhead). */
    private static final int RECALC_INTERVAL = 4;

    /** Target tick budget: 50 ms = 20 TPS. */
    private static final long TARGET_TICK_MS = 50L;

    private static final long[] samples = new long[SAMPLE_SIZE];
    private static int writeIdx = 0;
    private static int filled = 0;
    private static long tickStartNs = 0L;

    private static volatile double loadFactor = 1.0;
    private static volatile double currentTps = 20.0;

    // -------------------------------------------------------------------------
    // Called from main server thread
    // -------------------------------------------------------------------------

    public static void onTickStart() {
        tickStartNs = System.nanoTime();
    }

    public static void onTickEnd() {
        long now = System.nanoTime();
        if (tickStartNs == 0L) {
            tickStartNs = now;
            return;
        }

        long durationNs = now - tickStartNs;
        samples[writeIdx % SAMPLE_SIZE] = durationNs;
        writeIdx++;
        if (filled < SAMPLE_SIZE) filled++;

        if (writeIdx % RECALC_INTERVAL == 0) {
            recalculate();
        }
    }

    // -------------------------------------------------------------------------
    // Public accessors (reads from any thread)
    // -------------------------------------------------------------------------

    /**
     * Returns a multiplier ≥ 1.0.
     * Returns 1.0 when {@link AiConfig#ADAPTIVE_THROTTLE_ENABLED} is false.
     */
    public static double getLoadFactor() {
        return AiConfig.ADAPTIVE_THROTTLE_ENABLED.get() ? loadFactor : 1.0;
    }

    public static double getCurrentTps() {
        return currentTps;
    }

    /** Reset state on server restart. */
    public static void reset() {
        writeIdx = 0;
        filled = 0;
        tickStartNs = 0L;
        loadFactor = 1.0;
        currentTps = 20.0;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static void recalculate() {
        int n = filled;
        if (n == 0) return;

        long sum = 0;
        for (int i = 0; i < n; i++) sum += samples[i];
        double avgMs = (sum / (double) n) / 1_000_000.0;

        // Clamp TPS to [0, 20]
        currentTps = Math.min(20.0, 1000.0 / Math.max(1.0, avgMs));

        if (avgMs < 40.0) {
            loadFactor = 1.0;
        } else if (avgMs < 55.0) {
            loadFactor = 1.5;
        } else if (avgMs < 75.0) {
            loadFactor = 2.0;
        } else if (avgMs < 120.0) {
            loadFactor = 2.5;
        } else {
            loadFactor = 3.0;
        }
    }
}
