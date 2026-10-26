package dev.alorebb.kopi.net

import dev.alorebb.kopi.core.ClipDataHeader
import dev.alorebb.kopi.core.ErrorCode
import dev.alorebb.kopi.core.Hello
import dev.alorebb.kopi.core.KopiError
import dev.alorebb.kopi.core.KopiJson
import dev.alorebb.kopi.core.KopiMessage
import dev.alorebb.kopi.core.Limits
import dev.alorebb.kopi.core.Paired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class Endpoint(val host: String, val port: Int)

sealed interface PeerState {
    data object Searching : PeerState
    data class Connected(val macName: String) : PeerState
    data object NotFound : PeerState
    data object NotPaired : PeerState
    data object VersionMismatch : PeerState

    /** No Mac stored, and the Mac found already has another phone (it rejected this one's certificate). */
    data object MacBusy : PeerState
}

/** The pairing state PeerClient reads and writes. Implemented by [dev.alorebb.kopi.daemon.DaemonState]. */
interface PeerStore {
    suspend fun pairedFingerprint(): String?
    suspend fun pairedMacName(): String?
    suspend fun lastEndpoint(): Endpoint?
    suspend fun savePairing(fingerprint: String, macName: String, endpoint: Endpoint)
    suspend fun saveLastEndpoint(endpoint: Endpoint)
}

/** Finds the Mac's `_kopi._tcp` service. Suspends until found; the caller applies the timeout. */
interface NsdDiscovery {
    suspend fun discover(): Endpoint?
}

/**
 * Opens a WebSocket to [endpoint] that only trusts a server certificate with [fingerprint], or any
 * certificate when [fingerprint] is null (trust on first use). [onServerFingerprint] gets the
 * fingerprint of the certificate the server presented, during the TLS handshake.
 */
interface WsConnector {
    fun connect(
        endpoint: Endpoint,
        fingerprint: String?,
        listener: WebSocketListener,
        onServerFingerprint: (String) -> Unit = {},
    ): WebSocket
}

/** Real connector: OkHttp over mutual TLS 1.3 with fingerprint pinning and 30 s pings. */
class OkHttpConnector(
    private val tls: (fingerprint: String?, onServerFingerprint: (String) -> Unit) -> Pair<SSLContext, X509TrustManager>,
) : WsConnector {
    private val base = OkHttpClient.Builder()
        .pingInterval(Limits.PING_INTERVAL_SECONDS.toLong(), TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
        .build()

    override fun connect(
        endpoint: Endpoint,
        fingerprint: String?,
        listener: WebSocketListener,
        onServerFingerprint: (String) -> Unit,
    ): WebSocket {
        val (context, trustManager) = tls(fingerprint, onServerFingerprint)
        val client = base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(PinnedTls.hostnameVerifier)
            .build()
        val url = HttpUrl.Builder().scheme("https").host(endpoint.host).port(endpoint.port).build()
        return client.newWebSocket(Request.Builder().url(url).build(), listener)
    }
}

/**
 * The phone side of the protocol: finds the Mac (NSD, falling back to the last known
 * address after [nsdTimeoutMs]), connects with the pinned fingerprint, runs the
 * handshake and keeps reconnecting with [Backoff]. With no Mac stored it pairs on its own:
 * the first `_kopi._tcp` Mac found that accepts this phone is trusted and remembered.
 * Never logs clipboard contents.
 */
class PeerClient(
    private val scope: CoroutineScope,
    private val store: PeerStore,
    private val nsd: NsdDiscovery,
    private val connector: WsConnector,
    private val nsdTimeoutMs: Long = 5_000,
    private val handshakeTimeoutMs: Long = 15_000,
    private val phoneName: () -> String = { "Android" },
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<PeerState>(PeerState.Searching)
    val state: StateFlow<PeerState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<KopiMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<KopiMessage> = _incoming.asSharedFlow()

    private val _imageChunks = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val imageChunks: SharedFlow<Pair<String, ByteArray>> = _imageChunks.asSharedFlow()

    private val backoff = Backoff()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val loopLock = Mutex()
    private val imageLock = Mutex()

    @Volatile private var loopJob: Job? = null
    @Volatile private var active: WebSocket? = null

    fun start() {
        scope.launch {
            loopLock.withLock {
                if (loopJob?.isActive != true) loopJob = scope.launch { run() }
            }
        }
    }

    /** Drops the connection and starts over right away, e.g. after the stored Mac was forgotten. */
    fun restart() {
        scope.launch {
            loopLock.withLock {
                loopJob?.cancelAndJoin()
                backoff.reset()
                _state.value = PeerState.Searching
                loopJob = scope.launch { run() }
            }
        }
    }

    fun stop() {
        scope.launch {
            loopLock.withLock {
                loopJob?.cancelAndJoin()
                loopJob = null
                val s = _state.value
                if (s is PeerState.Connected || s is PeerState.NotFound) _state.value = PeerState.Searching
            }
        }
    }

    /** Resets the backoff and retries right away if waiting to reconnect. */
    fun onNetworkChanged() {
        backoff.reset()
        wake.trySend(Unit)
    }

    fun send(m: KopiMessage): Boolean {
        val socket = active ?: return false
        return socket.send(KopiJson.encodeToString(KopiMessage.serializer(), m))
    }

    /** Sends `clip_data` and then `[16-byte id][chunk]` binary frames, respecting OkHttp's queue limit. */
    fun sendImage(id: String, bytes: ByteArray) {
        val socket = active ?: return
        val prefix = runCatching { uuidBytes(id) }.getOrNull() ?: return
        scope.launch {
            imageLock.withLock {
                if (!socket.send(KopiJson.encodeToString(KopiMessage.serializer(), ClipDataHeader(id, bytes.size)))) return@launch
                var offset = 0
                while (offset < bytes.size) {
                    while (socket.queueSize() > MAX_QUEUED_BYTES) {
                        if (active !== socket) return@launch
                        delay(QUEUE_POLL_MS)
                    }
                    val end = minOf(offset + Limits.IMAGE_CHUNK_BYTES, bytes.size)
                    val frame = ByteArray(UUID_BYTES + end - offset)
                    prefix.copyInto(frame)
                    bytes.copyInto(frame, UUID_BYTES, offset, end)
                    if (!socket.send(frame.toByteString())) return@launch
                    offset = end
                }
                log("sent image id=$id size=${bytes.size}")
            }
        }
    }

    // MARK: - Connection loop

    private enum class End { DROPPED, FAILED, NOT_PAIRED, VERSION_MISMATCH, MAC_BUSY }

    private suspend fun run() {
        while (true) {
            // null: no Mac stored yet, so pair with the first one found (trust on first use).
            val fingerprint = store.pairedFingerprint()
            val s = _state.value
            if (s !is PeerState.NotFound && s !is PeerState.MacBusy) _state.value = PeerState.Searching
            val endpoint = withTimeoutOrNull(nsdTimeoutMs) { nsd.discover() }
                ?: if (fingerprint != null) store.lastEndpoint() else null
            if (endpoint == null) {
                log("mac not found")
                _state.value = PeerState.NotFound
                waitToRetry()
                continue
            }

            when (session(endpoint, fingerprint)) {
                End.NOT_PAIRED -> {
                    _state.value = PeerState.NotPaired
                    return
                }
                End.VERSION_MISMATCH -> {
                    _state.value = PeerState.VersionMismatch
                    return
                }
                End.MAC_BUSY -> {
                    _state.value = PeerState.MacBusy
                    waitToRetry(MAC_BUSY_RETRY_MS)
                    continue
                }
                End.DROPPED -> _state.value = PeerState.Searching
                End.FAILED -> _state.value = PeerState.NotFound
            }
            waitToRetry()
        }
    }

    private suspend fun waitToRetry(delayMs: Long = backoff.next()) {
        withTimeoutOrNull(delayMs) { wake.receive() }
    }

    private sealed interface Event {
        /** The fingerprint of the certificate the server presented (sent during the TLS handshake). */
        data class ServerCertificate(val fingerprint: String) : Event
        data class Text(val text: String) : Event
        data class Binary(val bytes: ByteString) : Event
        data class Closed(val tlsRejected: Boolean = false) : Event
    }

    private sealed interface Handshake {
        data class Ready(val macName: String, val serverFingerprint: String?) : Handshake
        data class Rejected(val code: ErrorCode) : Handshake
        data class Failed(val tlsRejected: Boolean) : Handshake
    }

    /** One connection, from opening to close. [fingerprint] null = trust on first use. Returns why it ended. */
    private suspend fun session(endpoint: Endpoint, fingerprint: String?): End {
        val tofu = fingerprint == null
        val events = Channel<Event>(Channel.UNLIMITED)
        val listener = object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(Event.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.trySend(Event.Binary(bytes))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
                events.trySend(Event.Closed())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(Event.Closed())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("connection failed: ${t.javaClass.simpleName}")
                // The Mac refusing this phone's certificate surfaces as a TLS alert.
                events.trySend(Event.Closed(tlsRejected = response == null && isTlsFailure(t)))
            }
        }

        val socket = try {
            connector.connect(endpoint, fingerprint, listener) { events.trySend(Event.ServerCertificate(it)) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("connect failed: ${e.javaClass.simpleName}")
            return End.FAILED
        }

        try {
            socket.send(encode(Hello(Limits.PROTOCOL_VERSION, Limits.APP_VERSION, phoneName())))

            val handshake = withTimeoutOrNull(handshakeTimeoutMs) { handshake(events) } ?: Handshake.Failed(false)
            val macName: String
            when (handshake) {
                is Handshake.Failed -> return if (tofu && handshake.tlsRejected) End.MAC_BUSY else End.FAILED
                is Handshake.Rejected -> {
                    log("handshake rejected code=${handshake.code}")
                    return when (handshake.code) {
                        ErrorCode.VERSION_MISMATCH -> End.VERSION_MISMATCH
                        ErrorCode.NOT_PAIRED -> if (tofu) End.MAC_BUSY else End.NOT_PAIRED
                    }
                }
                is Handshake.Ready -> {
                    macName = handshake.macName.ifBlank { store.pairedMacName() ?: endpoint.host }
                    if (tofu) {
                        // The Mac accepted this phone, so it is ours: remember its certificate.
                        val seen = handshake.serverFingerprint ?: run {
                            log("pairing failed reason=no_server_certificate")
                            return End.FAILED
                        }
                        store.savePairing(seen, macName, endpoint)
                        log("paired")
                    } else {
                        store.savePairing(fingerprint!!, macName, endpoint)
                    }
                }
            }

            active = socket
            backoff.reset()
            _state.value = PeerState.Connected(macName)
            log("connected")
            return receive(events)
        } finally {
            if (active === socket) active = null
            socket.close(NORMAL_CLOSURE, null)
        }
    }

    /** Waits for the Mac's hello. A `paired` that follows it arrives in [receive] and is ignored. */
    private suspend fun handshake(events: Channel<Event>): Handshake {
        var serverFingerprint: String? = null
        for (event in events) {
            when (event) {
                is Event.ServerCertificate -> serverFingerprint = event.fingerprint
                is Event.Text -> when (val m = decode(event.text)) {
                    is Hello -> {
                        if (m.protocolVersion != Limits.PROTOCOL_VERSION) return Handshake.Rejected(ErrorCode.VERSION_MISMATCH)
                        return Handshake.Ready(m.name, serverFingerprint)
                    }
                    is KopiError -> return Handshake.Rejected(m.code)
                    else -> Unit
                }
                is Event.Binary -> Unit
                is Event.Closed -> return Handshake.Failed(event.tlsRejected)
            }
        }
        return Handshake.Failed(false)
    }

    private suspend fun receive(events: Channel<Event>): End {
        for (event in events) {
            when (event) {
                is Event.Text -> when (val m = decode(event.text)) {
                    null, is Hello, is Paired -> Unit
                    is KopiError -> when (m.code) {
                        ErrorCode.NOT_PAIRED -> return End.NOT_PAIRED
                        ErrorCode.VERSION_MISMATCH -> return End.VERSION_MISMATCH
                    }
                    else -> _incoming.emit(m)
                }
                is Event.Binary -> {
                    val chunk = parseFrame(event.bytes)
                    if (chunk == null) log("ignored malformed binary frame size=${event.bytes.size}")
                    else _imageChunks.emit(chunk)
                }
                is Event.ServerCertificate -> Unit
                is Event.Closed -> {
                    log("disconnected")
                    return End.DROPPED
                }
            }
        }
        return End.DROPPED
    }

    private fun encode(m: KopiMessage) = KopiJson.encodeToString(KopiMessage.serializer(), m)

    /** Unknown or malformed messages are ignored (forward compatibility). */
    private fun decode(text: String): KopiMessage? =
        runCatching { KopiJson.decodeFromString(KopiMessage.serializer(), text) }
            .onFailure { log("ignored undecodable text frame size=${text.length}") }
            .getOrNull()

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val UUID_BYTES = 16
        private const val MAX_QUEUED_BYTES = 4L * 1024 * 1024
        private const val QUEUE_POLL_MS = 20L
        private const val MAC_BUSY_RETRY_MS = 30_000L

        /**
         * Whether [t] comes from a failed TLS handshake. OkHttp reports the last route's error and
         * keeps earlier ones (e.g. the TLS alert on IPv4 before IPv6 was refused) as suppressed.
         */
        fun isTlsFailure(t: Throwable): Boolean =
            generateSequence(t) { it.cause?.takeIf { c -> c !== it } }
                .any { e -> e is SSLException || e.suppressed.any { it is SSLException } }

        /** The UUID's 16 bytes, big-endian, as on the wire. */
        fun uuidBytes(id: String): ByteArray {
            val uuid = UUID.fromString(id)
            return ByteBuffer.allocate(UUID_BYTES).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        }

        /** Splits `[16-byte id][chunk ≤ IMAGE_CHUNK_BYTES]` into (lowercase id, chunk). */
        fun parseFrame(frame: ByteString): Pair<String, ByteArray>? {
            if (frame.size < UUID_BYTES || frame.size - UUID_BYTES > Limits.IMAGE_CHUNK_BYTES) return null
            val buffer = ByteBuffer.wrap(frame.substring(0, UUID_BYTES).toByteArray())
            val id = UUID(buffer.long, buffer.long).toString()
            return id to frame.substring(UUID_BYTES).toByteArray()
        }
    }
}
