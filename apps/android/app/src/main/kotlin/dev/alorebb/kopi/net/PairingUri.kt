package dev.alorebb.kopi.net

import dev.alorebb.kopi.core.Limits

/**
 * Pairing payload scanned from the Mac's QR code:
 * `kopi://pair?v=1&host=<ipv4>&port=<p>&fp=<hex64>&code=<base64url, 22 chars>`.
 */
data class PairingUri(val host: String, val port: Int, val fingerprint: String, val code: String) {
    companion object {
        private const val PREFIX = "kopi://pair?"
        private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
        private val CODE = Regex("^[A-Za-z0-9_-]{22}$")
        private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

        /** Returns null unless every field is present and valid. */
        fun parse(raw: String): PairingUri? {
            if (!raw.startsWith(PREFIX)) return null
            val params = raw.removePrefix(PREFIX).split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }
            // Ambiguous URIs with repeated keys are rejected.
            if (params.map { it.first }.toSet().size != params.size) return null
            val query = params.toMap()

            if (query["v"] != Limits.PROTOCOL_VERSION.toString()) return null
            val host = query["host"]?.takeIf(::isIpv4) ?: return null
            val port = query["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fp = query["fp"]?.takeIf { FINGERPRINT.matches(it) } ?: return null
            val code = query["code"]?.takeIf { CODE.matches(it) } ?: return null
            return PairingUri(host, port, fp, code)
        }

        private fun isIpv4(host: String): Boolean {
            val match = IPV4.matchEntire(host) ?: return false
            return match.groupValues.drop(1).all { it.toInt() <= 255 }
        }
    }
}
