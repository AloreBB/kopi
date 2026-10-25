import AppKit
import CryptoKit

public struct CapturedClip {
    public let content: LocalContent
    // PNG bytes for image clips; nil for text.
    public let imageData: Data?

    public init(content: LocalContent, imageData: Data?) {
        self.content = content
        self.imageData = imageData
    }
}

// Polls an NSPasteboard for changes made by other apps and writes remote clips
// back to it. Never reads a change it wrote itself: reading a promised .png
// would invoke our own data provider (and block on the network).
@MainActor
public final class ClipboardWatcher {
    static let concealedType = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    static let heicType = NSPasteboard.PasteboardType("public.heic")

    public var onCopy: ((CapturedClip) -> Void)?

    private let pasteboard: NSPasteboard
    private let interval: TimeInterval
    private var timer: Timer?
    private var lastChangeCount: Int
    // Kept alive until the pasteboard reports it is finished with it.
    private var activeProvider: ImagePromiseProvider?

    public init(pasteboard: NSPasteboard = .general, interval: TimeInterval = 0.5) {
        self.pasteboard = pasteboard
        self.interval = interval
        self.lastChangeCount = pasteboard.changeCount
    }

    public func start() {
        stop()
        lastChangeCount = pasteboard.changeCount
        timer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    // Called by the timer; exposed so tests can drive it synchronously.
    public func poll() {
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        if let clip = read() {
            onCopy?(clip)
        }
    }

    public func writeText(_ text: String) {
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastChangeCount = pasteboard.changeCount
    }

    // `provide` runs when someone pastes; it may block up to Limits.pasteWaitSeconds.
    // Only PNG is promised: callers convert other formats before writing.
    public func writeImagePromise(mime: String, provide: @escaping () -> Data?) {
        let provider = ImagePromiseProvider(provide: provide)
        provider.onFinished = { [weak self, weak provider] in
            MainActor.assumeIsolated {
                if let self, self.activeProvider === provider { self.activeProvider = nil }
            }
        }
        let item = NSPasteboardItem()
        item.setDataProvider(provider, forTypes: [.png])
        pasteboard.clearContents()
        activeProvider = provider
        pasteboard.writeObjects([item])
        lastChangeCount = pasteboard.changeCount
    }

    public func read() -> CapturedClip? {
        let types = pasteboard.types ?? []
        if types.contains(.string), let text = pasteboard.string(forType: .string) {
            let sensitive = types.contains(Self.concealedType)
            return CapturedClip(content: .text(text, sensitive: sensitive), imageData: nil)
        }
        if types.contains(.png), let png = pasteboard.data(forType: .png) {
            return Self.imageClip(png)
        }
        for type in [NSPasteboard.PasteboardType.tiff, Self.heicType] where types.contains(type) {
            if let data = pasteboard.data(forType: type),
               let png = NSBitmapImageRep(data: data)?.representation(using: .png, properties: [:]) {
                return Self.imageClip(png)
            }
        }
        return nil
    }

    // Oversized images are still reported; SyncEngine rejects them by size.
    private static func imageClip(_ png: Data) -> CapturedClip {
        let digest = Data(SHA256.hash(data: png))
        return CapturedClip(
            content: .image(mime: "image/png", size: png.count, sha256: digest),
            imageData: png)
    }
}

final class ImagePromiseProvider: NSObject, NSPasteboardItemDataProvider {
    private let provide: () -> Data?
    var onFinished: (() -> Void)?

    init(provide: @escaping () -> Data?) {
        self.provide = provide
    }

    func pasteboard(
        _ pasteboard: NSPasteboard?, item: NSPasteboardItem,
        provideDataForType type: NSPasteboard.PasteboardType
    ) {
        guard type == .png, let data = provide() else { return }
        item.setData(data, forType: .png)
    }

    func pasteboardFinishedWithDataProvider(_ pasteboard: NSPasteboard) {
        onFinished?()
    }
}
