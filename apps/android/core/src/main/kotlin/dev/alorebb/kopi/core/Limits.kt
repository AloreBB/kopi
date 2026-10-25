package dev.alorebb.kopi.core

// Shared protocol constants (see Global Constraints in the v1 plan).
object Limits {
    const val PROTOCOL_VERSION = 1
    const val APP_VERSION = "1.0.0"
    const val BUNDLE_ID = "dev.alorebb.kopi"
    const val BONJOUR_SERVICE_TYPE = "_kopi._tcp"
    const val SERVER_PORT = 47474
    const val OFFER_EXPIRY_MS = 120_000L

    // Copies crossing in flight within this local-time window are resolved by sender ts.
    const val CONFLICT_WINDOW_MS = 2_000L
    const val PAIRING_CODE_EXPIRY_MS = 300_000L
    const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    val ALLOWED_IMAGE_MIMES = setOf("image/png", "image/jpeg")
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    const val IMAGE_CHUNK_BYTES = 256 * 1024
    const val PASTE_WAIT_SECONDS = 5
    const val PING_INTERVAL_SECONDS = 30
    const val PREVIEW_MAX_CHARACTERS = 40
}
