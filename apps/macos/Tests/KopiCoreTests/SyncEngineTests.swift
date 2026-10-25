import Testing
import KopiCore

private let baseT: Int64 = 1_000_000

@Test func localTextIsSent() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    let sent = engine.onLocalCopy(.text("hola", sensitive: false))
    switch sent {
    case .clip(let clip)?:
        #expect(clip.text == "hola")
        #expect(clip.ts == t)
        #expect(clip.sensitive == false)
        #expect(clip.kind == .text)
    default:
        #expect(false, "expected clip, got \(String(describing: sent))")
    }
}

@Test func emptyOrWhitespaceIsNotSent() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    #expect(engine.onLocalCopy(.text("", sensitive: false)) == nil)
    #expect(engine.onLocalCopy(.text("  \n", sensitive: false)) == nil)
}

@Test func oversizedTextIsNotSent() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    let big = String(repeating: "a", count: Limits.maxTextBytes + 1)
    #expect(engine.onLocalCopy(.text(big, sensitive: false)) == nil)
}

@Test func echoIsSuppressed() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    engine.didApplyRemote(.text("A", sensitive: false))
    #expect(engine.onLocalCopy(.text("A", sensitive: false)) == nil)
}

@Test func recopyAfterOtherRemoteIsSent() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    engine.didApplyRemote(.text("A", sensitive: false))
    engine.didApplyRemote(.text("B", sensitive: false))
    #expect(engine.onLocalCopy(.text("A", sensitive: false)) != nil)
}

@Test func remoteTextIsWritten() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    let id = uuid("6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b")
    let incoming = KopiMessage.clip(Clip(id: id, ts: t, kind: .text, text: "X", sensitive: false))
    #expect(engine.onRemote(incoming) == [.writeText("X", id: id)])
}

@Test func olderRemoteIsIgnored() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    _ = engine.onLocalCopy(.text("hola", sensitive: false))
    let old = KopiMessage.clip(
        Clip(id: uuid("11111111-2222-3333-4444-555555555555"), ts: t - 1,
             kind: .text, text: "viejo", sensitive: false))
    #expect(engine.onRemote(old) == [])
}

@Test func equalTsHigherIdWins() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    _ = engine.onRemote(.clip(
        Clip(id: uuid("11111111-2222-3333-4444-555555555555"), ts: t,
             kind: .text, text: "primero", sensitive: false)))
    let loser = KopiMessage.clip(
        Clip(id: uuid("00000000-2222-3333-4444-555555555555"), ts: t,
             kind: .text, text: "menor", sensitive: false))
    #expect(engine.onRemote(loser) == [])
    let winner = KopiMessage.clip(
        Clip(id: uuid("ffffffff-2222-3333-4444-555555555555"), ts: t,
             kind: .text, text: "mayor", sensitive: false))
    let resolved = engine.onRemote(winner)
    #expect(resolved.count == 1)
    if case let .writeText(text, id: _)? = resolved.first {
        #expect(text == "mayor")
    } else {
        #expect(false, "expected writeText for the higher id")
    }
}

@Test func lazyOfferRegistersPromise() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    let id = uuid("6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b")
    let offer = KopiMessage.clipOffer(
        ClipOffer(id: id, ts: t, kind: .image, mime: "image/png", size: 100))
    #expect(engine.onRemote(offer) == [.registerImagePromise(id: id, mime: "image/png", size: 100)])
}

@Test func eagerOfferRequestsImmediately() {
    var t = baseT
    let engine = SyncEngine(strategy: .eager, now: { t })
    let id = uuid("6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b")
    let offer = KopiMessage.clipOffer(
        ClipOffer(id: id, ts: t, kind: .image, mime: "image/png", size: 100))
    #expect(engine.onRemote(offer) == [.requestImage(id: id)])
}

@Test func oversizedImageIsNotOffered() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    let content = LocalContent.image(mime: "image/png", size: Limits.maxImageBytes + 1, sha256: sha(1))
    #expect(engine.onLocalCopy(content) == nil)
}

@Test func imageRequestServesCurrent() {
    var t = baseT
    let imageID = uuid("11111111-2222-3333-4444-555555555555")
    let engine = SyncEngine(strategy: .lazy, now: { t }, newID: { imageID })
    let sent = engine.onLocalCopy(
        .image(mime: "image/png", size: 100, sha256: sha(1)))
    switch sent {
    case .clipOffer(let offer)?:
        #expect(offer.id == imageID)
    default:
        #expect(false, "expected clipOffer, got \(String(describing: sent))")
    }
    #expect(engine.onImageRequest(id: imageID) == .serve(id: imageID))
}

@Test func imageRequestExpired() {
    var t = baseT
    let imageID = uuid("11111111-2222-3333-4444-555555555555")
    let engine = SyncEngine(strategy: .lazy, now: { t }, newID: { imageID })
    _ = engine.onLocalCopy(.image(mime: "image/png", size: 100, sha256: sha(1)))
    t += Limits.offerExpiryMs
    #expect(engine.onImageRequest(id: imageID) == .unavailable(.expired))
}

@Test func imageRequestReplaced() {
    var t = baseT
    let imageID = uuid("11111111-2222-3333-4444-555555555555")
    let engine = SyncEngine(strategy: .lazy, now: { t }, newID: { imageID })
    _ = engine.onLocalCopy(.image(mime: "image/png", size: 100, sha256: sha(1)))
    _ = engine.onLocalCopy(.text("nuevo", sensitive: false))
    #expect(engine.onImageRequest(id: imageID) == .unavailable(.replaced))
}

@Test func reconnectResendsRecent() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    let sent = engine.onLocalCopy(.text("hola", sensitive: false))
    t += Limits.offerExpiryMs - 1
    #expect(engine.onReconnect() == sent)
    t += 1
    #expect(engine.onReconnect() == nil)
}

@Test func reconnectNeverResendsRemoteOrigin() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    _ = engine.onRemote(.clip(
        Clip(id: uuid("6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b"), ts: t,
             kind: .text, text: "X", sensitive: false)))
    #expect(engine.onReconnect() == nil)
}

@Test func recopyAfterOtherLocalIsSent() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    engine.didApplyRemote(.text("A", sensitive: false))
    t += 1_000
    #expect(engine.onLocalCopy(.text("B", sensitive: false)) != nil)
    t += 1_000
    #expect(engine.onLocalCopy(.text("A", sensitive: false)) != nil)
}

// The peer's clock may lag ours: ts-based conflict resolution only applies to
// copies that cross in flight (within Limits.conflictWindowMs of local time).
@Test func skewedRemoteAfterConflictWindowIsApplied() {
    var t = baseT
    let engine = SyncEngine(strategy: .lazy, now: { t })
    _ = engine.onLocalCopy(.text("local", sensitive: false))
    t += Limits.conflictWindowMs
    let id = uuid("6f1c2a9e-3b4d-4e5f-8a7b-1c2d3e4f5a6b")
    let skewed = KopiMessage.clip(Clip(id: id, ts: baseT - 5_000, kind: .text, text: "X", sensitive: false))
    #expect(engine.onRemote(skewed) == [.writeText("X", id: id)])
}
