package dev.alorebb.kopi.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChangeGateTest {
    @Test
    fun nullClipIsIgnored() {
        val gate = ChangeGate()
        assertFalse(gate.accept(null, ClipSource.POLL))
        assertFalse(gate.accept(null, ClipSource.LISTENER))
        assertEquals(ClipSource.LISTENER, gate.source)
    }

    @Test
    fun sameFingerprintAcceptedOnce() {
        val gate = ChangeGate()
        assertTrue(gate.accept("a", ClipSource.LISTENER))
        assertFalse(gate.accept("a", ClipSource.LISTENER))
        assertFalse(gate.accept("a", ClipSource.POLL))
        assertTrue(gate.accept("b", ClipSource.LISTENER))
    }

    @Test
    fun pollSeeingUnseenChangeSwitchesToPoll() {
        val gate = ChangeGate()
        assertTrue(gate.accept("a", ClipSource.LISTENER))
        assertTrue(gate.accept("b", ClipSource.POLL))
        assertEquals(ClipSource.POLL, gate.source)
    }

    @Test
    fun listenerKeepsListenerSource() {
        val gate = ChangeGate()
        assertTrue(gate.accept("a", ClipSource.LISTENER))
        assertFalse(gate.accept("a", ClipSource.POLL))
        assertTrue(gate.accept("b", ClipSource.LISTENER))
        assertEquals(ClipSource.LISTENER, gate.source)
    }
}
