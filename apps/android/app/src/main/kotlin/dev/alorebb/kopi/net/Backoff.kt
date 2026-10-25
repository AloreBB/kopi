package dev.alorebb.kopi.net

/** Reconnect delays: 1, 2, 4, 8, 16, 30, 30… seconds. Thread-safe. */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 30_000) {
    private var current = initialMs

    @Synchronized
    fun next(): Long {
        val value = current
        current = (current * 2).coerceAtMost(maxMs)
        return value
    }

    @Synchronized
    fun reset() {
        current = initialMs
    }
}
