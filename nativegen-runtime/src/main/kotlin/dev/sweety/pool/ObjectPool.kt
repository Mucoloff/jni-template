package dev.sweety.pool

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * Minimal object pool to recycle expensive instances and cut allocation / GC
 * pressure. Two strategies: [.threadLocal] (zero contention, release on the
 * acquiring thread) and [.shared] (lock-free, cross-thread).
 * 
 * 
 * Mirrors the design of `dev.sweety.math.pool.ObjectPool`, trimmed to
 * what this library needs.
 * 
 * @param <T> pooled type
</T> */
interface ObjectPool<T> {
    @Acquire
    fun acquire(): T

    @Release
    fun release(obj: T)

    /** Borrow for the duration of `fn`, then auto-release.  */
    @Borrows
    fun <V> use(fn: Function<T?, V?>): V? {
        val obj = acquire()
        try {
            return fn.apply(obj)
        } finally {
            release(obj)
        }
    }

    @Borrows
    fun consume(fn: Consumer<T>) {
        val obj = acquire()
        try {
            fn.accept(obj)
        } finally {
            release(obj)
        }
    }

    /** Per-thread deque; no synchronization. Must release on the acquiring thread.  */
    class ThreadLocalPool<T> internal constructor(
        private val factory: Supplier<T?>,
        private val reset: Consumer<T>?,
        private val onDiscard: Consumer<T>?,
        private val max: Int
    ) : ObjectPool<T?> {
        private val tl: ThreadLocal<ArrayDeque<T>> = ThreadLocal.withInitial { ArrayDeque() }

        override fun acquire(): T? {
            val obj: T? = tl.get().firstOrNull()
            return obj ?: factory.get()
        }

        override fun release(obj: T?) {
            if (obj == null) return
            reset?.accept(obj)
            val d = tl.get()
            if (d.size < max) d.addFirst(obj)
            else onDiscard?.accept(obj)
        }
    }

    /** Lock-free cross-thread pool, size-bounded via CAS.  */
    class SharedPool<T> internal constructor(
        private val factory: Supplier<T?>,
        private val reset: Consumer<T>?,
        private val onDiscard: Consumer<T>?,
        private val max: Int
    ) : ObjectPool<T?> {
        private val deque = ConcurrentLinkedDeque<T & Any>()
        private val size = AtomicInteger()

        override fun acquire(): T? {
            val obj = deque.pollFirst() ?: return factory.get()
            size.decrementAndGet()
            return obj
        }

        override fun release(obj: T?) {
            if (obj == null) return
            reset?.accept(obj)
            var s: Int
            do {
                s = size.get()
                if (s >= max) {
                    onDiscard?.accept(obj)
                    return
                }
            } while (!size.compareAndSet(s, s + 1))
            deque.addFirst(obj)
        }
    }

    companion object {
        @JvmStatic
        fun <T> threadLocal(
            factory: Supplier<T?>, reset: Consumer<T>?,
            onDiscard: Consumer<T>?, maxPerThread: Int
        ): ObjectPool<T?> {
            return ThreadLocalPool(factory, reset, onDiscard, maxPerThread)
        }

        @JvmStatic
        fun <T> threadLocal(factory: Supplier<T?>, maxPerThread: Int): ObjectPool<T?> {
            return ThreadLocalPool(factory, null, null, maxPerThread)
        }

        @JvmStatic
        fun <T> shared(
            factory: Supplier<T?>, reset: Consumer<T>?,
            onDiscard: Consumer<T>?, maxSize: Int
        ): ObjectPool<T?> {
            return SharedPool(factory, reset, onDiscard, maxSize)
        }

        @JvmStatic
        fun <T> shared(factory: Supplier<T?>, maxSize: Int): ObjectPool<T?> {
            return SharedPool(factory, null, null, maxSize)
        }
    }
}
