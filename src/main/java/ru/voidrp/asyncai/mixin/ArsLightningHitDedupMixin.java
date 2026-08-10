package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixes two distinct main-thread freezes caused by ars_nouveau's LightningEntity.
 *
 * === 1. Per-bolt O(n^2) dedup (original fix) ===
 * The entity keeps a {@code List<Integer> hitEntities} (an ArrayList) of the
 * ids of every entity it has already struck, so it never hits the same entity
 * twice. Each tick it queries {@code Level.getEntities(AABB)} and, for every
 * entity in range, calls {@code hitEntities.contains(id)} (O(n)) before adding.
 * With a dense entity clump in the bolt's AABB this degrades to O(n^2) *within a
 * single tick*. We back the membership test with a parallel {@link HashSet}
 * (O(1)), keeping behaviour identical.
 *
 * === 2. Runaway bolt storm (added 2026-07-20) ===
 * A lag-machine / command-loop contraption (observed: shtreimel sub-level churn +
 * "executed 65536 commands" limit hits) spawned hundreds of LightningEntity per
 * second, each scanning a 513-entity clump. Even at O(n) per bolt the sheer
 * *count* saturated the server thread (Watchdog HUNG_TICK 2026-07-20 15:05), and
 * the per-instance warn below flooded the HDD-backed log, compounding the stall.
 *
 * Fix: a global per-tick budget in {@link #voidrp$capLightning}. Once more than
 * {@link #VOIDRP_MAX_PER_WINDOW} LightningEntity ticks occur inside one ~50 ms
 * window (≈ one server tick) the excess bolts are {@link Entity#discard() discarded}
 * and their tick cancelled, so a runaway storm can never death-spiral the tick.
 * Legitimate use (a handful of concurrent bolts) is always far under budget and
 * untouched.
 *
 * === 3. Single-bolt giant-clump scan (added 2026-07-22) ===
 * Observed HUNG_TICK 2026-07-22 17:06: a *single* long-lived bolt sitting over a
 * huge entity pile (item drops / mob cram) at an unloaded chunk. Each tick the bolt
 * calls {@code Level.getEntities(AABB)} and then, for every entity returned, runs
 * {@code Entity#thunderHit -> hurt -> isInvulnerableTo} (a neoforge event post per
 * entity). The per-tick dedup only skips the mob-effect, NOT thunderHit, so the loop
 * cost scales with clump size *every* tick and the storm cap (bolt count) never
 * triggers for one bolt. The main thread spun for 100 s+.
 *
 * Fix: {@link #voidrp$capTargets} redirects that {@code getEntities} call and caps
 * the returned list to {@link #VOIDRP_MAX_TARGETS_PER_BOLT}. Real lightning hits a
 * handful of entities — far under the cap — so behaviour is unchanged; only a
 * pathological pile is truncated (remaining entities are simply not struck, which
 * has no gameplay meaning for a 100+ item pile).
 *
 * remap=false on the List redirects: LightningEntity is a mod class and those are
 * plain JDK calls. The Entity#discard() call in the inject uses normal MC mappings.
 * The getEntities redirect stays remap=false too — NeoForge 1.21.1 runs on official
 * Mojang mappings, so the method name matches the descriptor as written.
 */
@Mixin(targets = "com.hollingsworth.arsnouveau.common.entity.LightningEntity", remap = false)
public abstract class ArsLightningHitDedupMixin {

    /** Max LightningEntity ticks processed per ~50 ms window before excess bolts are culled. */
    @Unique
    private static final int VOIDRP_MAX_PER_WINDOW = 64;

    /** Max entities a single bolt will strike per tick; excess in the AABB are ignored this tick. */
    @Unique
    private static final int VOIDRP_MAX_TARGETS_PER_BOLT = 128;

    @Unique
    private static volatile long voidrp$lastClumpLog = 0L;

    @Unique
    private static final AtomicInteger voidrp$windowCount = new AtomicInteger();

    @Unique
    private static volatile long voidrp$window = 0L;

    @Unique
    private static volatile long voidrp$lastStormLog = 0L;

    @Unique
    private Set<Integer> voidrp$hitSet;

    @Unique
    private Set<Integer> voidrp$set(List<Integer> backing) {
        if (voidrp$hitSet == null) {
            // Seed from anything already present before our first redirect fires.
            voidrp$hitSet = new HashSet<>(backing);
        }
        return voidrp$hitSet;
    }

    /**
     * Global storm throttle: cap the number of LightningEntity ticks per server tick.
     * Excess bolts are discarded so a runaway spawner cannot saturate the main thread.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void voidrp$capLightning(CallbackInfo ci) {
        long now = System.currentTimeMillis();
        long win = now / 50L; // ~one server tick
        if (win != voidrp$window) {
            voidrp$window = win;
            voidrp$windowCount.set(0);
        }
        if (voidrp$windowCount.incrementAndGet() > VOIDRP_MAX_PER_WINDOW) {
            ((Entity) (Object) this).discard();
            ci.cancel();
            if (now - voidrp$lastStormLog > 5000L) {
                voidrp$lastStormLog = now;
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP Async AI] ars_nouveau LightningEntity storm — >{} bolts/tick; "
                    + "discarding excess to keep the server tick alive", VOIDRP_MAX_PER_WINDOW);
            }
        }
    }

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Ljava/util/List;contains(Ljava/lang/Object;)Z"),
        require = 0,
        remap = false
    )
    private boolean voidrp$fastContains(List<Integer> list, Object id) {
        return voidrp$set(list).contains(id);
    }

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"),
        require = 0,
        remap = false
    )
    private boolean voidrp$fastAdd(List<Integer> list, Object id) {
        voidrp$set(list).add((Integer) id);
        return list.add((Integer) id);
    }

    /**
     * Cap how many entities a single bolt processes per tick. A pathological entity
     * pile (hundreds/thousands of item drops or crammed mobs) in the bolt's AABB would
     * otherwise force a thunderHit -> hurt -> event-post per entity every tick and spin
     * the main thread. Real strikes hit a handful of entities and never reach the cap.
     */
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"
        ),
        require = 0,
        remap = false
    )
    private List<Entity> voidrp$capTargets(Level level, Entity self, AABB box, Predicate<? super Entity> pred) {
        List<Entity> found = level.getEntities(self, box, pred);
        if (found.size() > VOIDRP_MAX_TARGETS_PER_BOLT) {
            long now = System.currentTimeMillis();
            if (now - voidrp$lastClumpLog > 5000L) {
                voidrp$lastClumpLog = now;
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP Async AI] ars_nouveau LightningEntity found {} entities in strike AABB — "
                    + "capping to {} this tick to keep the server tick alive", found.size(), VOIDRP_MAX_TARGETS_PER_BOLT);
            }
            return found.subList(0, VOIDRP_MAX_TARGETS_PER_BOLT);
        }
        return found;
    }
}
