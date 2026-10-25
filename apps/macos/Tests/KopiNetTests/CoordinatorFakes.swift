import AppKit
import KopiCore
import KopiNet

// AppKit/Foundation-dependent fakes for AppCoordinatorTests (a file cannot import
// both Foundation and Testing on the Command Line Tools toolchain).

@MainActor
final class FakeWatcher: ClipboardWatching {
    var onCopy: ((CapturedClip) -> Void)?
    private(set) var started = false
    private(set) var writtenTexts: [String] = []
    private(set) var promisedMime: String?
    private(set) var provide: (() -> Data?)?

    func start() { started = true }

    func writeText(_ text: String) { writtenTexts.append(text) }

    func writeImagePromise(mime: String, provide: @escaping () -> Data?) {
        promisedMime = mime
        self.provide = provide
    }

    func emitText(_ text: String, sensitive: Bool = false) {
        onCopy?(CapturedClip(content: .text(text, sensitive: sensitive), imageData: nil))
    }

    func emitImage(_ png: Data) {
        onCopy?(CapturedClip(content: .image(mime: "image/png", size: png.count, sha256: Data(repeating: 1, count: 32)), imageData: png))
    }

    /// Runs the pasteboard promise on the calling (main) thread, as NSPasteboard does.
    func paste() -> [UInt8]? {
        provide?().map { [UInt8]($0) }
    }
}

/// Replies to clip_request like a phone would: from a background queue, never main.
enum PhoneReply {
    case none
    case image(Data)
    case disconnect
}

final class FakeServer: PeerServing, @unchecked Sendable {
    private let lock = NSLock()
    private weak var _delegate: PeerServerDelegate?
    private var _connected = true
    private var _sent: [KopiMessage] = []
    private var _sentImages: [(id: UUID, data: Data)] = []
    private var _restarts = 0
    private var _unpairs = 0
    private var _pairedName: String?
    var reply: PhoneReply = .none

    var delegate: PeerServerDelegate? {
        get { lock.withLock { _delegate } }
        set { lock.withLock { _delegate = newValue } }
    }
    var isConnected: Bool {
        get { lock.withLock { _connected } }
        set { lock.withLock { _connected = newValue } }
    }
    var sent: [KopiMessage] { lock.withLock { _sent } }
    var sentImages: [(id: UUID, bytes: [UInt8])] { lock.withLock { _sentImages.map { ($0.id, [UInt8]($0.data)) } } }
    var restarts: Int { lock.withLock { _restarts } }
    var unpairs: Int { lock.withLock { _unpairs } }
    var pairedPhoneName: String? {
        get { lock.withLock { _pairedName } }
        set { lock.withLock { _pairedName = newValue } }
    }

    func start() throws {}
    func restart() throws { lock.withLock { _restarts += 1 } }
    func unpair() {
        lock.withLock {
            _unpairs += 1
            _pairedName = nil
        }
    }

    func send(_ message: KopiMessage) {
        lock.withLock { _sent.append(message) }
        guard case .clipRequest(let request) = message else { return }
        let reply = self.reply
        nonisolated(unsafe) let delegate = self.delegate
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) {
            switch reply {
            case .none:
                break
            case .disconnect:
                delegate?.peerDisconnected()
            case .image(let data):
                delegate?.received(.clipData(ClipData(id: request.id, size: data.count)))
                var offset = 0
                while offset < data.count {
                    let end = min(offset + 1000, data.count)
                    delegate?.receivedImageChunk(id: request.id, bytes: data.subdata(in: offset..<end))
                    offset = end
                }
            }
        }
    }

    func sendImage(id: UUID, data: Data) {
        lock.withLock { _sentImages.append((id, data)) }
    }
}

@MainActor
struct CoordinatorRig {
    let coordinator: AppCoordinator
    let watcher: FakeWatcher
    let server: FakeServer
    let wakeCenter: NotificationCenter
    let logURL: URL

    init(pasteWait: TimeInterval = 5, pairedPhoneName: String? = nil) {
        watcher = FakeWatcher()
        server = FakeServer()
        server.pairedPhoneName = pairedPhoneName
        wakeCenter = NotificationCenter()
        let dir = FileManager.default.temporaryDirectory.appending(path: "kopi-test-\(UUID().uuidString)")
        logURL = dir.appending(path: "kopi.log")
        var counter: UInt8 = 0
        let engine = SyncEngine(strategy: .lazy, now: { 1_000_000 }, newID: {
            counter += 1
            // Fixed ids without the digits used in content assertions.
            return UUID(uuid: (0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x0A, 0xBC, 0xDE, 0xF0, 0xAB, 0xCD, 0xEF, 0xAB, 0xCD, counter))
        })
        coordinator = AppCoordinator(
            watcher: watcher, server: server, engine: engine, log: FileLog(url: logURL),
            wakeCenter: wakeCenter, pasteWait: pasteWait)
        coordinator.start()
    }

    func postWake() {
        wakeCenter.post(name: NSWorkspace.didWakeNotification, object: nil)
    }

    func logContents() -> String {
        (try? String(contentsOf: logURL, encoding: .utf8)) ?? ""
    }

    func cleanUp() {
        try? FileManager.default.removeItem(at: logURL.deletingLastPathComponent())
    }
}

/// Waits until blocks already queued on the main queue have run.
func flushMain() async {
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
        DispatchQueue.main.async { continuation.resume() }
    }
}

func makeID() -> UUID { UUID() }

func makeBytes(_ values: [UInt8]) -> Data { Data(values) }

func remoteClip(_ text: String, id: UUID = UUID()) -> KopiMessage {
    .clip(Clip(id: id, ts: 1_000_000, kind: .text, text: text, sensitive: false))
}

func remoteOffer(id: UUID, mime: String, size: Int) -> KopiMessage {
    .clipOffer(ClipOffer(id: id, ts: 1_000_000, kind: .image, mime: mime, size: size))
}

func makeImage(_ type: NSBitmapImageRep.FileType) -> Data {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: 8, pixelsHigh: 8, bitsPerSample: 8,
        samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    return rep.representation(using: type, properties: [:])!
}

func makePNG() -> Data { makeImage(.png) }
func makeJPEG() -> Data { makeImage(.jpeg) }
