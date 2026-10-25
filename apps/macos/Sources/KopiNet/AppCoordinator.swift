import AppKit
import Combine
import KopiCore

/// The pasteboard side, as the coordinator sees it (`ClipboardWatcher` in the app).
@MainActor
public protocol ClipboardWatching: AnyObject {
    var onCopy: ((CapturedClip) -> Void)? { get set }
    func start()
    func writeText(_ text: String)
    func writeImagePromise(mime: String, provide: @escaping () -> Data?)
}

extension ClipboardWatcher: ClipboardWatching {}

/// The network side (`PeerServer` in the app). Every method is safe from any thread.
public protocol PeerServing: AnyObject, Sendable {
    var delegate: PeerServerDelegate? { get set }
    var isConnected: Bool { get }
    /// Name of the paired phone, or nil while unpaired.
    var pairedPhoneName: String? { get }
    func start() throws
    func restart() throws
    func send(_ message: KopiMessage)
    func sendImage(id: UUID, data: Data)
    /// Forgets the paired phone and drops its session; the next phone pairs itself.
    func unpair()
}

extension PeerServer: PeerServing {}

/// Wires ClipboardWatcher ↔ SyncEngine ↔ PeerServer and publishes what the menu shows.
///
/// Server callbacks arrive on the server's queue. Everything except the image transfer
/// hops to the main actor; image bytes go straight into `IncomingImageBuffer`, because a
/// paste blocks the main thread while it waits for them.
@MainActor
public final class AppCoordinator: ObservableObject {
    public enum Status: Equatable {
        case connected(String)
        /// No phone paired yet: the next one that connects pairs itself.
        case waiting
        /// A phone is paired but not connected.
        case disconnected
    }

    public struct LastItem: Equatable {
        public let preview: String
        public let at: Date
    }

    public static let pulseDuration: Duration = .milliseconds(600)

    @Published public private(set) var status: Status = .waiting
    @Published public private(set) var last: LastItem?
    @Published public private(set) var pulse = false
    /// The phone was turned away because it speaks an older protocol.
    @Published public private(set) var phoneOutdated = false
    /// The paired phone's name, or nil while unpaired.
    @Published public private(set) var pairedName: String?

    /// Called when a phone pairs itself (trust on first use), so the app can tell the user.
    public var onAutoPaired: ((String) -> Void)?

    private let watcher: any ClipboardWatching
    private let engine: SyncEngine
    private let wakeCenter: NotificationCenter
    private let pasteWait: TimeInterval
    nonisolated private let server: any PeerServing
    nonisolated private let log: FileLog
    nonisolated private let incoming = IncomingImageBuffer()

    // The latest local image, kept to answer clip_request.
    private var localImage: (id: UUID, data: Data)?
    private var pulseTask: Task<Void, Never>?
    nonisolated(unsafe) private var wakeToken: NSObjectProtocol?

    public init(
        watcher: any ClipboardWatching,
        server: any PeerServing,
        engine: SyncEngine,
        log: FileLog,
        wakeCenter: NotificationCenter = NSWorkspace.shared.notificationCenter,
        pasteWait: TimeInterval = TimeInterval(Limits.pasteWaitSeconds)
    ) {
        self.watcher = watcher
        self.server = server
        self.engine = engine
        self.log = log
        self.wakeCenter = wakeCenter
        self.pasteWait = pasteWait
    }

    deinit {
        if let wakeToken { wakeCenter.removeObserver(wakeToken) }
    }

    public func start() {
        watcher.onCopy = { [weak self] clip in self?.handleLocalCopy(clip) }
        server.delegate = self
        pairedName = server.pairedPhoneName
        status = pairedName == nil ? .waiting : .disconnected
        // NSWorkspace posts on the main thread; restart() is safe from any thread.
        wakeToken = wakeCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: nil
        ) { [server, log] _ in
            log.event("wake_restart")
            do {
                try server.restart()
            } catch {
                log.event("restart_failed")
            }
        }
        do {
            try server.start()
            log.event("server_started")
        } catch {
            log.event("server_start_failed")
            status = .disconnected
        }
        watcher.start()
    }

    // MARK: - Local copies

    private func handleLocalCopy(_ clip: CapturedClip) {
        let kind = Self.kind(of: clip.content)
        guard let message = engine.onLocalCopy(clip.content) else {
            log.event("copy_skipped", kind: kind, size: Self.size(of: clip.content))
            return
        }
        if case .clipOffer(let offer) = message, let data = clip.imageData {
            localImage = (offer.id, data)
        }
        refreshLast()
        // Nothing goes out while offline; the engine resends the latest item on reconnect.
        guard server.isConnected else {
            log.event("send_skipped", kind: kind, size: Self.size(of: clip.content), reason: "offline")
            return
        }
        switch message {
        case .clip(let sent):
            log.event("copy_sent", id: sent.id, kind: kind, size: sent.text.utf8.count)
        case .clipOffer(let offer):
            log.event("offer_sent", id: offer.id, kind: kind, size: offer.size)
        default:
            break
        }
        server.send(message)
    }

    // MARK: - Remote messages (main actor)

    private func handle(_ message: KopiMessage) {
        switch message {
        case .clip, .clipOffer:
            apply(engine.onRemote(message))
        case .clipRequest(let request):
            serveImage(request.id)
        default:
            break
        }
    }

    private func apply(_ actions: [EngineAction]) {
        for action in actions {
            switch action {
            case .writeText(let text, let id):
                watcher.writeText(text)
                engine.didApplyRemote(.text(text, sensitive: false))
                log.event("remote_text", id: id, kind: "text", size: text.utf8.count)
                markReceived()
            case .registerImagePromise(let id, let mime, let size):
                guard Limits.allowedImageMimes.contains(mime), size <= Limits.maxImageBytes else {
                    log.event("remote_image_rejected", id: id, kind: "image", size: size)
                    continue
                }
                // The promise is always PNG; JPEG bytes are converted when pasted.
                watcher.writeImagePromise(
                    mime: "image/png",
                    provide: Self.imageProvider(
                        id: id, mime: mime, server: server, incoming: incoming, wait: pasteWait, log: log))
                log.event("remote_image_offer", id: id, kind: "image", size: size)
                markReceived()
            case .send(let message):
                server.send(message)
            case .requestImage:
                // Eager strategy is Android-only.
                break
            }
        }
    }

    private func serveImage(_ id: UUID) {
        switch engine.onImageRequest(id: id) {
        case .serve(let id):
            if let image = localImage, image.id == id {
                server.sendImage(id: id, data: image.data)
                log.event("image_served", id: id, kind: "image", size: image.data.count)
            } else {
                server.send(.clipUnavailable(ClipUnavailable(id: id, reason: .replaced)))
                log.event("image_unavailable", id: id, kind: "image")
            }
        case .unavailable(let reason):
            server.send(.clipUnavailable(ClipUnavailable(id: id, reason: reason)))
            log.event("image_unavailable", id: id, kind: "image")
        }
    }

    /// Runs on the main thread inside NSPasteboard and blocks until the image
    /// arrives, the phone disconnects, or `wait` elapses (then the paste yields nothing).
    nonisolated static func imageProvider(
        id: UUID, mime: String, server: any PeerServing, incoming: IncomingImageBuffer,
        wait: TimeInterval, log: FileLog
    ) -> () -> Data? {
        return {
            incoming.expect(id)
            guard server.isConnected else {
                incoming.discard(id)
                log.event("paste_offline", id: id, kind: "image")
                return nil
            }
            server.send(.clipRequest(ClipRequest(id: id)))
            guard let bytes = incoming.wait(id, timeout: wait) else {
                log.event("paste_failed", id: id, kind: "image")
                return nil
            }
            if mime == "image/png" {
                log.event("paste_image", id: id, kind: "image", size: bytes.count)
                return bytes
            }
            guard let png = NSBitmapImageRep(data: bytes)?.representation(using: .png, properties: [:]) else {
                log.event("convert_failed", id: id, kind: "image", size: bytes.count)
                return nil
            }
            log.event("paste_image_converted", id: id, kind: "image", size: png.count)
            return png
        }
    }

    // MARK: - Pairing and connection state (main actor)

    /// Forgets the paired phone; the next phone that connects pairs itself.
    public func unpair() {
        server.unpair()
        pairedName = nil
        status = .waiting
        log.event("unpaired")
    }

    private func didAutoPair(name: String) {
        pairedName = name
        log.event("auto_paired")
        pulseIcon()
        onAutoPaired?(name)
    }

    private func didConnect(name: String) {
        pairedName = name
        status = .connected(name)
        phoneOutdated = false
        log.event("connected")
        if let message = engine.onReconnect() {
            server.send(message)
            log.event("resent_on_reconnect")
        }
    }

    private func didDisconnect() {
        status = server.pairedPhoneName == nil ? .waiting : .disconnected
        log.event("disconnected")
    }

    private func markReceived() {
        refreshLast()
        pulseIcon()
    }

    private func pulseIcon() {
        pulse = true
        pulseTask?.cancel()
        pulseTask = Task { [weak self] in
            try? await Task.sleep(for: Self.pulseDuration)
            guard !Task.isCancelled else { return }
            self?.pulse = false
        }
    }

    private func refreshLast() {
        guard let item = engine.lastItem else { return }
        last = LastItem(preview: preview(item.preview), at: Date())
    }

    private static func kind(of content: LocalContent) -> String {
        switch content {
        case .text: "text"
        case .image: "image"
        }
    }

    private static func size(of content: LocalContent) -> Int {
        switch content {
        case .text(let text, _): text.utf8.count
        case .image(_, let size, _): size
        }
    }

    private nonisolated func onMain(_ body: @escaping @MainActor (AppCoordinator) -> Void) {
        // FIFO hop: keeps the order in which the server delivered events.
        DispatchQueue.main.async {
            MainActor.assumeIsolated { body(self) }
        }
    }
}

extension AppCoordinator: PeerServerDelegate {
    nonisolated public func peerConnected(name: String) {
        onMain { $0.didConnect(name: name) }
    }

    nonisolated public func peerPaired(name: String) {
        onMain { $0.didAutoPair(name: name) }
    }

    nonisolated public func peerDisconnected() {
        // Unblock a paste that is waiting for this phone right away.
        incoming.cancelAll()
        onMain { $0.didDisconnect() }
    }

    nonisolated public func received(_ message: KopiMessage) {
        switch message {
        case .clipData(let header):
            incoming.begin(id: header.id, size: header.size)
        case .clipUnavailable(let unavailable):
            incoming.fail(id: unavailable.id)
            log.event("remote_unavailable", id: unavailable.id, kind: "image")
        default:
            onMain { $0.handle(message) }
        }
    }

    nonisolated public func receivedImageChunk(id: UUID, bytes: Data) {
        incoming.append(id: id, bytes: bytes)
    }

    nonisolated public func peerVersionMismatch(protocolVersion: Int) {
        guard protocolVersion < Limits.protocolVersion else { return }
        onMain { $0.phoneOutdated = true }
    }
}
