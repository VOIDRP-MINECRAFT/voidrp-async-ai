package ru.voidrp.asyncai;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared policy for the main-thread {@code getBlockState} / {@code getBlockEntity} chunk guards.
 *
 * Old behaviour: any read of an unloaded chunk on the main thread returned AIR/null. That
 * prevented the Create cross-border generation deadlock, but it ALSO broke every legitimate
 * main-thread read that intends to load a real, already-generated chunk — most visibly
 * Waystones teleport, which reads the destination waystone block directly.
 *
 * New behaviour ({@link #shouldServeUnloaded}):
 *   - Off the main thread: still return AIR/null immediately (a worker thread cannot safely
 *     block-load, and the non-main getChunk guard would hand back null → NPE otherwise).
 *   - On the main thread: attempt ONE time-bounded load ({@link VoidRpBoundedChunkLoader}).
 *     A generated chunk loads within the budget → the guard lets vanilla read the real block.
 *     An ungenerated / distant chunk exceeds the budget → serve AIR/null, no Watchdog park.
 *
 * Safety rails so this can never re-introduce a hang or chunk-load thrash:
 *   - Per-chunk cooldown: after ANY bounded attempt (success or timeout) the chunk is skipped
 *     (served AIR/null) for {@link #COOLDOWN_NS}. A one-shot read (Waystones) still succeeds;
 *     a block entity hammering the same unloaded neighbour every tick does not re-pump.
 *   - Per-window budget: at most {@link #WINDOW_BUDGET_NS} of bounded loading per ~tick across
 *     all chunks, so a burst touching many distinct chunks can never add up to a Watchdog stall.
 */
public final class MainThreadChunkLoad {

    /** Max wall-time to spend pumping the chunk executor for a single chunk. */
    private static final long SINGLE_BUDGET_NS = 300_000_000L;   // 300 ms

    /** Max total bounded-loading per ~tick window (safety cap against many-distinct-chunk bursts). */
    private static final long WINDOW_BUDGET_NS = 600_000_000L;   // 600 ms
    private static final long WINDOW_NS        = 50_000_000L;    // ~1 tick

    /** How long to skip re-attempting a chunk after a bounded attempt. */
    private static final long COOLDOWN_NS      = 30_000_000_000L; // 30 s

    // packed (cx<<32|cz) → last attempt nanoTime
    private static final ConcurrentHashMap<Long, Long> LAST_ATTEMPT = new ConcurrentHashMap<>();

    // Per-window budget accounting (main-thread only).
    private static long windowStartNs = 0L;
    private static long windowSpentNs = 0L;

    private MainThreadChunkLoad() {}

    private static boolean isMainThread() {
        return "Server thread".equals(Thread.currentThread().getName());
    }

    /**
     * @return true if the guard should serve AIR/null (chunk unavailable), false if the chunk
     *         is now loaded and the vanilla read should proceed.
     */
    public static boolean shouldServeUnloaded(ServerLevel level, int cx, int cz) {
        ServerChunkCache chunkSource = level.getChunkSource();
        if (chunkSource.getChunkNow(cx, cz) != null) {
            return false; // already present — let vanilla read it
        }
        if (!isMainThread()) {
            return true; // worker thread — cannot block-load safely
        }
        if (!(chunkSource instanceof VoidRpBoundedChunkLoader loader)) {
            return true; // mixin not applied — behave as before
        }

        long now = System.nanoTime();
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);

        Long last = LAST_ATTEMPT.get(key);
        if (last != null && now - last < COOLDOWN_NS) {
            return true; // recently attempted — skip re-pumping, serve AIR/null
        }

        // Refill the per-window budget if we rolled into a new tick window.
        if (now - windowStartNs >= WINDOW_NS) {
            windowStartNs = now;
            windowSpentNs = 0L;
        }
        long remaining = WINDOW_BUDGET_NS - windowSpentNs;
        if (remaining <= 0L) {
            return true; // window budget exhausted — serve AIR/null this tick
        }

        long budget = Math.min(SINGLE_BUDGET_NS, remaining);
        LAST_ATTEMPT.put(key, now);
        LevelChunk chunk = loader.voidrp$loadChunkBounded(cx, cz, budget);
        windowSpentNs += System.nanoTime() - now;

        return chunk == null; // loaded → proceed (false); timed out → serve AIR/null (true)
    }

    public static void clear() {
        LAST_ATTEMPT.clear();
    }
}
