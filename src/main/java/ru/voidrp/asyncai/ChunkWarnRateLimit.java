package ru.voidrp.asyncai;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limits per-chunk WARN log entries from guard mixins.
 *
 * Guards (BlockCollisions, getOnPos, travel) fire on every tick for hot chunks —
 * without throttling they produce hundreds of log lines per second and non-trivial
 * String-format overhead on the main thread.
 *
 * Usage:
 *   long suppressed = ChunkWarnRateLimit.acquire(cx, cz);
 *   if (suppressed >= 0) {
 *       LOGGER.warn("... chunk [{},{}] ... (+{} suppressed)", cx, cz, ..., suppressed);
 *   }
 *
 * acquire() returns the suppressed-since-last-emission count (≥0) when the caller
 * should log, or -1 when the entry is within the quiet window and should be skipped.
 */
public final class ChunkWarnRateLimit {

    private static final long INTERVAL_NS = 5_000_000_000L; // 5 seconds per chunk

    // packed (cx<<32|cz) → [lastLogNano, suppressedCount]
    private static final ConcurrentHashMap<Long, long[]> STATE = new ConcurrentHashMap<>();

    private ChunkWarnRateLimit() {}

    /**
     * @return suppressed count (≥0) if the WARN should be emitted now, -1 to suppress.
     *         A return value of 0 means first occurrence or start of a new window.
     */
    public static long acquire(int cx, int cz) {
        long key  = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        long now  = System.nanoTime();
        long[] slot = STATE.computeIfAbsent(key, k -> new long[2]);
        // slot[0] = last emission nanoTime, slot[1] = suppressed count
        synchronized (slot) {
            if (now - slot[0] >= INTERVAL_NS) {
                long suppressed = slot[1];
                slot[0] = now;
                slot[1] = 0;
                return suppressed;
            }
            slot[1]++;
            return -1;
        }
    }

    public static void clear() {
        STATE.clear();
    }
}
