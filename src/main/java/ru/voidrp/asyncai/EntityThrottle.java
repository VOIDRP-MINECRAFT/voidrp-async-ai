package ru.voidrp.asyncai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DABS-style (Dynamic Activation of Brain & Sensors) entity AI throttle.
 *
 * Distance to the nearest player is re-evaluated every DIST_CACHE_TICKS ticks
 * per entity to avoid calling distanceToSqr() on every single mob every tick.
 *
 * Type-aware rules:
 *   Boss mobs (EnderDragon, Wither, Warden, ElderGuardian) — NEVER throttled.
 *   Passive mobs (Animal, WaterAnimal, AmbientCreature)     — 1.5× more aggressive.
 *   All others                                               — standard schedule.
 *
 * Adaptive: throttle intervals are multiplied by {@link AdaptiveThrottle#getLoadFactor()}
 * which rises above 1.0 when the server is lagging.
 */
public final class EntityThrottle {

    /** Re-check distance to nearest player every N ticks. */
    private static final int DIST_CACHE_TICKS = 10;

    private static final ConcurrentHashMap<UUID, int[]>    counters      = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, int[]>    brainCounters = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, double[]> distCache     = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API — goal / target selector (MobAiMixin)
    // -------------------------------------------------------------------------

    /**
     * Returns true if goal and target selector ticks should be skipped.
     * Also advances the per-entity counter (call exactly once per aiStep, via
     * {@code throttleGoalSelector} in MobAiMixin).
     */
    public static boolean shouldSkipGoals(Mob mob) {
        if (!ConfigCache.DABS_ENABLED) return false;
        if (mob.level().isClientSide()) return false;
        if (isBoss(mob)) return false;

        double distSq = getCachedDistSq(mob);
        double near   = ConfigCache.THROTTLE_NEAR_DIST;
        double far    = ConfigCache.THROTTLE_FAR_DIST;
        double vfar   = ConfigCache.THROTTLE_VFAR_DIST;

        double load   = AdaptiveThrottle.getLoadFactor();
        double typeX  = isPassive(mob) ? 1.5 : 1.0;

        int mod;
        if      (distSq > vfar * vfar) mod = (int) (8 * load * typeX);
        else if (distSq > far  * far)  mod = (int) (4 * load * typeX);
        else if (distSq > near * near) mod = (int) (2 * typeX);
        else return false; // always run for close mobs

        mod = Math.max(2, mod);

        UUID id = mob.getUUID();
        int[] counter = counters.computeIfAbsent(id, k -> new int[1]);
        counter[0]++;
        return (counter[0] % mod) != 0;
    }

    // -------------------------------------------------------------------------
    // Public API — brain sensor throttle (BrainThrottleMixin)
    // -------------------------------------------------------------------------

    /**
     * Returns true if Brain.tickSensors() should be skipped this tick.
     * Uses a dedicated counter so it doesn't interfere with the goal counter.
     */
    public static boolean shouldSkipBrainSensors(Mob mob) {
        double distSq = getCachedDistSq(mob);
        double near   = ConfigCache.THROTTLE_NEAR_DIST;
        double far    = ConfigCache.THROTTLE_FAR_DIST;
        double vfar   = ConfigCache.THROTTLE_VFAR_DIST;

        double load = AdaptiveThrottle.getLoadFactor();

        int mod;
        if      (distSq > vfar * vfar) mod = (int) (20 * load);
        else if (distSq > far  * far)  mod = (int) ( 8 * load);
        else if (distSq > near * near) mod = (int) ( 4 * load);
        else return false;

        mod = Math.max(2, mod);

        UUID id = mob.getUUID();
        int[] counter = brainCounters.computeIfAbsent(id, k -> new int[1]);
        counter[0]++;
        return (counter[0] % mod) != 0;
    }

    // -------------------------------------------------------------------------
    // Public API — navigation throttle (NavigationThrottleMixin)
    // -------------------------------------------------------------------------

    /**
     * Returns true if PathNavigation.tick() should be skipped.
     *
     * Throttle schedule (distance from nearest player):
     *   ≤ 64 blocks  — tick every frame (full responsiveness)
     *   64–96 blocks — tick every 2 frames  (halves VoxelShape checks for mid-range mobs)
     *   > 96 blocks  — tick every 4+ frames (adaptive)
     */
    public static boolean shouldSkipNavigation(Mob mob) {
        if (!ConfigCache.NAV_THROTTLE_ENABLED) return false;
        if (mob.level().isClientSide()) return false;
        if (isBoss(mob)) return false;

        double distSq = getCachedDistSq(mob);
        double far    = ConfigCache.THROTTLE_FAR_DIST;
        double vfar   = ConfigCache.THROTTLE_VFAR_DIST;

        if (distSq <= far * far) return false; // ≤64 blocks: always tick

        double load = AdaptiveThrottle.getLoadFactor();
        int mod = (distSq > vfar * vfar)
            ? (int) Math.max(4, 4 * load)  // >96: every 4+ ticks
            : (int) Math.max(2, 2 * load); // 64-96: every 2+ ticks

        UUID id = mob.getUUID();
        int[] counter = counters.computeIfAbsent(id, k -> new int[1]);
        return (counter[0] % mod) != 0;
    }

    // -------------------------------------------------------------------------
    // Public API — hibernation (EntityHibernateMixin)
    // -------------------------------------------------------------------------

    /**
     * Returns true if aiStep() should be completely skipped (full hibernation).
     * Threshold: {@link AiConfig#HIBERNATE_DIST}.
     */
    public static boolean shouldHibernate(Mob mob) {
        if (!ConfigCache.HIBERNATE_ENABLED) return false;
        if (mob.level().isClientSide()) return false;
        if (isBoss(mob)) return false;

        double distSq  = getCachedDistSq(mob);
        double hibDist = ConfigCache.HIBERNATE_DIST;
        return distSq > hibDist * hibDist;
    }

    // -------------------------------------------------------------------------
    // Public helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true for mobs that must never be throttled or hibernated.
     * Public so BrainThrottleMixin can access it without duplicating the list.
     */
    public static boolean isBoss(Mob mob) {
        return mob instanceof EnderDragon
            || mob instanceof WitherBoss
            || mob instanceof Warden
            || mob instanceof ElderGuardian;
    }

    /** Remove state for entities that have been unloaded or killed. */
    public static void cleanup(Iterable<? extends Mob> liveMobs) {
        var liveIds = new HashSet<UUID>();
        for (Mob m : liveMobs) liveIds.add(m.getUUID());
        counters.keySet().retainAll(liveIds);
        brainCounters.keySet().retainAll(liveIds);
        distCache.keySet().retainAll(liveIds);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static boolean isPassive(Mob mob) {
        return mob instanceof Animal
            || mob instanceof WaterAnimal
            || mob instanceof AmbientCreature;
    }

    /** Returns cached squared distance to nearest player. Refreshes every DIST_CACHE_TICKS. */
    private static double getCachedDistSq(Mob mob) {
        UUID id      = mob.getUUID();
        int[]    ctr = counters.computeIfAbsent(id, k -> new int[1]);
        double[] val = distCache.computeIfAbsent(id, k -> new double[]{Double.MAX_VALUE});

        if (ctr[0] % DIST_CACHE_TICKS == 0) {
            val[0] = computeMinDistSq(mob);
        }
        return val[0];
    }

    private static double computeMinDistSq(Mob mob) {
        var players = mob.level().players();
        if (players.isEmpty()) return Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        for (var p : players) {
            double d = mob.distanceToSqr(p);
            if (d < min) min = d;
        }
        return min;
    }
}
