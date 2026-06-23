package ru.voidrp.asyncai;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches hasLineOfSight() results between entity pairs to avoid re-running
 * the expensive block raycast every tick.
 *
 * TTL scales with distance — close combat still feels responsive, far targets
 * are checked less often:
 *   < 24 blocks  : 2 ticks  (near-combat)
 *   < 48 blocks  : 5 ticks
 *   < 96 blocks  : 10 ticks
 *   >= 96 blocks : 20 ticks
 *
 * This is the primary optimisation for tamed Ice&Fire dragons, which call
 * hasLineOfSight() on OwnerHurtTargetGoal and OwnerHurtByTargetGoal every
 * single tick — often toward a target that is not moving and whose LOS
 * status has not changed since the last tick.
 *
 * Thread safety: ConcurrentHashMap — safe for concurrent reads.
 * Writes happen only from the main server thread (entity tick).
 * Cleanup is called once per second from the server tick event.
 */
public final class LineOfSightCache {

    private record Key(UUID source, UUID target) {}
    private record Entry(boolean result, long gameTime) {}

    private static final ConcurrentHashMap<Key, Entry> cache = new ConcurrentHashMap<>(256);

    // -------------------------------------------------------------------------
    // API used by LineOfSightGuardMixin
    // -------------------------------------------------------------------------

    /**
     * Returns cached result if still fresh, or null if a real computation is needed.
     */
    public static Boolean get(UUID source, UUID target, long gameTime, double distSq) {
        Entry e = cache.get(new Key(source, target));
        if (e == null) return null;
        int ttl = ttlFor(distSq);
        return (gameTime - e.gameTime() < ttl) ? e.result() : null;
    }

    /**
     * Stores a freshly computed LOS result.
     */
    public static void put(UUID source, UUID target, boolean result, long gameTime) {
        cache.put(new Key(source, target), new Entry(result, gameTime));
    }

    /**
     * Evict entries older than 40 ticks (2 seconds). Call from a server tick
     * event at a low rate (e.g. once per second = every 20 ticks).
     */
    public static void evictStale(long gameTime) {
        cache.entrySet().removeIf(e -> gameTime - e.getValue().gameTime() > 40);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static int ttlFor(double distSq) {
        if (distSq < 24 * 24) return 2;
        if (distSq < 48 * 48) return 5;
        if (distSq < 96 * 96) return 10;
        return 20;
    }
}
