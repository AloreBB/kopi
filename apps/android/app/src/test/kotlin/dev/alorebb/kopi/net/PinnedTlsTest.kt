package dev.alorebb.kopi.net

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.security.cert.CertificateException
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class PinnedTlsTest {
    private val mac = TestIdentity("Kopi Mac")
    private val other = TestIdentity("Impostor")
    private val phone = TestIdentity("Kopi Android")
    private val macFp = Fingerprint.of(mac.cert)
    private val pw = "test".toCharArray()

    @Test
    fun fingerprintIsLowercaseHexSha256OfDer() {
        val expected = MessageDigest.getInstance("SHA-256").digest(mac.cert.encoded)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, macFp)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(macFp))
    }

    @Test
    fun trustManagerAcceptsPinned() {
        val tm = PinnedTls.trustManager(macFp)
        tm.checkServerTrusted(arrayOf(mac.cert), "ECDHE_ECDSA")
    }

    @Test
    fun trustManagerRejectsOther() {
        val tm = PinnedTls.trustManager(macFp)
        assertThrows<CertificateException> { tm.checkServerTrusted(arrayOf(other.cert), "ECDHE_ECDSA") }
        assertThrows<CertificateException> { tm.checkServerTrusted(arrayOf(), "ECDHE_ECDSA") }
    }

    @Test
    fun trustOnFirstUseAcceptsAnyServer() {
        val tm = PinnedTls.trustManager(null)
        tm.checkServerTrusted(arrayOf(mac.cert), "ECDHE_ECDSA")
        tm.checkServerTrusted(arrayOf(other.cert), "ECDHE_ECDSA")
        assertThrows<CertificateException> { tm.checkServerTrusted(arrayOf(), "ECDHE_ECDSA") }
    }

    @Test
    fun keyManagerServesIdentityAlias() {
        val km = PinnedTls.keyManager(phone.keyStore(PinnedTls.ALIAS, pw), PinnedTls.ALIAS, pw)
        assertEquals(PinnedTls.ALIAS, km.chooseClientAlias(arrayOf("EC"), null, null))
        assertEquals(PinnedTls.ALIAS, km.chooseEngineClientAlias(arrayOf("EC"), null, null))
        assertArrayEquals(arrayOf(phone.cert), km.getCertificateChain(PinnedTls.ALIAS))
        assertEquals(phone.keyPair.private, km.getPrivateKey(PinnedTls.ALIAS))
    }

    @Test
    fun handshakeSucceedsOnlyWithPinnedServer() {
        val phoneStore = phone.keyStore(PinnedTls.ALIAS, pw)

        val serverErrors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        fun serve(identity: TestIdentity): Pair<SSLServerSocket, Thread> {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(identity.keyStore("server", pw), pw)
            val ctx = SSLContext.getInstance("TLSv1.3")
            ctx.init(kmf.keyManagers, arrayOf(PinnedTls.trustManager(Fingerprint.of(phone.cert))), null)
            val server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            server.needClientAuth = true
            val t = thread {
                runCatching<Unit> {
                    (server.accept() as SSLSocket).use { s ->
                        s.startHandshake()
                        s.outputStream.write(42)
                        s.outputStream.flush()
                        // Wait for the client's ack so closing can't race its read (TLS 1.3 on Linux).
                        s.inputStream.read()
                    }
                }.onFailure { serverErrors += it }
            }
            return server to t
        }

        val (good, goodThread) = serve(mac)
        good.use {
            val (ctx, _) = PinnedTls.sslContext(macFp, phoneStore, PinnedTls.ALIAS, pw)
            (ctx.socketFactory.createSocket("127.0.0.1", good.localPort) as SSLSocket).use { s ->
                s.startHandshake()
                val first = runCatching { s.inputStream.read() }
                goodThread.join(2_000)
                assertEquals(42, first.getOrElse { e ->
                    throw AssertionError("client read failed: $e; server errors: ${serverErrors.joinToString { it.toString() }}", e)
                })
                s.outputStream.write(1)
                s.outputStream.flush()
            }
            goodThread.join(5_000)
        }

        val (bad, badThread) = serve(other)
        bad.use {
            val (ctx, _) = PinnedTls.sslContext(macFp, phoneStore, PinnedTls.ALIAS, pw)
            (ctx.socketFactory.createSocket("127.0.0.1", bad.localPort) as SSLSocket).use { s ->
                assertThrows<Exception> { s.startHandshake() }
            }
            badThread.join(5_000)
        }
    }
}
