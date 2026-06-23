package ru.voidrp.asyncai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Parallel line-of-sight pre-computation.
 *
 * Called once per tick, BEFORE the sequential entity tick loop, for each
 * ServerLevel.  For every (Mob → target) pair whose LOS result is not in cache,
 * we submit a raycast job to a dedicated worker pool.  The main thread waits up
 * to MAX_WAIT_MS for all jobs to finish, then proceeds with the entity tick.
 * Any jobs that miss the deadline are left running; their results land in the
 * cache and are used by the next tick.
 *
 * Thread-safety rationale
 * -----------------------
 * 1. Entity position reads  — x/y/z stored as volatile double in Entity; safe.
 * 2. level.isLoaded()       — reads ConcurrentHashMap; safe.
 * 3. level.clip()           — reads PalettedContainer (block states) from loaded
 *    chunks; PalettedContainer uses volatile for palette/data access; safe.
 * 4. Chunk unloading        — ServerChunkCache.tick() runs BEFORE entity ticking;
 *    chunk state is stable for the duration of the entity tick loop and our
 *    pre-tick injection.
 * 5. Entity removal         — we snapshot positions as immutable Vec3 before
 *    submitting; if entity is removed mid-flight the UUID key in cache is simply
 *    evicted next cleanup cycle.
 * 6. Cache writes           — LineOfSightCache uses ConcurrentHashMap; safe.
 */
public final class ParallelLosManager {

    /** Maximum time (ms) the main thread waits for parallel jobs each tick. */
    private static final long MAX_WAIT_MS = 4L;

    /** Maximum LOS jobs submitted per tick per level to prevent worker overload. */
    private static final int MAX_JOBS_PER_TICK = 40;

    private static volatile ExecutorService pool;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public static void init() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "voidrp-los-worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        VoidRpAsyncAI.LOGGER.info("[VoidRP Async AI] Parallel LOS pool: {} threads", threads);
    }

    public static void shutdown() {
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Per-tick entry point (called from ParallelPreTickMixin)
    // -------------------------------------------------------------------------

    public static void preComputeForLevel(ServerLevel level) {
        if (!AiConfig.PARALLEL_LOS_ENABLED.get()) return;
        ExecutorService p = pool;
        if (p == null || p.isShutdown()) return;

        long gameTime = level.getGameTime();
        List<CompletableFuture<?>> futures = null; // lazy-init
        int jobsThisTick = 0;

        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof Mob mob)) continue;
            Entity target = mob.getTarget();
            if (target == null) continue;

            UUID srcId = mob.getUUID();
            UUID tgtId = target.getUUID();
            double distSq = mob.distanceToSqr(target);

            // Already cached and fresh → nothing to do
            if (LineOfSightCache.get(srcId, tgtId, gameTime, distSq) != null) continue;

            // Either endpoint in unloaded chunk → cache false immediately, no raycast
            if (!level.isLoaded(mob.blockPosition()) || !level.isLoaded(target.blockPosition())) {
                LineOfSightCache.put(srcId, tgtId, false, gameTime);
                continue;
            }

            // Beyond vanilla hasLineOfSight limit → false
            if (distSq > 128.0 * 128.0) {
                LineOfSightCache.put(srcId, tgtId, false, gameTime);
                continue;
            }

            // Job rate limit: skip remaining pairs this tick to avoid worker overload
            if (jobsThisTick >= MAX_JOBS_PER_TICK) continue;

            // Snapshot volatile position fields before handing off to worker
            final double sx = mob.getX(),    sy = mob.getEyeY(),    sz = mob.getZ();
            final double tx = target.getX(), ty = target.getEyeY(), tz = target.getZ();
            final long   gt = gameTime;

            if (futures == null) futures = new ArrayList<>();
            jobsThisTick++;

            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    Vec3 from = new Vec3(sx, sy, sz);
                    Vec3 to   = new Vec3(tx, ty, tz);
                    // Replicate LivingEntity.hasLineOfSight() exactly:
                    // no entity means no shape exclusion (conservative / correct).
                    ClipContext ctx = new ClipContext(
                            from, to,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            (Entity) null
                    );
                    boolean los = level.clip(ctx).getType() == HitResult.Type.MISS;
                    LineOfSightCache.put(srcId, tgtId, los, gt);
                } catch (Exception ignored) {
                    // If anything goes wrong, leave cache empty; real computation
                    // will run on the main thread and our guard will protect it.
                }
            }, p));
        }

        if (futures == null) return;

        try {
            CompletableFuture
                    .allOf(futures.toArray(new CompletableFuture[0]))
                    .get(MAX_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Jobs that miss the deadline finish in the background; their
            // results land in the cache and are used next tick.
        }
    }
}
