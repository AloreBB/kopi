package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.R
import dev.alorebb.kopi.ui.StatusModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ShellNotifierTest {
    @TempDir lateinit var dir: File

    private val connected = StatusModel(R.string.status_connected, titleArg = "MacBook Air")
    private val searching = StatusModel(R.string.status_searching)

    private class FakePoster(var fail: Boolean = false) : NotificationPoster {
        val posted = mutableListOf<StatusModel>()
        var cancelled = 0
        override fun post(model: StatusModel) {
            if (fail) throw SecurityException("no")
            posted += model
        }
        override fun cancel() { cancelled++ }
    }

    @Test
    fun sameContentIsPostedOnce() {
        val poster = FakePoster()
        val notifier = ShellNotifier(DaemonLog(File(dir, "kopi.log")), poster)
        notifier.show(connected)
        notifier.show(connected)
        notifier.show(connected.copy(last = "imagen", lastAgo = "1 s"))
        assertEquals(2, poster.posted.size)
    }

    @Test
    fun failureIsLoggedOnceAndNotRetried() {
        val log = File(dir, "kopi.log")
        val poster = FakePoster(fail = true)
        val notifier = ShellNotifier(DaemonLog(log), poster)
        notifier.show(searching)
        poster.fail = false
        notifier.show(connected)
        assertEquals(0, poster.posted.size)
        assertEquals(1, log.readLines().count { it.contains("notify failed reason=SecurityException") })
    }

    @Test
    fun logNeverContainsNotificationText() {
        val log = File(dir, "kopi.log")
        val notifier = ShellNotifier(DaemonLog(log), FakePoster(fail = true))
        notifier.show(connected.copy(last = "Hola secreto", lastAgo = "1 s"))
        assertFalse(log.readText().contains("secreto"))
    }

    @Test
    fun cancelForgetsLastContent() {
        val poster = FakePoster()
        val notifier = ShellNotifier(DaemonLog(File(dir, "kopi.log")), poster)
        notifier.show(searching)
        notifier.cancel()
        notifier.show(searching)
        assertEquals(1, poster.cancelled)
        assertEquals(2, poster.posted.size)
    }
}
