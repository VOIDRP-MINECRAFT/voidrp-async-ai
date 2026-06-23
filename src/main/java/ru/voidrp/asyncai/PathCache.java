package ru.voidrp.asyncai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-entity path result cache with TTL.
 *
 * When a mob's async path computation finishes, the result is stored here keyed
 * by the mob's UUID. If the same mob requests a path to the same target within
 * the TTL window, the cached node list is returned as a fresh Path instance.
 *
 * Nodes are shared (they're effectively immutable once a path is finalized).
 * A new Path wrapper is always constructed so each mob has its own
 * nextNodeIndex state.
 */
public final class PathCache {

    /** Maximum number of cached entries. Oldest evicted when exceeded. */
    private static final int MAX_ENTRIES = 512;

    /** How long a cached path stays valid (milliseconds). */
    private static final long TTL_MS_NEAR = 1000L;  // <64 blocks
    private static final long TTL_MS_FAR  = 3000L;  // ≥64 blocks

    private record Entry(List<Node> nodes, BlockPos target, boolean reached, long expiresAt, double distSq) {}

    private static final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Store a computed path for this entity. Thread-safe — intended to be called
     * from the async worker thread immediately after path computation.
     *
     * @param distSq squared distance to nearest player at submission time
     *               (used to pick a longer TTL for distant mobs)
     */
    public static void store(UUID mobId, @Nullable Path path, double distSq) {
        if (!AiConfig.PATH_CACHE_ENABLED.get()) return;
        if (path == null || path.getNodeCount() == 0) return;

        List<Node> nodes = new ArrayList<>(path.getNodeCount());
        for (int i = 0; i < path.getNodeCount(); i++) {
            nodes.add(path.getNode(i));
        }

        long ttl = distSq >= 64.0 * 64.0 ? TTL_MS_FAR : TTL_MS_NEAR;
        cache.put(mobId, new Entry(
            List.copyOf(nodes),
            path.getTarget(),
            path.canReach(),
            System.currentTimeMillis() + ttl,
            distSq
        ));

        if (cache.size() > MAX_ENTRIES) evict();
    }

    /**
     * Attempt to retrieve a valid cached path for this entity targeting any of the
     * given positions. Returns null on cache miss or expiry.
     *
     * Uses proximity matching: if the new target is within 3 blocks (X/Z) and 2
     * blocks (Y) of the cached target, the cached path is reused without recomputing.
     * This avoids recomputing paths every tick when targets (chased entities) move
     * slightly — the primary cause of repeated VoxelShape/block-state reads.
     *
     * Always returns a fresh Path instance with independent mutable state.
     * Call from main thread only.
     */
    @Nullable
    public static Path retrieve(UUID mobId, Set<BlockPos> targets) {
        if (!AiConfig.PATH_CACHE_ENABLED.get()) return null;

        Entry entry = cache.get(mobId);
        if (entry == null) return null;

        if (System.currentTimeMillis() > entry.expiresAt()) {
            cache.remove(mobId);
            return null;
        }

        if (!targetNearby(entry.target(), targets)) return null;

        // New Path wrapper so each mob has independent nextNodeIndex
        return new Path(new ArrayList<>(entry.nodes()), entry.target(), entry.reached());
    }

    /**
     * Returns true if any of the requested targets is within 3 blocks (X/Z)
     * and 2 blocks (Y) of the cached target position.
     */
    private static boolean targetNearby(BlockPos cached, Set<BlockPos> targets) {
        if (targets.contains(cached)) return true;
        for (BlockPos t : targets) {
            if (Math.abs(t.getX() - cached.getX()) <= 3
             && Math.abs(t.getY() - cached.getY()) <= 2
             && Math.abs(t.getZ() - cached.getZ()) <= 3) return true;
        }
        return false;
    }

    /** Remove entries for entities that no longer exist. */
    public static void cleanup(Iterable<? extends Mob> liveMobs) {
        var liveIds = new HashSet<UUID>();
        for (Mob m : liveMobs) liveIds.add(m.getUUID());
        cache.keySet().retainAll(liveIds);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static void evict() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        // If still over limit after TTL eviction, just clear the whole map
        if (cache.size() > MAX_ENTRIES) cache.clear();
    }
}
