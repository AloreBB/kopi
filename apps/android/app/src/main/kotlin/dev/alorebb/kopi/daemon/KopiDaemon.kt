package dev.alorebb.kopi.daemon

import android.net.ConnectivityManager
import android.net.Network
import android.os.Process
import dev.alorebb.kopi.core.Clip
import dev.alorebb.kopi.core.ClipDataHeader
import dev.alorebb.kopi.core.ClipOffer
import dev.alorebb.kopi.core.ClipRequest
import dev.alorebb.kopi.core.ClipUnavailable
import dev.alorebb.kopi.core.EngineAction
import dev.alorebb.kopi.core.ImageServe
import dev.alorebb.kopi.core.ImageStrategy
import dev.alorebb.kopi.core.KopiMessage
import dev.alorebb.kopi.core.LocalContent
import dev.alorebb.kopi.core.SyncEngine
import dev.alorebb.kopi.net.AndroidNsdDiscovery
import dev.alorebb.kopi.net.OkHttpConnector
import dev.alorebb.kopi.net.PairingUri
import dev.alorebb.kopi.net.PeerClient
import dev.alorebb.kopi.net.PeerState
import dev.alorebb.kopi.net.PinnedTls
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * The sync process (shell uid): [ShellClipboard] ↔ [SyncEngine] (EAGER) ↔ [PeerClient], as the Mac's
 * AppCoordinator. All engine work runs on one thread. Shizuku-style no-arg constructor.
 */
class KopiDaemon : IKopiDaemon.Stub() {
    private val log = DaemonLog()
    private val engineThread = Executors.newSingleThreadExecutor { Thread(it, "kopi-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(
        SupervisorJob() + engineThread + CoroutineExceptionHandler { _, e -> log.event("error reason=${e.javaClass.simpleName}") },
    )
    private val state = DaemonState()
    private val engine = SyncEngine(ImageStrategy.EAGER, System::currentTimeMillis)
    private val identity: LocalIdentity
    private val clipboard: ShellClipboard?
    private val peer: PeerClient

    private class Download(val mime: String, val size: Int) {
        val bytes = ByteArrayOutputStream(size)
    }

    // Engine-thread state.
    private var lastLocalImage: Pair<String, ByteArray>? = null
    private val downloads = mutableMapOf<String, Download>()
    private var wasConnected = false

    init {
        DaemonPaths.ensureOwnerOnlyDir(DaemonPaths.DIR)
        identity = DaemonIdentity().loadOrCreate()
        log.event("daemon started uid=${Process.myUid()}")
        val context = SystemContext.get()
        clipboard = runCatching { ShellClipboard(context, scope, log).also { it.start() } }
            .onFailure { log.event("clipboard unavailable reason=${it.javaClass.simpleName}") }
            .getOrNull()
        peer = PeerClient(
            scope = scope,
            store = state,
            nsd = AndroidNsdDiscovery(context),
            connector = OkHttpConnector { fp -> PinnedTls.sslContext(fp, identity.keyStore, DaemonIdentity.ALIAS, DaemonIdentity.PASSWORD) },
            log = { log.event(it) },
        )
        wire()
        watchNetwork(context)
        if (state.read().macFingerprint != null) peer.start()
    }

    private fun wire() {
        clipboard?.let { cb -> scope.launch { cb.copies.collect(::onLocalCopy) } }
        scope.launch { peer.incoming.collect(::onRemote) }
        scope.launch { peer.imageChunks.collect { (id, chunk) -> onChunk(id, chunk) } }
        scope.launch {
            peer.state.collect { s ->
                log.event("state=${snapshot(s).state}")
                val connected = s is PeerState.Connected
                if (connected && !wasConnected) engine.onReconnect()?.let(::send)
                wasConnected = connected
            }
        }
    }

    private fun onLocalCopy(clip: CapturedClip) {
        val message = engine.onLocalCopy(clip.content) ?: return
        if (message is ClipOffer) lastLocalImage = message.id to clip.imageBytes!!
        send(message)
    }

    private fun send(message: KopiMessage) {
        when (message) {
            is Clip -> log.event("copy", message.id, "text", message.text.encodeToByteArray().size)
            is ClipOffer -> log.event("copy", message.id, "image", message.size)
            else -> Unit
        }
        if (!peer.send(message)) log.event("send skipped reason=offline", (message as? Clip)?.id ?: (message as? ClipOffer)?.id)
    }

    private fun onRemote(message: KopiMessage) {
        when (message) {
            is ClipRequest -> serveImage(message.id)
            is ClipDataHeader -> Unit
            is ClipUnavailable -> {
                downloads.remove(message.id)
                log.event("image unavailable reason=${message.reason.name.lowercase()}", message.id)
            }
            else -> for (action in engine.onRemote(message)) apply(action, message)
        }
    }

    private fun apply(action: EngineAction, message: KopiMessage) {
        when (action) {
            is EngineAction.WriteText -> {
                val sensitive = (message as? Clip)?.sensitive == true
                val cb = clipboard ?: return
                cb.writeText(action.text, sensitive)
                engine.didApplyRemote(LocalContent.Text(action.text, sensitive))
                log.event("applied", action.id, "text", action.text.encodeToByteArray().size)
            }
            is EngineAction.RequestImage -> {
                val offer = message as? ClipOffer ?: return
                downloads.clear()
                downloads[offer.id] = Download(offer.mime, offer.size)
                peer.send(ClipRequest(offer.id))
            }
            is EngineAction.RegisterImagePromise, is EngineAction.Send -> Unit
        }
    }

    private fun onChunk(id: String, chunk: ByteArray) {
        val download = downloads[id] ?: return
        download.bytes.write(chunk)
        val received = download.bytes.size()
        if (received < download.size) return
        downloads.remove(id)
        if (received != download.size) {
            log.event("image dropped reason=size_mismatch", id, "image", received)
            return
        }
        val bytes = download.bytes.toByteArray()
        try {
            clipboard?.writeImage(id, bytes, download.mime) ?: return
        } catch (e: Throwable) {
            log.event("image write failed reason=${e.javaClass.simpleName}", id, "image", bytes.size)
            return
        }
        engine.didApplyRemote(LocalContent.Image(download.mime, bytes.size, MessageDigest.getInstance("SHA-256").digest(bytes)))
        log.event("applied", id, "image", bytes.size)
    }

    private fun serveImage(id: String) {
        when (val serve = engine.onImageRequest(id)) {
            is ImageServe.Serve -> {
                val image = lastLocalImage?.takeIf { it.first == id }
                if (image == null) peer.send(ClipUnavailable(id, dev.alorebb.kopi.core.UnavailableReason.REPLACED))
                else peer.sendImage(id, image.second)
            }
            is ImageServe.Unavailable -> peer.send(ClipUnavailable(id, serve.reason))
        }
    }

    private fun watchNetwork(context: android.content.Context) {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = peer.onNetworkChanged()
            })
        }.onFailure { log.event("network watch unavailable reason=${it.javaClass.simpleName}") }
    }

    private fun snapshot(peerState: PeerState = peer.state.value): DaemonStatus {
        val saved = state.read()
        return DaemonStatus.of(peerState, saved.macFingerprint != null, saved.macName, engine.lastItem, clipboard != null)
    }

    // MARK: - IKopiDaemon (binder threads)

    override fun status(): String = runBlocking { withContext(engineThread) { snapshot() } }.toJson()

    override fun pair(pairingUri: String, phoneName: String): Boolean {
        val uri = PairingUri.parse(pairingUri) ?: return false
        val result = runBlocking { withTimeoutOrNull(PAIR_TIMEOUT_MS) { peer.pair(uri, phoneName) } }
        val name = result?.getOrNull() ?: run {
            log.event("pair failed reason=${result?.exceptionOrNull()?.javaClass?.simpleName ?: "Timeout"}")
            return false
        }
        log.event("paired mac=$name")
        return true
    }

    override fun unpair() {
        state.clear()
        peer.stop()
        log.event("unpaired")
    }

    override fun stop() {
        log.event("daemon stopped")
        clipboard?.stop()
        exitProcess(0)
    }

    override fun destroy() = stop()

    private companion object {
        const val PAIR_TIMEOUT_MS = 20_000L
    }
}
