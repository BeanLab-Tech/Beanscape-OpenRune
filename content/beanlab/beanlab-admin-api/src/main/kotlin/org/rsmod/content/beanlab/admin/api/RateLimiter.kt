package org.rsmod.content.beanlab.admin.api

import java.util.concurrent.ConcurrentHashMap

/**
 * Allows at most [limit] calls per key in any one-second window. Safe to call from many threads.
 */
public class RateLimiter(private val limit: Int, private val clock: () -> Long = System::nanoTime) {
    private val windows = ConcurrentHashMap<Int, ArrayDeque<Long>>()

    public fun allow(key: Int): Boolean {
        val now = clock()
        val window = windows.computeIfAbsent(key) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && now - window.first() >= ONE_SECOND_NANOS) {
                window.removeFirst()
            }
            if (window.size >= limit) return false
            window.addLast(now)
            return true
        }
    }

    private companion object {
        private const val ONE_SECOND_NANOS = 1_000_000_000L
    }
}
