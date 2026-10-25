package dev.alorebb.kopi.net

import dev.alorebb.kopi.core.ClipDataHeader
import dev.alorebb.kopi.core.ErrorCode
import dev.alorebb.kopi.core.Hello
import dev.alorebb.kopi.core.KopiError
import dev.alorebb.kopi.core.KopiJson
import dev.alorebb.kopi.core.KopiMessage
import dev.alorebb.kopi.core.Limits
import dev.alorebb.kopi.core.PairRequest
import dev.alorebb.kopi.core.Paired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class Endpoint(val host: String, val port: Int)

sealed interface PeerState {
    data object Searching : PeerState
    data class Connected(val macName: String) : PeerState
    data object NotFound : PeerState
    data object NotPaired : PeerState
    data object VersionMismatch : PeerState
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

/** Opens a WebSocket to [endpoint] that only trusts a server certificate with [fingerprint]. */
interface WsConnector {
    fun connect(endpoint: Endpoint, fingerprint: String, listener: WebSocketListener): WebSocket
}

/** The Mac rejected pairing with [code]. */
class PairingException(val code: ErrorCode) : Exception("pairing rejected: $code")

/** Real connector: OkHttp over mutual TLS 1.3 with fingerprint pinning and 30 s pings. */
class OkHttpConnector(
    private val tls: (fingerprint: String) -> Pair<SSLContext, X509TrustManager>,
) : WsConnector {
    private val base = OkHttpClient.Builder()
        .pingInterval(Limits.PING_INTERVAL_SECONDS.toLong(), TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
        .build()

    override fun connect(endpoint: Endpoint, fingerprint: String, listener: WebSocketListener): WebSocket {
        val (context, trustManager) = tls(fingerprint)
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
 * handshake and keeps reconnecting with [Backoff]. Never logs clipboard contents.
 */
class PeerClient(
    private val scope: CoroutineScope,
    private val store: PeerStore,
    private val nsd: NsdDiscovery,
    private val connector: WsConnector,
    private val nsdTimeoutMs: Long = 5_000,
    private val handshakeTimeoutMs: Long = 15_000,
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
                if (loopJob?.isActive != true) loopJob = scope.launch { run(null) }
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

    /** Pairs with the Mac in [uri]; on success the connection stays up and the Mac's name is returned. */
    suspend fun pair(uri: PairingUri, phoneName: String): Result<String> {
        val result = CompletableDeferred<Result<String>>()
        val attempt = PairingAttempt(uri, phoneName, result)
        loopLock.withLock {
            loopJob?.cancelAndJoin()
            val job = scope.launch { run(attempt) }
            job.invokeOnCompletion { result.complete(Result.failure(CancellationException("pairing stopped"))) }
            loopJob = job
        }
        return result.await()
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

    private class PairingAttempt(val uri: PairingUri, val phoneName: String, val result: CompletableDeferred<Result<String>>)

    private enum class End { DROPPED, FAILED, NOT_PAIRED, VERSION_MISMATCH }

    private suspend fun run(initialPairing: PairingAttempt?) {
        var pairing = initialPairing
        while (true) {
            val endpoint: Endpoint
            val fingerprint: String
            if (pairing != null) {
                endpoint = Endpoint(pairing.uri.host, pairing.uri.port)
                fingerprint = pairing.uri.fingerprint
            } else {
                fingerprint = store.pairedFingerprint() ?: run {
                    _state.value = PeerState.NotPaired
                    return
                }
                if (_state.value !is PeerState.NotFound) _state.value = PeerState.Searching
                val found = withTimeoutOrNull(nsdTimeoutMs) { nsd.discover() } ?: store.lastEndpoint()
                if (found == null) {
                    log("mac not found")
                    _state.value = PeerState.NotFound
                    waitToRetry()
                    continue
                }
                endpoint = found
            }

            val end = session(endpoint, fingerprint, pairing)
            pairing = null
            when (end) {
                End.NOT_PAIRED -> {
                    _state.value = PeerState.NotPaired
                    return
                }
                End.VERSION_MISMATCH -> {
                    _state.value = PeerState.VersionMismatch
                    return
                }
                End.DROPPED -> _state.value = PeerState.Searching
                End.FAILED -> _state.value = PeerState.NotFound
            }
            waitToRetry()
        }
    }

    private suspend fun waitToRetry() {
        val delayMs = backoff.next()
        withTimeoutOrNull(delayMs) { wake.receive() }
    }

    private sealed interface Event {
        data class Text(val text: String) : Event
        data class Binary(val bytes: ByteString) : Event
        data object Closed : Event
    }

    private sealed interface Handshake {
        data object Ready : Handshake
        data class PairedWith(val name: String) : Handshake
        data class Rejected(val code: ErrorCode) : Handshake
        data object Failed : Handshake
    }

    /** One connection, from opening to close. Returns why it ended. */
    private suspend fun session(endpoint: Endpoint, fingerprint: String, pairing: PairingAttempt?): End {
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
                events.trySend(Event.Closed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(Event.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("connection failed: ${t.javaClass.simpleName}")
                events.trySend(Event.Closed)
            }
        }

        val socket = try {
            connector.connect(endpoint, fingerprint, listener)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("connect failed: ${e.javaClass.simpleName}")
            pairing?.result?.complete(Result.failure(e))
            return End.FAILED
        }

        try {
            socket.send(encode(Hello(Limits.PROTOCOL_VERSION, Limits.APP_VERSION)))
            if (pairing != null) socket.send(encode(PairRequest(pairing.uri.code, pairing.phoneName)))

            val handshake = withTimeoutOrNull(handshakeTimeoutMs) { handshake(events, pairing != null) }
                ?: Handshake.Failed
            val macName: String
            when (handshake) {
                Handshake.Failed -> {
                    pairing?.result?.complete(Result.failure(java.io.IOException("handshake failed")))
                    return End.FAILED
                }
                is Handshake.Rejected -> {
                    log("handshake rejected code=${handshake.code}")
                    pairing?.result?.complete(Result.failure(PairingException(handshake.code)))
                    return when (handshake.code) {
                        ErrorCode.VERSION_MISMATCH -> End.VERSION_MISMATCH
                        // While pairing, fall back to the stored pairing (if any).
                        ErrorCode.NOT_PAIRED -> if (pairing != null) End.FAILED else End.NOT_PAIRED
                        ErrorCode.INVALID_CODE -> End.FAILED
                    }
                }
                is Handshake.PairedWith -> {
                    store.savePairing(fingerprint, handshake.name, endpoint)
                    macName = handshake.name
                    log("paired")
                    pairing?.result?.complete(Result.success(handshake.name))
                }
                Handshake.Ready -> {
                    store.saveLastEndpoint(endpoint)
                    macName = store.pairedMacName() ?: endpoint.host
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

    private suspend fun handshake(events: Channel<Event>, pairing: Boolean): Handshake {
        var gotHello = false
        for (event in events) {
            when (event) {
                is Event.Text -> when (val m = decode(event.text)) {
                    is Hello -> {
                        if (m.protocolVersion != Limits.PROTOCOL_VERSION) return Handshake.Rejected(ErrorCode.VERSION_MISMATCH)
                        if (!pairing) return Handshake.Ready
                        gotHello = true
                    }
                    is Paired -> if (gotHello) return Handshake.PairedWith(m.name)
                    is KopiError -> return Handshake.Rejected(m.code)
                    else -> Unit
                }
                is Event.Binary -> Unit
                Event.Closed -> return Handshake.Failed
            }
        }
        return Handshake.Failed
    }

    private suspend fun receive(events: Channel<Event>): End {
        for (event in events) {
            when (event) {
                is Event.Text -> when (val m = decode(event.text)) {
                    null, is Hello -> Unit
                    is KopiError -> when (m.code) {
                        ErrorCode.NOT_PAIRED -> return End.NOT_PAIRED
                        ErrorCode.VERSION_MISMATCH -> return End.VERSION_MISMATCH
                        ErrorCode.INVALID_CODE -> Unit
                    }
                    else -> _incoming.emit(m)
                }
                is Event.Binary -> {
                    val chunk = parseFrame(event.bytes)
                    if (chunk == null) log("ignored malformed binary frame size=${event.bytes.size}")
                    else _imageChunks.emit(chunk)
                }
                Event.Closed -> {
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
