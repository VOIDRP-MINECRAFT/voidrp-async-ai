package ru.voidrp.asyncai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.PathNavigationRegion;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

public final class AsyncPathManager {

    // Worker threads: half of available CPUs, at least 2, at most 8
    private static final int THREAD_COUNT =
        Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
        THREAD_COUNT,
        r -> {
            Thread t = new Thread(r, "voidrp-pathfinder-" + r.hashCode());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    );

    /** Ready results waiting to be applied on main thread next tick. */
    private static final ConcurrentHashMap<UUID, Optional<Path>> pendingResults = new ConcurrentHashMap<>();

    /** Entities whose path is currently being computed — avoids duplicate submissions. */
    private static final ConcurrentHashMap.KeySetView<UUID, Boolean> inFlight =
        ConcurrentHashMap.newKeySet();

    /**
     * Suppress re-submission spam: same entity can submit at most once per cooldown.
     * 200 ms ≈ 4 ticks — gives the PathCache a chance to serve the next few queries
     * from cache before a new async job is started.
     */
    private static final ConcurrentHashMap<UUID, Long> lastSubmitMs = new ConcurrentHashMap<>();
    private static final long SUBMIT_COOLDOWN_MS = 200L;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns true if this mob should use async pathfinding this tick.
     * Mobs close to any player always use synchronous pathfinding so their
     * movement feels immediate and responsive.
     */
    public static boolean shouldAsync(Mob mob) {
        if (!ConfigCache.ASYNC_PATH_ENABLED) return false;
        if (mob.level().isClientSide()) return false;
        double minDist = ConfigCache.ASYNC_PATH_MIN_DIST;
        double minDistSq = minDist * minDist;
        var players = mob.level().players();
        if (players.isEmpty()) return true;
        for (var player : players) {
            if (mob.distanceToSqr(player) < minDistSq) return false;
        }
        return true;
    }

    /**
     * Submit a path computation to the worker pool.
     *
     * @param distSq squared distance to nearest player — passed through to PathCache
     *               so it can choose an appropriate TTL for the computed path
     * @return true  → submitted (or already in-flight); caller should return null this tick
     *         false → result ready — caller should call {@link #pollResult(UUID)}
     */
    public static boolean submitAsync(
        UUID entityId,
        PathFinder pathFinder,
        PathNavigationRegion region,
        Mob mob,
        Set<BlockPos> targets,
        float maxRange,
        int accuracy,
        float multiplier,
        double distSq
    ) {
        // Result already ready from previous submission
        if (pendingResults.containsKey(entityId)) return false;

        // Already computing
        if (inFlight.contains(entityId)) return true;

        // Cooldown — don't flood the pool with the same mob every tick
        long now  = System.currentTimeMillis();
        Long last = lastSubmitMs.get(entityId);
        if (last != null && now - last < SUBMIT_COOLDOWN_MS) return true;

        if (!inFlight.add(entityId)) return true;
        lastSubmitMs.put(entityId, now);

        EXECUTOR.submit(() -> {
            try {
                Path result = pathFinder.findPath(region, mob, targets, maxRange, accuracy, multiplier);
                PathCache.store(entityId, result, distSq);
                pendingResults.put(entityId, Optional.ofNullable(result));
            } catch (Exception e) {
                pendingResults.put(entityId, Optional.empty());
                VoidRpAsyncAI.LOGGER.warn("[VoidRP Async AI] pathfinding error for {}: {}",
                    entityId, e.getMessage());
            } finally {
                inFlight.remove(entityId);
            }
        });

        return true;
    }

    /** Returns the computed path and removes it from the pending map. Null if not ready. */
    @Nullable
    public static Path pollResult(UUID entityId) {
        Optional<Path> opt = pendingResults.remove(entityId);
        if (opt == null) return null;
        return opt.orElse(null);
    }

    public static boolean hasResult(UUID entityId) {
        return pendingResults.containsKey(entityId);
    }

    /** Called on server shutdown. */
    public static void shutdown() {
        EXECUTOR.shutdownNow();
        pendingResults.clear();
        inFlight.clear();
        lastSubmitMs.clear();
    }

    /** Squared distance to the nearest player, or MAX_VALUE if no players present. */
    public static double nearestPlayerDistSq(Mob mob) {
        var players = mob.level().players();
        if (players.isEmpty()) return Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        for (var p : players) {
            double d = mob.distanceToSqr(p);
            if (d < min) min = d;
        }
        return min;
    }

    public static int getThreadCount()   { return THREAD_COUNT; }
    public static int getPendingCount()  { return pendingResults.size(); }
    public static int getInFlightCount() { return inFlight.size(); }
}
