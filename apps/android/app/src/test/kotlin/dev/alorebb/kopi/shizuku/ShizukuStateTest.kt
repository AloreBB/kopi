package dev.alorebb.kopi.shizuku

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShizukuStateTest {
    @Test
    fun derivesInOrder() {
        assertEquals(ShizukuState.NotInstalled, ShizukuState.of(installed = false, running = true, granted = true))
        assertEquals(ShizukuState.NotRunning, ShizukuState.of(installed = true, running = false, granted = true))
        assertEquals(ShizukuState.NoPermission, ShizukuState.of(installed = true, running = true, granted = false))
        assertEquals(ShizukuState.Ready, ShizukuState.of(installed = true, running = true, granted = true))
    }
}
