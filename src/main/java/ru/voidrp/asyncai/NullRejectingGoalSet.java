package ru.voidrp.asyncai;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The backing set for {@code GoalSelector.availableGoals}, which refuses to hold
 * {@code null} — and says who tried.
 *
 * <p>Why this exists, after two weaker attempts failed:
 *
 * <ol>
 *   <li>Guarding {@code getAvailableGoals()} did nothing for vanilla's own
 *       {@code removeGoal}/{@code tick}, which read the field directly.</li>
 *   <li>Purging the field at the head of those methods still crashed — on
 *       2026-09-10 19:04 and 20:02 the NPE landed <em>inside</em>
 *       {@code GoalSelector.removeGoal}'s own {@code removeIf}, microseconds after
 *       our purge had just cleaned the same set. A null that comes back that fast
 *       cannot be chased by cleaning; it has to be refused at insertion.</li>
 * </ol>
 *
 * <p>Vanilla only ever inserts through {@code addGoal}, which wraps a non-null
 * {@code WrappedGoal}, so nothing legitimate loses a goal here. A rejected insert
 * logs one stack trace — that trace names the mod actually responsible, which is
 * the thing three rounds of guarding never established.
 *
 * <p>{@link LinkedHashSet} also replaces fastutil's {@code ObjectLinkedOpenHashSet}.
 * Iteration order (which decides goal priority evaluation) is preserved, and unlike
 * the fastutil set a concurrent modification fails loudly with a
 * {@code ConcurrentModificationException} instead of silently yielding nulls — so
 * if the real cause is off-thread access, the next crash report will say so plainly
 * rather than pointing at another innocent consumer.
 */
public final class NullRejectingGoalSet<T> extends LinkedHashSet<T> {

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
