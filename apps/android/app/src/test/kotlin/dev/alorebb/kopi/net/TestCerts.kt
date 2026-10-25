package dev.alorebb.kopi.net

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/** Self-signed EC P-256 identity generated for JVM tests (stands in for the Keystore one). */
class TestIdentity(cn: String) {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    val cert: X509Certificate = run {
        val name = X500Name("CN=$cn")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), name, keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private))
        JcaX509CertificateConverter().getCertificate(holder)
    }

    fun keyStore(alias: String, password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(alias, keyPair.private, password, arrayOf(cert))
        }
}
