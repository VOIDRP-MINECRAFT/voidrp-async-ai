package ru.voidrp.asyncai;

import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A live view over a {@link Set} that never hands a {@code null} element to a
 * reader, while leaving every write operation delegated to the backing set.
 *
 * <p>Why this exists: {@code GoalSelector.availableGoals} is a fastutil
 * {@code ObjectLinkedOpenHashSet}, which is not thread-safe. When it is mutated
 * while another party iterates it, the iterator can hand out {@code null} — and
 * every consumer immediately calls {@code WrappedGoal.getGoal()} on it and takes
 * the whole world tick down with an NPE (crash 2026-09-10 16:31, epicfight
 * {@code MobPatch.selectGoalToRemove}; earlier 2026-08-25, geneticsresequenced
 * {@code giveFrenzyGoals}).
 *
 * <p>The previous guard scanned the set and substituted a clean snapshot only if
 * it actually saw a {@code null}. That left the real window open: when the scan
 * found the set clean it returned the <em>original</em>, so a {@code null}
 * surfacing during the caller's own iteration still crashed the server — which is
 * exactly what happened on 2026-09-10. Hence a view rather than a conditional copy.
 *
 * <p>A copy is deliberately not used: mods legitimately mutate the set they get
 * back from {@code getAvailableGoals()} (removing AI goals is a common pattern),
 * and handing them a detached snapshot would silently discard those writes.
 * Reads are filtered, writes go straight through.
 */
public final class NullFilteringSetView<T> implements Set<T> {

    private final Set<T> backing;

    public NullFilteringSetView(Set<T> backing) {
        this.backing = Objects.requireNonNull(backing);
    }

    // ---- reads: null-free, and tolerant of the set mutating underneath us ----

    @Override
    public Iterator<T> iterator() {
        Iterator<T> it = backing.iterator();
        return new Iterator<>() {
            private T next;
            private boolean hasNext;

            private void advance() {
                while (!hasNext) {
                    T candidate;
                    try {
                        if (!it.hasNext()) {
                            return;
                        }
                        candidate = it.next();
                    } catch (RuntimeException corrupted) {
                        // Concurrent structural change corrupted the iterator —
                        // ending the iteration early is always better than
                        // propagating into a world tick.
                        return;
                    }
                    if (candidate != null) {
                        next = candidate;
                        hasNext = true;
                    }
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return hasNext;
            }

            @Override
            public T next() {
                advance();
                if (!hasNext) {
                    throw new java.util.NoSuchElementException();
                }
                T value = next;
                next = null;
                hasNext = false;
                return value;
            }

            @Override
            public void remove() {
                it.remove();
            }
        };
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        for (T element : this) {
            action.accept(element);
        }
    }

    @Override
    public Stream<T> stream() {
        return backing.stream().filter(Objects::nonNull);
    }

    @Override
    public Stream<T> parallelStream() {
        return backing.parallelStream().filter(Objects::nonNull);
    }

    @Override
    public Object[] toArray() {
        return stream().toArray();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <A> A[] toArray(A[] a) {
        return (A[]) stream().toArray(size -> java.util.Arrays.copyOf(a, size));
    }

    @Override
    public boolean isEmpty() {
        return !iterator().hasNext();
    }

    // ---- size and membership: straight delegation ----
    // size() may still count a transient null; nothing consumes it in a way that
    // can crash, and keeping it O(1) matters on the mob-tick path.

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean contains(Object o) {
        return o != null && backing.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    // ---- writes: delegated unchanged, so callers keep mutating the real set ----

    @Override
    public boolean add(T t) {
        return backing.add(t);
    }

    @Override
    public boolean remove(Object o) {
        return backing.remove(o);
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        return backing.addAll(c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return backing.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return backing.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super T> filter) {
        return backing.removeIf(e -> e == null || filter.test(e));
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public String toString() {
        return "NullFilteringSetView" + backing;
    }
}
