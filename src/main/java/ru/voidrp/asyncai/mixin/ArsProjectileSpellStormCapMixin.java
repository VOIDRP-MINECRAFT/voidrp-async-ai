package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prevents an ars_nouveau projectile-spell storm from freezing the server tick.
 *
 * === Observed HUNG_TICK 2026-07-25 13:37 ===
 * A lag contraption spawned a swarm of {@code EntityOrbitProjectile} /
 * {@code EntityProjectileSpell} (ars_nouveau spell projectiles) at the same spot where
 * sable's {@code SubLevelInclusiveLevelEntityGetter} inflates entity counts. Every tick
 * each projectile runs {@code tick -> traceAnyHit -> findHitEntity ->
 * ProjectileUtil.getEntityHitResult -> Level.getEntities(AABB)} and then evaluates the
 * {@code canHitEntity} predicate against ~10,000 entities in the AABB. With hundreds of
 * concurrent projectiles that is millions of predicate evaluations per tick — the main
 * thread spun and the Watchdog fired repeated thread dumps (top frames:
 * {@code EntityProjectileSpell.canHitEntity} / {@code EntityOrbitProjectile.getType}).
 * Admin cleared it manually with {@code /kill @e[type=ars_nouveau:orbit_*]}.
 *
 * This is the same failure family as {@link ArsLightningHitDedupMixin} (runaway mod-entity
 * count saturating a per-tick O(entities) scan), just via the spell-projectile path instead
 * of LightningEntity — which the lightning cap does not cover.
 *
 * Fix: a global per-~50 ms-window budget. Once more than {@link #VOIDRP_MAX_PER_WINDOW}
 * ars projectile-spell ticks occur inside one server tick, the excess projectiles are
 * {@link Entity#discard() discarded} and their tick cancelled, so a swarm can never
 * death-spiral the tick. Legitimate spellcasting produces a handful of concurrent
 * projectiles — always far under budget and untouched.
 *
 * {@code EntityOrbitProjectile extends EntityProjectileSpell} and its {@code tick()} calls
 * {@code super.tick()}, so injecting the HEAD of {@code EntityProjectileSpell.tick} covers
 * every ars spell projectile. remap=false: it is a mod class on plain names; the
 * {@code Entity#discard()} call uses normal MC mappings (official Mojang mappings on
 * NeoForge 1.21.1, so no remap needed there either).
 */
@Mixin(targets = "com.hollingsworth.arsnouveau.common.entity.EntityProjectileSpell", remap = false)
public abstract class ArsProjectileSpellStormCapMixin {

    /** Max ars projectile-spell ticks processed per ~50 ms window before excess are culled. */
    @Unique
    private static final int VOIDRP_MAX_PER_WINDOW = 96;

    @Unique
    private static final AtomicInteger voidrp$windowCount = new AtomicInteger();

    @Unique
    private static volatile long voidrp$window = 0L;

    @Unique
    private static volatile long voidrp$lastStormLog = 0L;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void voidrp$capProjectileSpells(CallbackInfo ci) {
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
                    "[VoidRP Async AI] ars_nouveau spell-projectile storm — >{} projectiles/tick; "
                    + "discarding excess to keep the server tick alive", VOIDRP_MAX_PER_WINDOW);
            }
        }
    }
}
