package dev.alorebb.kopi.daemon

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.IOnPrimaryClipChangedListener
import android.net.Uri
import android.os.IBinder
import android.os.PersistableBundle
import dev.alorebb.kopi.clip.ImageCodec
import dev.alorebb.kopi.core.LocalContent
import dev.alorebb.kopi.core.Limits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.Executors

class CapturedClip(val content: LocalContent, val imageBytes: ByteArray?)

/**
 * The clipboard as `com.android.shell` (uid 2000 may read it in the background): hidden `IClipboard`
 * by reflection, its change listener and, if that stays silent, a poll every [pollMs].
 * Never logs clipboard content; the change fingerprint stays in memory.
 */
@SuppressLint("PrivateApi")
class ShellClipboard(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: DaemonLog,
    private val pollMs: Long = 1_000,
) {
    private val _copies = MutableSharedFlow<CapturedClip>(extraBufferCapacity = 16)
    val copies: SharedFlow<CapturedClip> = _copies.asSharedFlow()

    private val gate = ChangeGate()
    val source: ClipSource get() = gate.source

    private val reader = Executors.newSingleThreadExecutor { Thread(it, "kopi-clipboard") }.asCoroutineDispatcher()
    private val clipboard: Any
    private val getClip: Pair<Method, MethodSig>
    private val setClip: Pair<Method, MethodSig>
    private var pollJob: Job? = null
    private var reportedPoll = false

    // Keep a strong reference: the framework only holds it weakly through the Binder.
    private val listener = object : IOnPrimaryClipChangedListener.Stub() {
        override fun dispatchPrimaryClipChanged() {
            scope.launch(reader) { check(ClipSource.LISTENER) }
        }
    }

    init {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder
        clipboard = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: error("no IClipboard")
        getClip = resolve("getPrimaryClip")
        setClip = resolve("setPrimaryClip", ClipData::class.java)
        log.event("clipboard sig=${describe(getClip.second)}")
    }

    fun start() {
        val registered = runCatching {
            val (method, sig) = resolve("addPrimaryClipChangedListener", IOnPrimaryClipChangedListener::class.java)
            method.invoke(clipboard, *ClipboardCall.args(sig, listOf(listener)))
        }
        if (registered.isFailure) {
            gate.usePolling()
            log.event("clipboard listener failed reason=${rootName(registered.exceptionOrNull()!!)}")
        }
        log.event("clipboard source=${gate.source.name.lowercase()}")
        reportedPoll = gate.source == ClipSource.POLL
        // Seed the gate with what is already there, so starting does not count as a copy.
        scope.launch(reader) { fingerprintOf(read()).let { gate.accept(it, ClipSource.LISTENER) } }
        pollJob = scope.launch(reader) {
            while (isActive) {
                delay(pollMs)
                check(ClipSource.POLL)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        runCatching {
            val (method, sig) = resolve("removePrimaryClipChangedListener", IOnPrimaryClipChangedListener::class.java)
            method.invoke(clipboard, *ClipboardCall.args(sig, listOf(listener)))
        }
    }

    fun writeText(text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(LABEL, text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        write(clip)
    }

    /** Saves the image as `DaemonPaths.CLIPS/<id>.png` (removing the previous one) and puts its `content://` URI on the clipboard. */
    fun writeImage(id: String, bytes: ByteArray, mime: String) {
        val dir = DaemonPaths.CLIPS
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name != "$id.png") it.delete() }
        File(dir, "$id.png").writeBytes(bytes)
        val uri = Uri.parse("content://${DaemonPaths.CLIPS_AUTHORITY}/$id")
        write(ClipData(LABEL, arrayOf(mime), ClipData.Item(uri)))
    }

    private fun write(clip: ClipData) {
        // Our own write is not a local copy.
        gate.accept(fingerprintOf(clip), ClipSource.LISTENER)
        setClip.first.invoke(clipboard, *ClipboardCall.args(setClip.second, listOf(clip)))
    }

    private fun read(): ClipData? = getClip.first.invoke(clipboard, *ClipboardCall.args(getClip.second, emptyList())) as ClipData?

    private suspend fun check(from: ClipSource) {
        val clip = runCatching { read() }.getOrElse {
            log.event("clipboard read failed reason=${rootName(it)}")
            return
        }
        if (!gate.accept(fingerprintOf(clip), from)) return
        if (gate.source == ClipSource.POLL && !reportedPoll) {
            reportedPoll = true
            log.event("clipboard source=poll")
        }
        capture(clip!!)?.let { _copies.emit(it) }
    }

    private fun capture(clip: ClipData): CapturedClip? {
        if (clip.itemCount == 0) return null
        val description = clip.description
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (description.hasMimeType("image/*") && uri != null) {
            val bytes = try {
                readLimited(uri)
            } catch (e: Throwable) {
                log.event("image read denied reason=${rootName(e)}")
                return null
            }
            val image = bytes?.let(ImageCodec::normalize) ?: run {
                log.event("image read denied reason=${if (bytes == null) "TooLarge" else "Undecodable"}")
                return null
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(image.bytes)
            return CapturedClip(LocalContent.Image(image.mime, image.bytes.size, sha), image.bytes)
        }
        val text = (item.text ?: item.htmlText)?.toString() ?: return null
        val sensitive = description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        return CapturedClip(LocalContent.Text(text, sensitive), null)
    }

    /** Null when larger than the image limit. */
    private fun readLimited(uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: error("no stream")
        return input.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > Limits.MAX_IMAGE_BYTES) return null
            }
            out.toByteArray()
        }
    }

    private fun resolve(name: String, vararg leading: Class<*>): Pair<Method, MethodSig> {
        val methods = clipboard.javaClass.methods.filter { it.name == name }
        val sigs = methods.map { MethodSig(it.name, it.parameterTypes.toList()) }
        val sig = ClipboardCall.pick(sigs, name, leading.toList()) ?: error("no usable $name")
        return methods[sigs.indexOf(sig)] to sig
    }

    companion object {
        private const val LABEL = "Kopi"

        private fun describe(sig: MethodSig) =
            "${sig.name}(${sig.params.joinToString(",") { it.simpleName.substringAfterLast('.') }})"

        private fun rootName(e: Throwable): String {
            var t = e
            while (t.cause != null && t.cause !== t) t = t.cause!!
            return t.javaClass.simpleName
        }

        /** MIME types, length and SHA-256 of the text or URI. Null for an empty or missing clip. */
        fun fingerprintOf(clip: ClipData?): String? {
            if (clip == null || clip.itemCount == 0) return null
            val d = clip.description
            val mimes = (0 until d.mimeTypeCount).joinToString(",") { d.getMimeType(it) }
            val item = clip.getItemAt(0)
            val body = item.uri?.toString() ?: (item.text ?: item.htmlText)?.toString() ?: return null
            val sha = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "$mimes|${body.length}|$sha"
        }
    }
}
