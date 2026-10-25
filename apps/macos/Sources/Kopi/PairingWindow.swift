import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import KopiCore
import KopiNet
import SwiftUI

/// One pairing attempt: a fresh one-time code and its QR, which expires after 300 s.
@MainActor
final class PairingSession: ObservableObject {
    @Published private(set) var qr: NSImage?
    @Published private(set) var expired = false

    private let pairing: PairingStore
    private let fingerprint: String
    private var expiryTask: Task<Void, Never>?

    init(pairing: PairingStore, fingerprint: String) {
        self.pairing = pairing
        self.fingerprint = fingerprint
    }

    func regenerate() {
        // Prefer the LAN address; fall back to the Bonjour host name.
        let host = PairingStore.primaryIPv4() ?? ProcessInfo.processInfo.hostName
        let url = pairing.beginPairing(host: host, fingerprint: fingerprint)
        qr = Self.qrImage(for: url.absoluteString)
        expired = false
        expiryTask?.cancel()
        expiryTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Limits.pairingCodeExpiryMs))
            guard !Task.isCancelled else { return }
            self?.expired = true
            self?.qr = nil
        }
    }

    func cancel() {
        expiryTask?.cancel()
        expiryTask = nil
        pairing.endPairing()
    }

    static func qrImage(for text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cgImage = CIContext().createCGImage(output, from: output.extent)
        else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: output.extent.width, height: output.extent.height))
    }
}

struct PairingView: View {
    @ObservedObject var session: PairingSession
    static let qrSide: CGFloat = 260

    var body: some View {
        VStack(spacing: 16) {
            if session.expired {
                Text(Copy.codeExpired)
                    .font(.headline)
                    .frame(width: Self.qrSide, height: Self.qrSide)
                Button(Copy.newCode) { session.regenerate() }
                    .keyboardShortcut(.defaultAction)
            } else {
                Text(Copy.pairingInstructions)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                if let qr = session.qr {
                    Image(nsImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: Self.qrSide, height: Self.qrSide)
                        .accessibilityLabel(Copy.pairingTitle)
                }
            }
        }
        .padding(24)
        .frame(width: Self.qrSide + 48)
    }
}

/// Hosts PairingView in a plain AppKit window (the app has no Dock icon or main window).
@MainActor
final class PairingWindowController: NSObject, NSWindowDelegate {
    private let session: PairingSession
    private var window: NSWindow?

    init(pairing: PairingStore, fingerprint: String) {
        session = PairingSession(pairing: pairing, fingerprint: fingerprint)
    }

    var isVisible: Bool { window?.isVisible ?? false }

    func show() {
        session.regenerate()
        if window == nil {
            let window = NSWindow(contentViewController: NSHostingController(rootView: PairingView(session: session)))
            window.title = Copy.pairingTitle
            window.styleMask = [.titled, .closable]
            window.isReleasedWhenClosed = false
            window.delegate = self
            window.center()
            self.window = window
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func close() {
        window?.close()
    }

    func windowWillClose(_ notification: Notification) {
        session.cancel()
    }
}
