package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.net.Fingerprint
import dev.alorebb.kopi.net.PinnedTls
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.Date

class LocalIdentity(val keyStore: KeyStore, val certificate: X509Certificate) {
    val fingerprint: String = Fingerprint.of(certificate)
}

/**
 * The daemon's TLS client identity: a software EC P-256 key and a self-signed certificate in a
 * PKCS#12 file (the app's AndroidKeyStore is per uid and the daemon runs as shell).
 */
class DaemonIdentity(private val file: File = DaemonPaths.IDENTITY) {
    companion object {
        const val ALIAS = PinnedTls.ALIAS

        /** Not a secret: the file's 600 mode in a 700 folder is what protects the key. */
        val PASSWORD = "kopi".toCharArray()
        private const val VALIDITY_YEARS = 10
    }

    @Synchronized
    fun loadOrCreate(): LocalIdentity {
        DaemonPaths.ensureOwnerOnlyDir(file.absoluteFile.parentFile!!)
        if (file.isFile) {
            runCatching { load() }.getOrNull()?.let { return it }
        }
        return create()
    }

    private fun load(): LocalIdentity {
        val ks = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD) } }
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        checkNotNull(ks.getKey(ALIAS, PASSWORD))
        return LocalIdentity(ks, cert)
    }

    private fun create(): LocalIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val name = X500Name("CN=Kopi Android")
        val start = Date()
        val end = Calendar.getInstance().apply { time = start; add(Calendar.YEAR, VALIDITY_YEARS) }.time
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
        val holder = JcaX509v3CertificateBuilder(name, serial, start, end, name, pair.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, pair.private, PASSWORD, arrayOf(cert))
        }
        val bytes = ByteArrayOutputStream().also { ks.store(it, PASSWORD) }.toByteArray()
        DaemonPaths.writeOwnerOnly(file, bytes)
        return LocalIdentity(ks, cert)
    }
}
