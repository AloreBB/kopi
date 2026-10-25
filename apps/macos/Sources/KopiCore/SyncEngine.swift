import Foundation

// Pure clipboard synchronization rules (spec section 6.4 and Review Focus).
// Synchronous and not thread-safe: the coordinator calls it from one actor.
public enum LocalContent: Equatable {
    case text(String, sensitive: Bool)
    case image(mime: String, size: Int, sha256: Data)
}

public enum ImageStrategy {
    case lazy
    case eager
}

public enum EngineAction: Equatable {
    case send(KopiMessage)
    case writeText(String, id: UUID)
    case registerImagePromise(id: UUID, mime: String, size: Int)
    case requestImage(id: UUID)
}

public enum ImageServe: Equatable {
    case serve(id: UUID)
    case unavailable(UnavailableReason)
}

public final class SyncEngine {
    private let strategy: ImageStrategy
    private let now: () -> Int64
    private let newID: () -> UUID

    private var lastAppliedRemote: LocalContent?
    private var current: (ts: Int64, id: UUID, setAt: Int64)?
    private var lastOutgoing: KopiMessage?
    private var lastOutgoingTs: Int64 = 0
    private var lastOriginIsLocal = false
    private var lastPreview: (preview: PreviewInput, ts: Int64)?

    public init(
        strategy: ImageStrategy,
        now: @escaping () -> Int64,
        newID: @escaping () -> UUID = UUID.init
    ) {
        self.strategy = strategy
        self.now = now
        self.newID = newID
    }

    // Returns the message to send, or nil when the copy must not be sent
    // (empty, oversized, or an echo of the last remote-applied content).
    public func onLocalCopy(_ content: LocalContent) -> KopiMessage? {
        switch content {
        case .text(let text, let sensitive):
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            guard text.utf8.count <= Limits.maxTextBytes else { return nil }
            guard content != lastAppliedRemote else { return nil }
            let clip = Clip(id: newID(), ts: now(), kind: .text, text: text, sensitive: sensitive)
            recordLocal(.clip(clip), id: clip.id, ts: clip.ts, preview: .text(text, sensitive: sensitive))
            return .clip(clip)
        case .image(let mime, let size, _):
            guard size <= Limits.maxImageBytes else { return nil }
            guard content != lastAppliedRemote else { return nil }
            let offer = ClipOffer(id: newID(), ts: now(), kind: .image, mime: mime, size: size)
            recordLocal(.clipOffer(offer), id: offer.id, ts: offer.ts, preview: .image)
            return .clipOffer(offer)
        }
    }

    // Remote clipboard content becomes local actions. Within the conflict
    // window, a remote item older than the current one is ignored and on equal
    // ts the greater id wins; after it, arrival order wins (clocks may skew).
    public func onRemote(_ message: KopiMessage) -> [EngineAction] {
        switch message {
        case .clip(let clip):
            guard isNewer(ts: clip.ts, id: clip.id) else { return [] }
            current = (clip.ts, clip.id, now())
            lastPreview = (.text(clip.text, sensitive: clip.sensitive), clip.ts)
            lastOriginIsLocal = false
            return [.writeText(clip.text, id: clip.id)]
        case .clipOffer(let offer):
            guard isNewer(ts: offer.ts, id: offer.id) else { return [] }
            current = (offer.ts, offer.id, now())
            lastPreview = (.image, offer.ts)
            lastOriginIsLocal = false
            switch strategy {
            case .lazy:
                return [.registerImagePromise(id: offer.id, mime: offer.mime, size: offer.size)]
            case .eager:
                return [.requestImage(id: offer.id)]
            }
        default:
            return []
        }
    }

    // Call after the actions from onRemote were written to the local clipboard.
    public func didApplyRemote(_ content: LocalContent) {
        lastAppliedRemote = content
    }

    // Answers a clip_request for an image offered by onLocalCopy.
    public func onImageRequest(id: UUID) -> ImageServe {
        guard lastOriginIsLocal else { return .unavailable(.replaced) }
        switch lastOutgoing {
        case .clipOffer(let offer)? where offer.id == id:
            if now() - offer.ts >= Limits.offerExpiryMs {
                return .unavailable(.expired)
            }
            return .serve(id: id)
        default:
            return .unavailable(.replaced)
        }
    }

    // Only the last local item is resent, and only while it is fresh.
    public func onReconnect() -> KopiMessage? {
        guard lastOriginIsLocal, let message = lastOutgoing else { return nil }
        guard now() - lastOutgoingTs < Limits.offerExpiryMs else { return nil }
        return message
    }

    public var lastItem: (preview: PreviewInput, ts: Int64)? {
        lastPreview
    }

    private func recordLocal(_ message: KopiMessage, id: UUID, ts: Int64, preview: PreviewInput) {
        current = (ts, id, now())
        // The peer now holds this item, so a later re-copy of the old remote content is new.
        lastAppliedRemote = nil
        lastOutgoing = message
        lastOutgoingTs = ts
        lastOriginIsLocal = true
        lastPreview = (preview, ts)
    }

    private func isNewer(ts: Int64, id: UUID) -> Bool {
        guard let current else { return true }
        if now() - current.setAt >= Limits.conflictWindowMs { return true }
        if ts != current.ts { return ts > current.ts }
        return id.uuidString.lowercased() > current.id.uuidString.lowercased()
    }
}
