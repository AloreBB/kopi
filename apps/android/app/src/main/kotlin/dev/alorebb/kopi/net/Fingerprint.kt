package dev.alorebb.kopi.net

import java.security.MessageDigest
import java.security.cert.X509Certificate

object Fingerprint {
    /** Lowercase hex SHA-256 of the certificate's DER encoding. */
    fun of(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
