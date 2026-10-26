package dev.alorebb.kopi.net

import dev.alorebb.kopi.core.Clip
import dev.alorebb.kopi.core.ClipKind
import dev.alorebb.kopi.core.ErrorCode
import dev.alorebb.kopi.core.Hello
import dev.alorebb.kopi.core.KopiError
import dev.alorebb.kopi.core.KopiJson
import dev.alorebb.kopi.core.KopiMessage
import dev.alorebb.kopi.core.Limits
import dev.alorebb.kopi.core.Paired
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

@Timeout(20)
@OptIn(ExperimentalCoroutinesApi::class)
class PeerClientTest {
    private val macCert = HeldCertificate.Builder().commonName("Kopi Mac").ecdsa256().build()
    private val phoneCert = HeldCertificate.Builder().commonName("Kopi Android").ecdsa256().build()
    private val macFp = Fingerprint.of(macCert.certificate)
    private val pw = "test".toCharArray()
    private val imageId = "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b"

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        val serverTls = SSLContext.getInstance("TLSv1.3").apply {
            init(
                arrayOf(HandshakeCertificates.Builder().heldCertificate(macCert).build().keyManager),
                arrayOf(PinnedTls.trustManager(Fingerprint.of(phoneCert.certificate))),
                null,
            )
        }
        server = MockWebServer().apply {
            useHttps(serverTls.socketFactory, false)
            requireClientAuth()
            start()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    // MARK: - Over TLS against MockWebServer

    @Test
    fun connectsAndSendsHello() = runBlocking {
        val mac = serve(macReplies())
        val endpoint = Endpoint(server.hostName, server.port)
        val store = InMemoryPeerStore(macFp, "MacBook Air")
        val client = PeerClient(scope, store, FixedNsd(endpoint), tlsConnector(), phoneName = { PHONE })
        client.start()

        assertJsonEquals(fixture("hello"), mac.nextText())
        withTimeout(5_000) { client.state.first { it == PeerState.Connected("MacBook Air") } }
        assertEquals(endpoint, store.lastEndpoint())
    }

    @Test
    fun exchangesJsonMessages() = runBlocking {
        val mac = serve(macReplies())
        val client = PeerClient(
            scope, InMemoryPeerStore(macFp, "MacBook Air"), FixedNsd(Endpoint(server.hostName, server.port)),
            tlsConnector(),
        )
        val incoming = async(start = CoroutineStart.UNDISPATCHED) { client.incoming.first() }
        client.start()
        mac.nextText()
        withTimeout(5_000) { client.state.first { it is PeerState.Connected } }

        val clip = Clip(imageId, 1761400000000, ClipKind.TEXT, "Hola desde Kopi", false)
        assertTrue(client.send(clip))
        assertJsonEquals(fixture("clip_text"), mac.nextText())

        mac.socket().send(fixture("clip_text"))
        assertEquals(clip, withTimeout(5_000) { incoming.await() })
    }

    @Test
    fun autoPairsWithFirstMacFound() = runBlocking {
        val mac = serve(macReplies(alsoPaired = true))
        val endpoint = Endpoint(server.hostName, server.port)
        val store = InMemoryPeerStore()
        val client = PeerClient(scope, store, FixedNsd(endpoint), tlsConnector(), phoneName = { PHONE })
        client.start()

        assertJsonEquals(fixture("hello"), mac.nextText())
        withTimeout(5_000) { client.state.first { it == PeerState.Connected("MacBook Air") } }
        assertEquals(macFp, store.pairedFingerprint())
        assertEquals("MacBook Air", store.pairedMacName())
        assertEquals(endpoint, store.lastEndpoint())
    }

    @Test
    fun autoPairsWhenMacAlreadyKnowsThisPhone() = runBlocking {
        // After "Desvincular" on the phone only, the Mac still accepts it and answers hello alone.
        serve(macReplies())
        val store = InMemoryPeerStore()
        val client = PeerClient(scope, store, FixedNsd(Endpoint(server.hostName, server.port)), tlsConnector())
        client.start()

        withTimeout(5_000) { client.state.first { it == PeerState.Connected("MacBook Air") } }
        assertEquals(macFp, store.pairedFingerprint())
    }

    @Test
    fun pinnedMacRejectsOtherServer() = runBlocking {
        serve(macReplies())
        val store = InMemoryPeerStore("0".repeat(64), "MacBook Air")
        val client = PeerClient(scope, store, FixedNsd(Endpoint(server.hostName, server.port)), tlsConnector())
        client.start()

        withTimeout(5_000) { client.state.first { it == PeerState.NotFound } }
        assertEquals("0".repeat(64), store.pairedFingerprint())
    }

    @Test
    fun macWithAnotherPhoneIsBusy() = runBlocking {
        // A Mac paired with another phone rejects this phone's certificate during the TLS handshake.
        val busyTls = SSLContext.getInstance("TLSv1.3").apply {
            init(
                arrayOf(HandshakeCertificates.Builder().heldCertificate(macCert).build().keyManager),
                arrayOf(PinnedTls.trustManager("0".repeat(64))),
                null,
            )
        }
        val busy = MockWebServer().apply {
            useHttps(busyTls.socketFactory, false)
            requireClientAuth()
            enqueue(MockResponse().withWebSocketUpgrade(MacScript(macReplies(alsoPaired = true))))
            start()
        }
        try {
            val store = InMemoryPeerStore()
            val client = PeerClient(scope, store, FixedNsd(Endpoint(busy.hostName, busy.port)), tlsConnector())
            client.start()

            withTimeout(5_000) { client.state.first { it == PeerState.MacBusy } }
            assertNull(store.pairedFingerprint())
        } finally {
            busy.shutdown()
        }
    }

    @Test
    fun reassemblesImageChunks() = runBlocking {
        val mac = serve(macReplies())
        val client = PeerClient(
            scope, InMemoryPeerStore(macFp, "MacBook Air"), FixedNsd(Endpoint(server.hostName, server.port)),
            tlsConnector(),
        )
        val chunks = async(start = CoroutineStart.UNDISPATCHED) { client.imageChunks.take(3).toList() }
        client.start()
        withTimeout(5_000) { client.state.first { it is PeerState.Connected } }

        val image = ByteArray(600_000) { (it % 251).toByte() }
        val prefix = imageId.replace("-", "").decodeHex()
        image.toList().chunked(Limits.IMAGE_CHUNK_BYTES).forEach { part ->
            mac.socket().send(okio.Buffer().write(prefix).write(part.toByteArray()).readByteString())
        }

        val received = withTimeout(5_000) { chunks.await() }
        assertEquals(listOf(imageId, imageId, imageId), received.map { it.first })
        assertEquals(listOf(262_144, 262_144, 75_712), received.map { it.second.size })
        assertArrayEquals(image, received.map { it.second }.reduce { a, b -> a + b })
    }

    @Test
    fun sendImageSplitsIntoPrefixedFrames() = runBlocking {
        val mac = serve(macReplies())
        val client = PeerClient(
            scope, InMemoryPeerStore(macFp, "MacBook Air"), FixedNsd(Endpoint(server.hostName, server.port)),
            tlsConnector(),
        )
        client.start()
        mac.nextText()
        withTimeout(5_000) { client.state.first { it is PeerState.Connected } }

        val image = ByteArray(600_000) { (it % 253).toByte() }
        client.sendImage(imageId, image)

        assertJsonEquals("""{"type":"clip_data","id":"$imageId","size":600000}""", mac.nextText())
        val frames = List(3) { mac.binaries.poll(5, TimeUnit.SECONDS) ?: error("missing frame") }
        val prefix = imageId.replace("-", "").decodeHex()
        frames.forEach { assertEquals(prefix, it.substring(0, 16)) }
        assertEquals(listOf(262_144, 262_144, 75_712), frames.map { it.size - 16 })
        assertArrayEquals(image, frames.map { it.substring(16).toByteArray() }.reduce { a, b -> a + b })
    }

    // MARK: - Retry rules, in virtual time

    @Test
    fun notPairedStopsRetrying() = runTest {
        val connector = FakeConnector(listOf(hello(), KopiError(ErrorCode.NOT_PAIRED)))
        val client = PeerClient(backgroundScope, InMemoryPeerStore(macFp, "MacBook Air"), FixedNsd(lan), connector)
        client.start()
        runCurrent()
        assertEquals(PeerState.NotPaired, client.state.value)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, connector.endpoints.size)
        assertEquals(PeerState.NotPaired, client.state.value)
    }

    @Test
    fun versionMismatchStopsRetrying() = runTest {
        val connector = FakeConnector(listOf(KopiError(ErrorCode.VERSION_MISMATCH)))
        val client = PeerClient(backgroundScope, InMemoryPeerStore(macFp, "MacBook Air"), FixedNsd(lan), connector)
        client.start()
        runCurrent()
        assertEquals(PeerState.VersionMismatch, client.state.value)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, connector.endpoints.size)
    }

    @Test
    fun macBusyRetriesEvery30Seconds() = runTest {
        val connector = FakeConnector(null, failure = javax.net.ssl.SSLHandshakeException("bad_certificate"))
        val client = PeerClient(backgroundScope, InMemoryPeerStore(), FixedNsd(lan), connector)
        client.start()
        runCurrent()
        assertEquals(PeerState.MacBusy, client.state.value)
        assertEquals(listOf<String?>(null), connector.fingerprints)

        advanceTimeBy(29_999); runCurrent(); assertEquals(1, connector.endpoints.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, connector.endpoints.size)
        assertEquals(PeerState.MacBusy, client.state.value)
        advanceTimeBy(30_000); runCurrent(); assertEquals(3, connector.endpoints.size)
    }

    @Test
    fun notPairedErrorWhileUnpairedMeansBusy() = runTest {
        val connector = FakeConnector(listOf(KopiError(ErrorCode.NOT_PAIRED)))
        val client = PeerClient(backgroundScope, InMemoryPeerStore(), FixedNsd(lan), connector)
        client.start()
        runCurrent()
        assertEquals(PeerState.MacBusy, client.state.value)
    }

    @Test
    fun unpairedUsesNsdOnly() = runTest {
        val connector = FakeConnector(listOf(hello()))
        val client = PeerClient(backgroundScope, InMemoryPeerStore(last = lan), SilentNsd, connector)
        client.start()
        advanceTimeBy(5_001); runCurrent()
        assertEquals(PeerState.NotFound, client.state.value)
        assertTrue(connector.endpoints.isEmpty())
    }

    @Test
    fun restartRetriesRightAway() = runTest {
        val connector = FakeConnector(null)
        val client = PeerClient(backgroundScope, InMemoryPeerStore(macFp, "MacBook Air"), FixedNsd(lan), connector)
        client.start()
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(3, connector.endpoints.size)

        client.restart()
        runCurrent()
        assertEquals(4, connector.endpoints.size)
    }

    @Test
    fun pinnedConnectionUsesStoredFingerprintAndMacName() = runTest {
        val connector = FakeConnector(listOf(hello()))
        val store = InMemoryPeerStore(macFp, "Old name")
        val client = PeerClient(backgroundScope, store, FixedNsd(lan), connector)
        client.start()
        runCurrent()
        assertEquals(listOf<String?>(macFp), connector.fingerprints)
        assertEquals(PeerState.Connected("MacBook Air"), client.state.value)
        assertEquals("MacBook Air", store.pairedMacName())
    }

    @Test
    fun fallsBackToLastHost() = runTest {
        val last = Endpoint("192.168.1.20", Limits.SERVER_PORT)
        val store = InMemoryPeerStore(macFp, "MacBook Air", last)
        val connector = FakeConnector(listOf(hello()))
        val client = PeerClient(backgroundScope, store, SilentNsd, connector)
        client.start()

        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(connector.endpoints.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(last), connector.endpoints)
        assertEquals(PeerState.Connected("MacBook Air"), client.state.value)
    }

    @Test
    fun retriesWithBackoff() = runTest {
        val connector = FakeConnector(null)
        val client = PeerClient(backgroundScope, InMemoryPeerStore(macFp, "MacBook Air"), FixedNsd(lan), connector)
        client.start()
        runCurrent()
        assertEquals(1, connector.endpoints.size)
        assertEquals(PeerState.NotFound, client.state.value)

        // Retries after 1 s, then 2 s.
        advanceTimeBy(999); runCurrent(); assertEquals(1, connector.endpoints.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, connector.endpoints.size)
        advanceTimeBy(1_999); runCurrent(); assertEquals(2, connector.endpoints.size)
        advanceTimeBy(1); runCurrent(); assertEquals(3, connector.endpoints.size)
    }

    @Test
    fun networkChangeReconnectsImmediately() = runTest {
        val connector = FakeConnector(null)
        val client = PeerClient(backgroundScope, InMemoryPeerStore(macFp, "MacBook Air"), FixedNsd(lan), connector)
        client.start()
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(3, connector.endpoints.size) // now waiting 4 s

        advanceTimeBy(500); runCurrent()
        client.onNetworkChanged()
        runCurrent()
        assertEquals(4, connector.endpoints.size)

        // The backoff was reset: the next retry comes after 1 s again.
        advanceTimeBy(1_000); runCurrent()
        assertEquals(5, connector.endpoints.size)
    }

    // MARK: - Helpers

    private val lan = Endpoint("192.168.1.10", Limits.SERVER_PORT)

    private companion object {
        const val PHONE = "Tecno Camon 40 Pro"
    }

    private fun hello() = Hello(Limits.PROTOCOL_VERSION, Limits.APP_VERSION, "MacBook Air")

    private fun macReplies(alsoPaired: Boolean = false): (KopiMessage) -> List<KopiMessage> = { m ->
        when (m) {
            is Hello -> if (alsoPaired) listOf(hello(), Paired("MacBook Air")) else listOf(hello())
            else -> emptyList()
        }
    }

    private fun serve(replies: (KopiMessage) -> List<KopiMessage>): MacScript {
        val script = MacScript(replies)
        server.enqueue(MockResponse().withWebSocketUpgrade(script))
        return script
    }

    private fun tlsConnector() = OkHttpConnector { fp, onSeen ->
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(PinnedTls.ALIAS, phoneCert.keyPair.private, pw, arrayOf(phoneCert.certificate))
        }
        PinnedTls.sslContext(fp, store, PinnedTls.ALIAS, pw, onSeen)
    }

    private fun fixture(name: String) = File(System.getProperty("kopi.fixtures"), "$name.json").readText()

    private fun assertJsonEquals(expected: String, actual: String?) {
        assertNotNull(actual, "no frame received")
        assertEquals(KopiJson.parseToJsonElement(expected), KopiJson.parseToJsonElement(actual!!))
    }
}

/** Server side of a MockWebServer WebSocket that answers like the Mac. */
private class MacScript(private val replies: (KopiMessage) -> List<KopiMessage>) : WebSocketListener() {
    val texts = LinkedBlockingQueue<String>()
    val binaries = LinkedBlockingQueue<ByteString>()
    private val sockets = LinkedBlockingQueue<WebSocket>()

    fun nextText(): String? = texts.poll(5, TimeUnit.SECONDS)

    fun socket(): WebSocket = sockets.peek() ?: sockets.poll(5, TimeUnit.SECONDS).also { sockets.add(it) }
        ?: error("no websocket opened")

    override fun onOpen(webSocket: WebSocket, response: Response) {
        sockets.add(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        texts.add(text)
        val message = runCatching { KopiJson.decodeFromString(KopiMessage.serializer(), text) }.getOrNull() ?: return
        replies(message).forEach { webSocket.send(KopiJson.encodeToString(KopiMessage.serializer(), it)) }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        binaries.add(bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
    }
}

private class InMemoryPeerStore(
    private var fingerprint: String? = null,
    private var name: String? = null,
    private var last: Endpoint? = null,
) : PeerStore {
    override suspend fun pairedFingerprint() = fingerprint
    override suspend fun pairedMacName() = name
    override suspend fun lastEndpoint() = last
    override suspend fun savePairing(fingerprint: String, macName: String, endpoint: Endpoint) {
        this.fingerprint = fingerprint
        this.name = macName
        this.last = endpoint
    }

    override suspend fun saveLastEndpoint(endpoint: Endpoint) {
        last = endpoint
    }
}

private class FixedNsd(private val endpoint: Endpoint?) : NsdDiscovery {
    override suspend fun discover(): Endpoint? = endpoint
}

private object SilentNsd : NsdDiscovery {
    override suspend fun discover(): Endpoint? = awaitCancellation()
}

/** Scripted connector: [onHello] is what the "Mac" answers to hello; null makes every connection fail with [failure]. */
private class FakeConnector(
    private val onHello: List<KopiMessage>?,
    private val failure: Exception = IOException("connection refused"),
) : WsConnector {
    val endpoints = mutableListOf<Endpoint>()
    val fingerprints = mutableListOf<String?>()

    override fun connect(
        endpoint: Endpoint,
        fingerprint: String?,
        listener: WebSocketListener,
        onServerFingerprint: (String) -> Unit,
    ): WebSocket {
        endpoints += endpoint
        fingerprints += fingerprint
        val socket = FakeSocket(listener, onHello.orEmpty())
        if (onHello == null) listener.onFailure(socket, failure, null)
        return socket
    }

    private class FakeSocket(private val listener: WebSocketListener, private val onHello: List<KopiMessage>) :
        WebSocket {
        override fun request(): Request = Request.Builder().url("https://fake.invalid/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            val message = KopiJson.decodeFromString(KopiMessage.serializer(), text)
            if (message is Hello) {
                onHello.forEach { listener.onMessage(this, KopiJson.encodeToString(KopiMessage.serializer(), it)) }
            }
            return true
        }

        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }
}
