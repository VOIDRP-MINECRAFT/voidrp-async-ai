package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guards {@link GoalSelector} against a {@code null} element in its
 * {@code availableGoals} set — a crash class that took the main server down
 * (crash-reports 2026-08-25 15:00 / 15:23 / 16:18 / 17:19), all identical:
 *
 *   NullPointerException: Cannot invoke "...WrappedGoal.getGoal()" because "it" is null
 *     at geneticsresequenced MobGenes.giveFrenzyGoals(MobGenes.kt:93)
 *     at geneticsresequenced EntityEvents.onEntitySpawn
 *     ... NaturalSpawner.spawnCategoryForPosition -> ServerLevel.addFreshEntity   [Server thread]
 *
 * geneticsresequenced's Frenzy handler iterates {@code targetSelector/goalSelector
 * .getAvailableGoals()} on the (single) Server thread and calls
 * {@code WrappedGoal.getGoal()} on each element; one element is {@code null}, so
 * the whole world tick throws and the server hard-crashes. (The crash exits the
 * JVM with status 0, so systemd's Restart=on-failure never fires and it stays down.)
 *
 * History / why this shape:
 *   A first attempt strip-cleaned the {@code availableGoals} *field* at HEAD of
 *   getAvailableGoals() guarded by {@code contains(null)}. It did NOT stop the
 *   crash and never logged — meaning either {@code getAvailableGoals()} returns a
 *   different collection than the raw field (a wrapper/copy inserted by another
 *   GoalSelector mixin — servercore's activation_range GoalSelectorMixin also
 *   targets this class), or the runtime set's {@code contains(null)} disagrees
 *   with its iterator. So this version does NOT trust the field or contains():
 *   it cleans the *exact reference the caller will iterate* — the return value of
 *   getAvailableGoals() — by scanning it and, only if a null (or a scan error) is
 *   seen, substituting a null-free {@link LinkedHashSet} snapshot. In the normal
 *   (clean) case the original set is returned untouched, so there is no behaviour
 *   or identity change for the overwhelming majority of calls.
 *
 * getAvailableGoals() is a read accessor (vanilla ticking uses the field directly,
 * not this method), so the extra scan — over a handful of goals — is off the hot
 * path. tick() is additionally guarded best-effort against a persistent null.
 *
 * voidrp_async_ai is NOT the source of the null (it never mutates goal sets off
 * thread). The throttled warn records the offending set's runtime class + mob so
 * the culprit mod can be fixed upstream.
 */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorNullGoalGuardMixin {

    @Shadow @Final private Set<WrappedGoal> availableGoals;

    @Unique
    private static final AtomicLong voidrp_lastNullGoalWarn = new AtomicLong(0L);

    @Unique
    private void voidrp_warnOnce(String where, Object badSet, int removed) {
        long now = System.currentTimeMillis();
        long last = voidrp_lastNullGoalWarn.get();
        if (now - last >= 30_000L && voidrp_lastNullGoalWarn.compareAndSet(last, now)) {
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] GoalSelector null-goal guard ({}) — removed {} null goal(s) from a mob's goal set " +
                "(set impl: {}). Prevents the geneticsresequenced giveFrenzyGoals / vanilla tick NPE that " +
                "crashed the server on 2026-08-25. Some mod inserts null into a goal set; this guard " +
                "neutralises it. Further occurrences suppressed for 30 s.",
                where, removed, badSet == null ? "null" : badSet.getClass().getName());
        }
    }

    /**
     * Clean the exact set the caller is about to iterate. Returns a null-free set:
     * the original if it was already clean, otherwise a fresh snapshot with nulls
     * (and any element that errored mid-scan) dropped.
     */
    @Inject(method = "getAvailableGoals", at = @At("RETURN"), cancellable = true, require = 0)
    private void voidrp_guardGetAvailableGoals(CallbackInfoReturnable<Set<WrappedGoal>> cir) {
        Set<WrappedGoal> goals = cir.getReturnValue();
        if (goals == null || goals.isEmpty()) {
            return;
        }
        LinkedHashSet<WrappedGoal> clean = new LinkedHashSet<>();
        boolean foundNull = false;
        try {
            for (WrappedGoal g : goals) {
                if (g == null) {
                    foundNull = true;
                } else {
                    clean.add(g);
                }
            }
        } catch (RuntimeException scanError) {
            // Iterator itself blew up (e.g. transient structural inconsistency) —
            // fall back to the null-free snapshot we managed to build.
            foundNull = true;
        }
        if (foundNull) {
            cir.setReturnValue(clean);
            voidrp_warnOnce("getAvailableGoals", goals, Math.max(1, goals.size() - clean.size()));
        }
    }

    /** Best-effort: physically drop any persistent null from the field before vanilla ticks it. */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void voidrp_guardTick(CallbackInfo ci) {
        Set<WrappedGoal> goals = this.availableGoals;
        if (goals == null || goals.isEmpty()) {
            return;
        }
        try {
            if (goals.removeIf(g -> g == null)) {
                voidrp_warnOnce("tick", goals, 1);
            }
        } catch (RuntimeException ignored) {
            // never let the guard itself break a tick
        }
    }
}
