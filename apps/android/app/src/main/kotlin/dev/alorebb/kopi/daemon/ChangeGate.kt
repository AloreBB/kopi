package dev.alorebb.kopi.daemon

enum class ClipSource { LISTENER, POLL }

/**
 * Decides whether a clipboard observation is a new copy, and which source the daemon relies on.
 * Fingerprints only live in memory and are never logged.
 */
class ChangeGate {
    @Volatile var source: ClipSource = ClipSource.LISTENER
        private set

    private var last: String? = null

    /** False for a transient null clip or one already seen. A poll that sees an unseen change means the listener missed it. */
    @Synchronized
    fun accept(fingerprint: String?, from: ClipSource): Boolean {
        if (fingerprint == null || fingerprint == last) return false
        last = fingerprint
        if (from == ClipSource.POLL) source = ClipSource.POLL
        return true
    }

    /** Listener registration failed: rely on polling. */
    fun usePolling() {
        source = ClipSource.POLL
    }
}
