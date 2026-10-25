package dev.alorebb.kopi.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/** The phone's TLS client identity: an EC P-256 key that never leaves the AndroidKeyStore. */
object Identity {
    const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "kopi_identity"
    private const val VALIDITY_YEARS = 10

    fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** Returns the identity certificate, generating the key pair on first use. */
    @Synchronized
    fun ensure(): X509Certificate {
        val ks = keyStore()
        (ks.getCertificate(ALIAS) as? X509Certificate)?.let { return it }

        val start = Date()
        val end = Calendar.getInstance().apply { time = start; add(Calendar.YEAR, VALIDITY_YEARS) }.time
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=Kopi Android"))
            .setCertificateSerialNumber(BigInteger.valueOf(start.time))
            .setCertificateNotBefore(start)
            .setCertificateNotAfter(end)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
        return keyStore().getCertificate(ALIAS) as X509Certificate
    }

    /** Lowercase hex SHA-256 of the certificate's DER encoding. */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
