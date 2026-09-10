package ru.voidrp.asyncai.mixin;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.NullFilteringSetView;
import ru.voidrp.asyncai.NullRejectingGoalSet;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.Set;
import java.util.function.Predicate;
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

    @Mutable @Shadow @Final private Set<WrappedGoal> availableGoals;

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
     * Wrap the set the caller is about to iterate in a null-filtering live view.
     *
     * <p>This deliberately does NOT scan-and-maybe-copy any more. That earlier shape
     * returned the original set whenever the scan happened to find it clean, which
     * left the actual failure window wide open: the backing fastutil set is not
     * thread-safe, so a {@code null} can surface during the <em>caller's own</em>
     * iteration, after our scan already passed. That is exactly how the server
     * crashed on 2026-09-10 16:31 — epicfight {@code MobPatch.selectGoalToRemove}
     * iterating this very method's result — despite the guard being active and
     * having cleaned a different set nine seconds earlier.
     *
     * <p>{@link NullFilteringSetView} filters reads while delegating writes, so mods
     * that remove goals through the returned set keep working.
     */
    @Inject(method = "getAvailableGoals", at = @At("RETURN"), cancellable = true, require = 0)
    private void voidrp_guardGetAvailableGoals(CallbackInfoReturnable<Set<WrappedGoal>> cir) {
        Set<WrappedGoal> goals = cir.getReturnValue();
        if (goals == null || goals instanceof NullFilteringSetView) {
            return;
        }
        cir.setReturnValue(new NullFilteringSetView<>(goals));
    }

    /**
     * Physically drop any {@code null} from the backing field.
     *
     * <p>This — not the getter view — is what actually stops the crash. Vanilla's own
     * {@code removeGoal}, {@code removeAllGoals}, {@code getRunningGoals} and
     * {@code tickRunningGoals} read the {@code availableGoals} <b>field directly</b> and
     * never go through {@code getAvailableGoals()}, so guarding only the getter left
     * every one of those paths exposed. The 2026-09-10 17:16 crash came in through
     * epicfight's {@code initAI} → {@code goalSelector::removeGoal}, i.e. straight into
     * vanilla's field access, while the getter guard was live and working.
     *
     * <p>Cost is one pass over a handful of goals at the head of each of these calls —
     * the same order as the iteration each is about to do anyway.
     */
    @Unique
    private void voidrp_purgeNulls(String where) {
        Set<WrappedGoal> goals = this.availableGoals;
        if (goals == null || goals.isEmpty()) {
            return;
        }
        try {
            if (goals.removeIf(g -> g == null)) {
                voidrp_warnOnce(where, goals, 1);
            }
        } catch (RuntimeException ignored) {
            // A guard must never be the thing that breaks a tick.
        }
    }

    // Signatures below mirror net.minecraft.world.entity.ai.goal.GoalSelector (1.21.1)
    // exactly — verified against the mapped class, not assumed. A mixin injector whose
    // parameters do not match its target simply fails to apply, and with require = 0 it
    // fails *silently*: that is how an earlier version of this guard appeared to be
    // deployed while the server kept crash-looping. If these ever stop applying, the
    // "Mixing GoalSelectorNullGoalGuardMixin" lines in logs/debug.log are the check.

    /**
     * Swap the goal set for one that cannot hold a null, at construction.
     *
     * <p>This is the actual fix. Cleaning the set at the head of each vanilla method
     * was not enough: on 2026-09-10 19:04 and 20:02 the NPE happened *inside*
     * {@code removeGoal}'s own {@code removeIf}, i.e. a null reappeared within the
     * same method call our purge had just cleaned. Refusing the insert is the only
     * placement that closes that.
     *
     * <p>The purges below stay as a second line of defence for any set that somehow
     * predates or bypasses this swap.
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void voidrp_replaceGoalSet(CallbackInfo ci) {
        NullRejectingGoalSet<WrappedGoal> safe = new NullRejectingGoalSet<>();
        Set<WrappedGoal> existing = this.availableGoals;
        if (existing != null && !existing.isEmpty()) {
            for (WrappedGoal goal : existing) {
                if (goal != null) {
                    safe.add(goal);
                }
            }
        }
        this.availableGoals = safe;
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void voidrp_guardTick(CallbackInfo ci) {
        voidrp_purgeNulls("tick");
    }

    @Inject(method = "tickRunningGoals", at = @At("HEAD"), require = 0)
    private void voidrp_guardTickRunningGoals(boolean tickAllRunning, CallbackInfo ci) {
        voidrp_purgeNulls("tickRunningGoals");
    }

    /** The exact path that crashed the server on 2026-09-10 17:16 (epicfight initAI). */
    @Inject(method = "removeGoal", at = @At("HEAD"), require = 0)
    private void voidrp_guardRemoveGoal(Goal goal, CallbackInfo ci) {
        voidrp_purgeNulls("removeGoal");
    }

    @Inject(method = "removeAllGoals", at = @At("HEAD"), require = 0)
    private void voidrp_guardRemoveAllGoals(Predicate<Goal> predicate, CallbackInfo ci) {
        voidrp_purgeNulls("removeAllGoals");
    }

    /** Nothing legitimate ever adds a null; drop it at the door if something tries. */
    @Inject(method = "addGoal", at = @At("RETURN"), require = 0)
    private void voidrp_guardAddGoal(int priority, Goal goal, CallbackInfo ci) {
        voidrp_purgeNulls("addGoal");
    }
}
