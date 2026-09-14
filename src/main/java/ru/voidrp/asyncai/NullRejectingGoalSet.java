package ru.voidrp.asyncai;

import java.util.Collection;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The backing set for {@code GoalSelector.availableGoals}. It refuses to hold
 * {@code null} — and says who tried — and, just as importantly, it cannot throw a
 * {@link java.util.ConcurrentModificationException} while a mob's AI is being torn
 * down and rebuilt.
 *
 * <p>Why this exists, after several weaker attempts failed:
 *
 * <ol>
 *   <li>Guarding {@code getAvailableGoals()} did nothing for vanilla's own
 *       {@code removeGoal}/{@code tick}, which read the field directly.</li>
 *   <li>Purging the field at the head of those methods still crashed — on
 *       2026-09-10 19:04 and 20:02 the NPE landed <em>inside</em>
 *       {@code GoalSelector.removeGoal}'s own {@code removeIf}, microseconds after
 *       our purge had just cleaned the same set. A null that comes back that fast
 *       cannot be chased by cleaning; it has to be refused at insertion — which the
 *       {@code add} override below does.</li>
 *   <li>Refusing nulls stopped the {@code NPE} but not the underlying disease. A
 *       plain {@link java.util.LinkedHashSet} backing (the previous version of this
 *       class) surfaced the real cause loudly on 2026-09-11 16:08: a
 *       {@code ConcurrentModificationException} thrown from {@code removeGoal},
 *       driven by epicfight {@code MobPatch.initAI} (spider natural-spawn). Epic
 *       Fight iterates the goal set and calls {@code removeGoal} for entries as it
 *       goes — a re-entrant iterate-and-remove. fastutil's set tolerated that by
 *       silently handing back {@code null}/garbage (hence every earlier NPE);
 *       {@code LinkedHashSet} detected it and threw CME. Same bug, two faces.</li>
 * </ol>
 *
 * <p>The cure for <em>both</em> faces is a backing whose iteration is decoupled
 * from its structure. {@link CopyOnWriteArraySet} iterates a point-in-time snapshot
 * array, so a structural change during iteration — whether re-entrant (Epic Fight's
 * pattern) or genuinely off-thread — can neither throw {@code CME} nor expose a
 * torn/null slot. Its {@code add}, {@code remove} and {@code removeIf} are atomic
 * snapshot rewrites under a lock, so the set is also safe if the real cause turns
 * out to be another thread. Insertion order (which decides goal-priority evaluation)
 * is preserved.
 *
 * <p>Cost: writes copy the array, but goal sets hold only a handful of goals and
 * are mutated only on spawn / AI-init, never per tick. The per-tick read path —
 * {@code tick()} iterating the set — is a lock-free array walk, if anything cheaper
 * than a {@code LinkedHashSet}.
 *
 * <p>Vanilla only ever inserts through {@code addGoal}, which wraps a non-null
 * {@code WrappedGoal}, so nothing legitimate loses a goal here. A rejected insert
 * logs one stack trace naming the mod responsible.
 */
public final class NullRejectingGoalSet<T> extends CopyOnWriteArraySet<T> {

    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);

    @Override
    public boolean add(T element) {
        if (element == null) {
            reportOnce();
            return false;
        }
        return super.add(element);
    }

    @Override
    public boolean addAll(Collection<? extends T> collection) {
        boolean changed = false;
        for (T element : collection) {
            changed |= add(element);
        }
        return changed;
    }

    private static void reportOnce() {
        if (!REPORTED.compareAndSet(false, true)) {
            return;
        }
        VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP] Someone tried to put a null goal into a mob's goal set — refused. "
                        + "This is the crash that took the server down repeatedly on 2026-09-10 "
                        + "(NPE on WrappedGoal.getGoal). The stack below names the culprit; "
                        + "logged once per server start.",
                new Throwable("null goal insertion — stack trace, not a crash"));
    }
}
