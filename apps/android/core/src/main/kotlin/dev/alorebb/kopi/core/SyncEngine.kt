package dev.alorebb.kopi.core

import java.util.UUID

// Pure clipboard synchronization rules (spec section 6.4 and Review Focus).
// Synchronous and not thread-safe: the coordinator calls it from one thread.
sealed interface LocalContent {
    data class Text(val text: String, val sensitive: Boolean) : LocalContent

    data class Image(val mime: String, val size: Int, val sha256: ByteArray) : LocalContent {
        override fun equals(other: Any?): Boolean =
            other is Image && mime == other.mime && size == other.size && sha256.contentEquals(other.sha256)

        override fun hashCode(): Int = (mime.hashCode() * 31 + size) * 31 + sha256.contentHashCode()
    }
}

enum class ImageStrategy { LAZY, EAGER }

sealed interface EngineAction {
    data class Send(val message: KopiMessage) : EngineAction
    data class WriteText(val text: String, val id: String) : EngineAction
    data class RegisterImagePromise(val id: String, val mime: String, val size: Int) : EngineAction
    data class RequestImage(val id: String) : EngineAction
}

sealed interface ImageServe {
    data class Serve(val id: String) : ImageServe
    data class Unavailable(val reason: UnavailableReason) : ImageServe
}

data class LastItem(val preview: PreviewInput, val ts: Long)

class SyncEngine(
    private val strategy: ImageStrategy,
    private val now: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Current(val ts: Long, val id: String, val setAt: Long)

    private var lastAppliedRemote: LocalContent? = null

    // Last content sent while the peer still holds it; cleared by any remote item.
    private var lastSentLocal: LocalContent? = null
    private var current: Current? = null
    private var lastOutgoing: KopiMessage? = null
    private var lastOutgoingTs: Long = 0
    private var lastOriginIsLocal = false

    var lastItem: LastItem? = null
        private set

    // Returns the message to send, or null when the copy must not be sent
    // (empty, oversized, an echo of the last remote-applied content, or a
    // repeat of the last sent content that the peer already holds).
    fun onLocalCopy(content: LocalContent): KopiMessage? = when (content) {
        is LocalContent.Text -> {
            if (content.text.isBlank() ||
                content.text.encodeToByteArray().size > Limits.MAX_TEXT_BYTES ||
                content == lastAppliedRemote ||
                content == lastSentLocal
            ) {
                null
            } else {
                val clip = Clip(newId(), now(), ClipKind.TEXT, content.text, content.sensitive)
                recordLocal(content, clip, clip.id, clip.ts, PreviewInput.Text(content.text, content.sensitive))
                clip
            }
        }
        is LocalContent.Image -> {
            if (content.size > Limits.MAX_IMAGE_BYTES ||
                content == lastAppliedRemote ||
                content == lastSentLocal
            ) {
                null
            } else {
                val offer = ClipOffer(newId(), now(), ClipKind.IMAGE, content.mime, content.size)
                recordLocal(content, offer, offer.id, offer.ts, PreviewInput.Image)
                offer
            }
        }
    }

    // Remote clipboard content becomes local actions. Within the conflict
    // window, a remote item older than the current one is ignored and on equal
    // ts the greater id wins; after it, arrival order wins (clocks may skew).
    fun onRemote(message: KopiMessage): List<EngineAction> = when (message) {
        is Clip -> {
            if (!isNewer(message.ts, message.id)) {
                emptyList()
            } else {
                recordRemote(message.ts, message.id, PreviewInput.Text(message.text, message.sensitive))
                listOf(EngineAction.WriteText(message.text, message.id))
            }
        }
        is ClipOffer -> {
            if (!isNewer(message.ts, message.id)) {
                emptyList()
            } else {
                recordRemote(message.ts, message.id, PreviewInput.Image)
                when (strategy) {
                    ImageStrategy.LAZY ->
                        listOf(EngineAction.RegisterImagePromise(message.id, message.mime, message.size))
                    ImageStrategy.EAGER -> listOf(EngineAction.RequestImage(message.id))
                }
            }
        }
        else -> emptyList()
    }

    // Call after the actions from onRemote were written to the local clipboard.
    fun didApplyRemote(content: LocalContent) {
        lastAppliedRemote = content
        lastSentLocal = null
    }

    // Answers a clip_request for an image offered by onLocalCopy.
    fun onImageRequest(id: String): ImageServe {
        val offer = lastOutgoing as? ClipOffer
        if (!lastOriginIsLocal || offer == null || offer.id != id) {
            return ImageServe.Unavailable(UnavailableReason.REPLACED)
        }
        if (now() - offer.ts >= Limits.OFFER_EXPIRY_MS) {
            return ImageServe.Unavailable(UnavailableReason.EXPIRED)
        }
        return ImageServe.Serve(id)
    }

    // Only the last local item is resent, and only while it is fresh.
    fun onReconnect(): KopiMessage? {
        val message = lastOutgoing
        if (!lastOriginIsLocal || message == null) return null
        if (now() - lastOutgoingTs >= Limits.OFFER_EXPIRY_MS) return null
        return message
    }

    private fun recordLocal(content: LocalContent, message: KopiMessage, id: String, ts: Long, preview: PreviewInput) {
        current = Current(ts, id, now())
        // The peer now holds this item, so a later re-copy of the old remote content is new.
        lastAppliedRemote = null
        lastSentLocal = content
        lastOutgoing = message
        lastOutgoingTs = ts
        lastOriginIsLocal = true
        lastItem = LastItem(preview, ts)
    }

    private fun recordRemote(ts: Long, id: String, preview: PreviewInput) {
        current = Current(ts, id, now())
        lastItem = LastItem(preview, ts)
        lastOriginIsLocal = false
        lastSentLocal = null
    }

    private fun isNewer(ts: Long, id: String): Boolean {
        val current = current ?: return true
        if (now() - current.setAt >= Limits.CONFLICT_WINDOW_MS) return true
        if (ts != current.ts) return ts > current.ts
        return id > current.id
    }
}
