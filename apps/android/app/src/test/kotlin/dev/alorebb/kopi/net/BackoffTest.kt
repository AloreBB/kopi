package dev.alorebb.kopi.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackoffTest {
    @Test
    fun backoffSequence() {
        val backoff = Backoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), List(7) { backoff.next() })
        backoff.reset()
        assertEquals(1000L, backoff.next())
    }
}
