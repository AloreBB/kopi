package dev.alorebb.kopi.net

import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Client TLS setup: presents the daemon's PKCS#12 identity and trusts the server only if its
 * leaf certificate's SHA-256 matches the fingerprint pinned at pairing time.
 */
object PinnedTls {
    const val ALIAS = "kopi_identity"

    /** The pinned fingerprint is the security boundary; hostnames are irrelevant. */
    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    /** [keyStore] holds the client key and chain under [alias]. */
    fun sslContext(
        expectedFingerprint: String,
        keyStore: KeyStore,
        alias: String,
        password: CharArray?,
    ): Pair<SSLContext, X509TrustManager> {
        val tm = trustManager(expectedFingerprint)
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(keyManager(keyStore, alias, password)), arrayOf(tm), null)
        return ctx to tm
    }

    fun trustManager(expectedFingerprint: String): X509TrustManager = PinnedTrustManager(expectedFingerprint)

    fun keyManager(keyStore: KeyStore, alias: String, password: CharArray?): X509ExtendedKeyManager =
        SingleAliasKeyManager(keyStore, alias, password)

    private class PinnedTrustManager(private val expected: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty server chain")
            if (Fingerprint.of(leaf) != expected) throw CertificateException("server fingerprint mismatch")
        }

        // Same rule when used server-side (tests); the phone itself only acts as a client.
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            checkServerTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class SingleAliasKeyManager(
        private val keyStore: KeyStore,
        private val alias: String,
        private val password: CharArray?,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias != this.alias) null
            else keyStore.getCertificateChain(alias)?.map { it as X509Certificate }?.toTypedArray()

        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias != this.alias) null else keyStore.getKey(alias, password) as? PrivateKey
    }
}
