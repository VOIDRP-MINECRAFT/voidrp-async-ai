package ru.voidrp.asyncai;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Predictive chunk pre-loading via the ticket system (non-blocking on Youer).
 *
 * getChunkFuture(FULL, true) blocks the main thread on Youer via managedBlock.
 * addRegionTicket posts a request to DistanceManager and returns immediately —
 * generation runs on vanilla's thread pool (all cores), zero main-thread cost.
 *
 * Speed tiers (horizontal blocks/sec):
 *   SLOW    < 8   — walking/sneaking, vanilla view distance covers it, skip
 *   NORMAL  8–20  — sprinting, horse, creative fly
 *   FAST   20–50  — elytra, Create trains, Immersive Aircraft
 *   EXTREME 50+   — modded hyper-speed, rockets, teleport-style movement
 *
 * EMA velocity smoothing (α=0.25) catches up in ~7 ticks after a speed change.
 * Under lag (loadFactor > 1.4) all parameters are halved.
 */
public final class ChunkPreloadManager {

    // ---- Speed tier thresholds (blocks/sec) ----
    private static final double SPEED_SLOW    =  8.0;
    private static final double SPEED_FAST    = 20.0;
    private static final double SPEED_EXTREME = 50.0;

    // ---- Per-tier parameters [NORMAL, FAST, EXTREME] ----
    // Max chunks submitted to gen pool per tick
    private static final int[] SUBMIT     = {12, 20, 28};
    // Lookahead in seconds
    private static final float[] LOOKAHEAD = {7f, 11f, 15f};
    // Steps along prediction vector (cap to avoid huge candidate sets)
    private static final int[] MAX_STEPS   = {8,  12, 18};
    // Chunk radius around each predicted position
    private static final int[] RADIUS      = {2,   2,  1};
    // Max in-flight tickets (memory / gen pool cap)
    private static final int[] INFLIGHT_CAP = {64, 100, 140};

    // ---- Lag multipliers (applied when loadFactor > 1.4) ----
    private static final float LAG_SUBMIT_MUL   = 0.33f;
    private static final float LAG_LOOKAHEAD_MUL = 0.5f;

    // EMA α for velocity smoothing
    private static final double VEL_ALPHA = 0.25;

    // Ticket level: 1 → target chunk + immediate 8 neighbours get the request
    private static final int TICKET_LEVEL = 1;

    // Vanilla-safe dimensions; modded dims opt-in via config
    private static final Set<ResourceKey<Level>> SAFE_DIMENSIONS = Set.of(
        Level.OVERWORLD, Level.NETHER, Level.END
    );

    // Per-player state — main thread only
    private static final Map<UUID, Vec3> lastPos   = new HashMap<>();
    private static final Map<UUID, Vec3> smoothVel = new HashMap<>();

    // inFlight: chunkKey → level, used to drain when chunk becomes available
    private static final Map<Long, ServerLevel> inFlight = new ConcurrentHashMap<>();

    private static final AtomicInteger submittedThisTick = new AtomicInteger();

    private ChunkPreloadManager() {}

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public static void init() {
        VoidRpAsyncAI.LOGGER.info(
            "[VoidRP Chunk Preload] ready — ticket-based, tiered by speed" +
            " | moddedDims={}", AiConfig.CHUNK_PRELOAD_MODDED_DIMS.get());
    }

    public static void shutdown() {
        inFlight.clear();
        lastPos.clear();
        smoothVel.clear();
    }

    // -------------------------------------------------------------------------
    // Per-tick entry point
    // -------------------------------------------------------------------------

    public static void onTick(MinecraftServer server) {
        if (!ConfigCache.CHUNK_PRELOAD_ENABLED) return;

        drainCompleted();

        double  load    = AdaptiveThrottle.getLoadFactor();
        boolean lagging = load > 1.4;

        submittedThisTick.set(0);

        for (ServerLevel level : server.getAllLevels()) {
            if (!isSafeToPreload(level)) continue;
            processLevel(level, lagging);
        }
    }

    // -------------------------------------------------------------------------
    // Per-level
    // -------------------------------------------------------------------------

    private static void processLevel(ServerLevel level, boolean lagging) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        ServerChunkCache scc     = level.getChunkSource();
        int viewDist             = level.getServer().getPlayerList().getViewDistance();

        for (ServerPlayer player : players) {
            UUID id  = player.getUUID();
            Vec3 pos = player.position();

            Vec3 last = lastPos.put(id, pos);
            if (last == null) continue;

            Vec3 rawVel = pos.subtract(last).scale(20.0); // blocks/sec, Y ignored
            Vec3 prev   = smoothVel.getOrDefault(id, Vec3.ZERO);
            Vec3 vel    = new Vec3(
                lerp(prev.x, rawVel.x, VEL_ALPHA),
                0.0,
                lerp(prev.z, rawVel.z, VEL_ALPHA)
            );
            smoothVel.put(id, vel);

            double speed = vel.horizontalDistance();
            if (speed < SPEED_SLOW) continue; // too slow — vanilla covers it

            int tier = tier(speed);
            int maxSubmit  = lagging ? Math.max(1, (int)(SUBMIT[tier]   * LAG_SUBMIT_MUL))   : SUBMIT[tier];
            float lookahead = lagging ? LOOKAHEAD[tier] * LAG_LOOKAHEAD_MUL : LOOKAHEAD[tier];
            int maxSteps   = lagging ? MAX_STEPS[tier] / 2 : MAX_STEPS[tier];
            int radius     = lagging ? Math.max(1, RADIUS[tier] - 1)   : RADIUS[tier];
            int cap        = lagging ? INFLIGHT_CAP[tier] / 2          : INFLIGHT_CAP[tier];

            if (inFlight.size() >= cap) continue;
            if (submittedThisTick.get() >= maxSubmit) continue;

            submitPredicted(scc, level, pos, vel, speed,
                            lookahead, maxSteps, radius, maxSubmit, cap, viewDist);
        }
    }

    // -------------------------------------------------------------------------
    // Prediction + ticket submission
    // -------------------------------------------------------------------------

    private static void submitPredicted(ServerChunkCache scc, ServerLevel level,
                                        Vec3 origin, Vec3 vel, double speed,
                                        float lookaheadSecs, int maxSteps,
                                        int radius, int maxSubmit, int cap, int viewDist) {
        int playerCx = SectionPos.blockToSectionCoord((int) origin.x);
        int playerCz = SectionPos.blockToSectionCoord((int) origin.z);
        // At extreme speed extend allowed distance proportionally
        int maxDist = viewDist + radius + Math.max(2, (int)(speed / 16));

        int steps = Math.min(maxSteps, Math.max(1, (int)(lookaheadSecs * speed / 16.0)));

        PriorityQueue<long[]> candidates = new PriorityQueue<>(
            steps * (2 * radius + 1) * (2 * radius + 1) + 1,
            Comparator.comparingLong(a -> a[2])
        );

        for (int step = 1; step <= steps; step++) {
            double t = step * (16.0 / Math.max(speed, 1.0));
            Vec3 predicted = origin.add(vel.scale(t));
            int pcx = SectionPos.blockToSectionCoord((int) predicted.x);
            int pcz = SectionPos.blockToSectionCoord((int) predicted.z);

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int cx = pcx + dx, cz = pcz + dz;
                    if (Math.abs(cx - playerCx) > maxDist ||
                        Math.abs(cz - playerCz) > maxDist) continue;
                    long key  = ChunkPos.asLong(cx, cz);
                    long prio = (long)(step * 100) + Math.abs(dx) + Math.abs(dz);
                    candidates.add(new long[]{cx, cz, prio, key});
                }
            }
        }

        while (!candidates.isEmpty()
               && submittedThisTick.get() < maxSubmit
               && inFlight.size() < cap) {

            long[] c  = candidates.poll();
            int cx    = (int) c[0];
            int cz    = (int) c[1];
            long key  = c[3];

            if (scc.getChunkNow(cx, cz) != null) continue;
            if (inFlight.putIfAbsent(key, level) != null) continue;

            submittedThisTick.incrementAndGet();

            ChunkPos cp = new ChunkPos(cx, cz);
            scc.addRegionTicket(TicketType.UNKNOWN, cp, TICKET_LEVEL, cp);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void drainCompleted() {
        inFlight.entrySet().removeIf(e -> {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            return e.getValue().getChunkSource().getChunkNow(cx, cz) != null;
        });
    }

    /** 0 = NORMAL, 1 = FAST, 2 = EXTREME */
    private static int tier(double speed) {
        if (speed >= SPEED_EXTREME) return 2;
        if (speed >= SPEED_FAST)    return 1;
        return 0;
    }

    public static void onPlayerLeave(UUID id) {
        lastPos.remove(id);
        smoothVel.remove(id);
    }

    private static boolean isSafeToPreload(ServerLevel level) {
        if (SAFE_DIMENSIONS.contains(level.dimension())) return true;
        return ConfigCache.CHUNK_PRELOAD_MODDED_DIMS;
    }

    public static int getInFlightCount() { return inFlight.size(); }
    public static int getPendingCount()  { return inFlight.size(); }

    private static double lerp(double a, double b, double alpha) {
        return a + (b - a) * alpha;
    }
}
