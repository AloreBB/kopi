package dev.alorebb.kopi.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val BASE_T: Long = 1_000_000

private fun sha(byte: Byte): ByteArray = ByteArray(32) { byte }

class SyncEngineTest {
    private var t = BASE_T

    private fun engine(
        strategy: ImageStrategy = ImageStrategy.LAZY,
        newId: (() -> String)? = null,
    ): SyncEngine =
        if (newId == null) SyncEngine(strategy, now = { t }) else SyncEngine(strategy, now = { t }, newId = newId)

    @Test
    fun localTextIsSent() {
        val engine = engine()
        val sent = engine.onLocalCopy(LocalContent.Text("hola", sensitive = false))
        val clip = sent as? Clip ?: error("expected clip, got $sent")
        assertEquals("hola", clip.text)
        assertEquals(t, clip.ts)
        assertEquals(false, clip.sensitive)
        assertEquals(ClipKind.TEXT, clip.kind)
    }

    @Test
    fun emptyOrWhitespaceIsNotSent() {
        val engine = engine()
        assertNull(engine.onLocalCopy(LocalContent.Text("", sensitive = false)))
        assertNull(engine.onLocalCopy(LocalContent.Text("  \n", sensitive = false)))
    }

    @Test
    fun oversizedTextIsNotSent() {
        val engine = engine()
        val big = "a".repeat(Limits.MAX_TEXT_BYTES + 1)
        assertNull(engine.onLocalCopy(LocalContent.Text(big, sensitive = false)))
    }

    @Test
    fun echoIsSuppressed() {
        val engine = engine()
        engine.didApplyRemote(LocalContent.Text("A", sensitive = false))
        assertNull(engine.onLocalCopy(LocalContent.Text("A", sensitive = false)))
    }

    @Test
    fun recopyAfterOtherRemoteIsSent() {
        val engine = engine()
        engine.didApplyRemote(LocalContent.Text("A", sensitive = false))
        engine.didApplyRemote(LocalContent.Text("B", sensitive = false))
        assertNotNull(engine.onLocalCopy(LocalContent.Text("A", sensitive = false)))
    }

    @Test
    fun remoteTextIsWritten() {
        val engine = engine()
        val id = "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b"
        val incoming = Clip(id = id, ts = t, kind = ClipKind.TEXT, text = "X", sensitive = false)
        assertEquals(listOf(EngineAction.WriteText("X", id)), engine.onRemote(incoming))
    }

    @Test
    fun olderRemoteIsIgnored() {
        val engine = engine()
        engine.onLocalCopy(LocalContent.Text("hola", sensitive = false))
        val old = Clip(
            id = "11111111-2222-3333-4444-555555555555", ts = t - 1,
            kind = ClipKind.TEXT, text = "viejo", sensitive = false,
        )
        assertEquals(emptyList<EngineAction>(), engine.onRemote(old))
    }

    @Test
    fun equalTsHigherIdWins() {
        val engine = engine()
        engine.onRemote(
            Clip(
                id = "11111111-2222-3333-4444-555555555555", ts = t,
                kind = ClipKind.TEXT, text = "primero", sensitive = false,
            ),
        )
        val loser = Clip(
            id = "00000000-2222-3333-4444-555555555555", ts = t,
            kind = ClipKind.TEXT, text = "menor", sensitive = false,
        )
        assertEquals(emptyList<EngineAction>(), engine.onRemote(loser))
        val winner = Clip(
            id = "ffffffff-2222-3333-4444-555555555555", ts = t,
            kind = ClipKind.TEXT, text = "mayor", sensitive = false,
        )
        val resolved = engine.onRemote(winner)
        assertEquals(1, resolved.size)
        val write = resolved.first() as? EngineAction.WriteText
            ?: error("expected WriteText for the higher id")
        assertEquals("mayor", write.text)
    }

    @Test
    fun lazyOfferRegistersPromise() {
        val engine = engine(ImageStrategy.LAZY)
        val id = "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b"
        val offer = ClipOffer(id = id, ts = t, kind = ClipKind.IMAGE, mime = "image/png", size = 100)
        assertEquals(listOf(EngineAction.RegisterImagePromise(id, "image/png", 100)), engine.onRemote(offer))
    }

    @Test
    fun eagerOfferRequestsImmediately() {
        val engine = engine(ImageStrategy.EAGER)
        val id = "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b"
        val offer = ClipOffer(id = id, ts = t, kind = ClipKind.IMAGE, mime = "image/png", size = 100)
        assertEquals(listOf(EngineAction.RequestImage(id)), engine.onRemote(offer))
    }

    @Test
    fun oversizedImageIsNotOffered() {
        val engine = engine()
        val content = LocalContent.Image(mime = "image/png", size = Limits.MAX_IMAGE_BYTES + 1, sha256 = sha(1))
        assertNull(engine.onLocalCopy(content))
    }

    @Test
    fun imageRequestServesCurrent() {
        val imageId = "11111111-2222-3333-4444-555555555555"
        val engine = engine(newId = { imageId })
        val sent = engine.onLocalCopy(LocalContent.Image(mime = "image/png", size = 100, sha256 = sha(1)))
        val offer = sent as? ClipOffer ?: error("expected clipOffer, got $sent")
        assertEquals(imageId, offer.id)
        assertEquals(ImageServe.Serve(imageId), engine.onImageRequest(imageId))
    }

    @Test
    fun imageRequestExpired() {
        val imageId = "11111111-2222-3333-4444-555555555555"
        val engine = engine(newId = { imageId })
        engine.onLocalCopy(LocalContent.Image(mime = "image/png", size = 100, sha256 = sha(1)))
        t += Limits.OFFER_EXPIRY_MS
        assertEquals(ImageServe.Unavailable(UnavailableReason.EXPIRED), engine.onImageRequest(imageId))
    }

    @Test
    fun imageRequestReplaced() {
        val imageId = "11111111-2222-3333-4444-555555555555"
        val engine = engine(newId = { imageId })
        engine.onLocalCopy(LocalContent.Image(mime = "image/png", size = 100, sha256 = sha(1)))
        engine.onLocalCopy(LocalContent.Text("nuevo", sensitive = false))
        assertEquals(ImageServe.Unavailable(UnavailableReason.REPLACED), engine.onImageRequest(imageId))
    }

    @Test
    fun reconnectResendsRecent() {
        val engine = engine()
        val sent = engine.onLocalCopy(LocalContent.Text("hola", sensitive = false))
        t += Limits.OFFER_EXPIRY_MS - 1
        assertEquals(sent, engine.onReconnect())
        t += 1
        assertNull(engine.onReconnect())
    }

    @Test
    fun reconnectNeverResendsRemoteOrigin() {
        val engine = engine()
        engine.onRemote(
            Clip(
                id = "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b", ts = t,
                kind = ClipKind.TEXT, text = "X", sensitive = false,
            ),
        )
        assertNull(engine.onReconnect())
    }

    @Test
    fun recopyAfterOtherLocalIsSent() {
        val engine = engine()
        engine.didApplyRemote(LocalContent.Text("A", sensitive = false))
        t += 1_000
        assertNotNull(engine.onLocalCopy(LocalContent.Text("B", sensitive = false)))
        t += 1_000
        assertNotNull(engine.onLocalCopy(LocalContent.Text("A", sensitive = false)))
    }

    // The peer's clock may lag ours: ts-based conflict resolution only applies to
    // copies that cross in flight (within Limits.CONFLICT_WINDOW_MS of local time).
    @Test
    fun skewedRemoteAfterConflictWindowIsApplied() {
        val engine = engine()
        engine.onLocalCopy(LocalContent.Text("local", sensitive = false))
        t += Limits.CONFLICT_WINDOW_MS
        val id = "6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b"
        val skewed = Clip(id = id, ts = BASE_T - 5_000, kind = ClipKind.TEXT, text = "X", sensitive = false)
        assertEquals(listOf(EngineAction.WriteText("X", id)), engine.onRemote(skewed))
    }
}
