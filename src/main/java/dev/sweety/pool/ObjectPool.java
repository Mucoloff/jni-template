package dev.sweety.pool;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Minimal object pool to recycle expensive instances and cut allocation / GC
 * pressure. Two strategies: {@link #threadLocal} (zero contention, release on the
 * acquiring thread) and {@link #shared} (lock-free, cross-thread).
 *
 * <p>Mirrors the design of {@code dev.sweety.math.pool.ObjectPool}, trimmed to
 * what this library needs.
 *
 * @param <T> pooled type
 */
public interface ObjectPool<T> {

    @Acquire
    T acquire();

    @Release
    void release(T obj);

    /** Borrow for the duration of {@code fn}, then auto-release. */
    @Borrows
    default <V> V use(Function<T, V> fn) {
        T obj = acquire();
        try {
            return fn.apply(obj);
        } finally {
            release(obj);
        }
    }

    @Borrows
    default void consume(Consumer<T> fn) {
        T obj = acquire();
        try {
            fn.accept(obj);
        } finally {
            release(obj);
        }
    }

    static <T> ObjectPool<T> threadLocal(Supplier<T> factory, Consumer<T> reset,
                                         Consumer<T> onDiscard, int maxPerThread) {
        return new ThreadLocalPool<>(factory, reset, onDiscard, maxPerThread);
    }

    static <T> ObjectPool<T> threadLocal(Supplier<T> factory, int maxPerThread) {
        return new ThreadLocalPool<>(factory, null, null, maxPerThread);
    }

    static <T> ObjectPool<T> shared(Supplier<T> factory, Consumer<T> reset,
                                    Consumer<T> onDiscard, int maxSize) {
        return new SharedPool<>(factory, reset, onDiscard, maxSize);
    }

    static <T> ObjectPool<T> shared(Supplier<T> factory, int maxSize) {
        return new SharedPool<>(factory, null, null, maxSize);
    }

    /** Per-thread deque; no synchronization. Must release on the acquiring thread. */
    final class ThreadLocalPool<T> implements ObjectPool<T> {
        private final Supplier<T> factory;
        private final Consumer<T> reset;
        private final Consumer<T> onDiscard;
        private final int max;
        private final ThreadLocal<ArrayDeque<T>> tl = ThreadLocal.withInitial(ArrayDeque::new);

        ThreadLocalPool(Supplier<T> factory, Consumer<T> reset, Consumer<T> onDiscard, int max) {
            this.factory = factory;
            this.reset = reset;
            this.onDiscard = onDiscard;
            this.max = max;
        }

        @Override
        public T acquire() {
            T obj = tl.get().pollFirst();
            return obj != null ? obj : factory.get();
        }

        @Override
        public void release(T obj) {
            if (obj == null) return;
            if (reset != null) reset.accept(obj);
            ArrayDeque<T> d = tl.get();
            if (d.size() < max) d.addFirst(obj);
            else if (onDiscard != null) onDiscard.accept(obj);
        }
    }

    /** Lock-free cross-thread pool, size-bounded via CAS. */
    final class SharedPool<T> implements ObjectPool<T> {
        private final Supplier<T> factory;
        private final Consumer<T> reset;
        private final Consumer<T> onDiscard;
        private final int max;
        private final ConcurrentLinkedDeque<T> deque = new ConcurrentLinkedDeque<>();
        private final AtomicInteger size = new AtomicInteger();

        SharedPool(Supplier<T> factory, Consumer<T> reset, Consumer<T> onDiscard, int max) {
            this.factory = factory;
            this.reset = reset;
            this.onDiscard = onDiscard;
            this.max = max;
        }

        @Override
        public T acquire() {
            T obj = deque.pollFirst();
            if (obj == null) return factory.get();
            size.decrementAndGet();
            return obj;
        }

        @Override
        public void release(T obj) {
            if (obj == null) return;
            if (reset != null) reset.accept(obj);
            int s;
            do {
                s = size.get();
                if (s >= max) {
                    if (onDiscard != null) onDiscard.accept(obj);
                    return;
                }
            } while (!size.compareAndSet(s, s + 1));
            deque.addFirst(obj);
        }
    }
}
