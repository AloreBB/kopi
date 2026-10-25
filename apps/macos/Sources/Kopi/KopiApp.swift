import AppKit
import Combine
import KopiCore
import KopiNet
import ServiceManagement
import SwiftUI

@main
struct KopiApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        MenuBarExtra {
            MenuContent(model: model, coordinator: model.coordinator)
        } label: {
            MenuBarLabel(coordinator: model.coordinator)
        }
        .menuBarExtraStyle(.menu)
    }
}

/// Owns the long-lived objects and the menu actions.
@MainActor
final class AppModel: ObservableObject {
    let coordinator: AppCoordinator
    private let pairing: PairingStore
    private let server: any PeerServing
    private let log = FileLog()
    private let pairingWindow: PairingWindowController?
    private var cancellables: Set<AnyCancellable> = []
    private var clock: Timer?

    @Published private(set) var launchAtLogin = SMAppService.mainApp.status == .enabled
    /// Ticks every second while there is a last item, so "hace N s" stays current.
    @Published private(set) var now = Date()

    init() {
        pairing = PairingStore(defaults: .standard, now: AppModel.nowMs)
        do {
            let identity = try Identity.loadOrCreate()
            server = PeerServer(
                identity: identity, pairing: pairing,
                serviceName: Host.current().localizedName ?? "Mac",
                queue: DispatchQueue(label: "\(Limits.bundleID).server"))
            pairingWindow = PairingWindowController(pairing: pairing, fingerprint: identity.fingerprint)
        } catch {
            log.event("identity_failed")
            server = OfflineServer()
            pairingWindow = nil
        }
        coordinator = AppCoordinator(
            watcher: ClipboardWatcher(), server: server,
            engine: SyncEngine(strategy: .lazy, now: AppModel.nowMs), log: log)
        coordinator.start()

        // A phone that just scanned the code connects: the pairing window is done.
        coordinator.$status
            .sink { [weak self] status in
                if case .connected = status { self?.pairingWindow?.close() }
            }
            .store(in: &cancellables)
        clock = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.coordinator.last != nil else { return }
                self.now = Date()
            }
        }
        // First run: nothing to sync until a phone is paired, so start there.
        if !isPaired {
            DispatchQueue.main.async { [weak self] in self?.showPairing() }
        }
    }

    var isPaired: Bool { pairing.pairedPhone != nil }
    var canPair: Bool { pairingWindow != nil }

    func showPairing() {
        pairingWindow?.show()
    }

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            log.event("login_item_failed")
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    func showLog() {
        log.touch()
        NSWorkspace.shared.open(log.url)
    }

    func unpair() {
        pairing.unpair()
        log.event("unpaired")
        // Drop the current session; the phone's certificate is no longer accepted.
        try? server.restart()
        objectWillChange.send()
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

struct MenuContent: View {
    @ObservedObject var model: AppModel
    @ObservedObject var coordinator: AppCoordinator

    var body: some View {
        Text(statusText)
        if coordinator.phoneOutdated {
            Text(Copy.phoneOutdated)
        }
        if let last = coordinator.last {
            Text(Copy.last(last.preview, secondsAgo: Int(model.now.timeIntervalSince(last.at))))
        }
        Divider()
        Button(Copy.pairPhone) { model.showPairing() }
            .disabled(!model.canPair)
        Toggle(Copy.openAtLogin, isOn: Binding(
            get: { model.launchAtLogin },
            set: { model.setLaunchAtLogin($0) }))
        Button(Copy.showLog) { model.showLog() }
        Button(Copy.unpair) { model.unpair() }
            .disabled(!model.isPaired)
        Divider()
        Button(Copy.quit) { NSApp.terminate(nil) }
            .keyboardShortcut("q")
    }

    private var statusText: String {
        switch coordinator.status {
        case .connected(let name): Copy.connected(to: name)
        case .waiting: Copy.waiting
        case .disconnected: Copy.disconnected
        }
    }
}

struct MenuBarLabel: View {
    @ObservedObject var coordinator: AppCoordinator

    var body: some View {
        let connected = if case .connected = coordinator.status { true } else { false }
        Image(nsImage: MenuBarIcon.image(filled: coordinator.pulse, dimmed: !connected))
    }
}

enum MenuBarIcon {
    static let dimmedAlpha: CGFloat = 0.4

    /// `doc.on.clipboard`, `.fill` while pulsing, drawn faded when not connected.
    /// Template images keep their alpha, so the fade survives menu bar tinting.
    static func image(filled: Bool, dimmed: Bool) -> NSImage {
        let name = filled ? "doc.on.clipboard.fill" : "doc.on.clipboard"
        guard let symbol = NSImage(systemSymbolName: name, accessibilityDescription: "Kopi") else {
            return NSImage()
        }
        guard dimmed else {
            symbol.isTemplate = true
            return symbol
        }
        let image = NSImage(size: symbol.size, flipped: false) { rect in
            symbol.draw(in: rect, from: .zero, operation: .sourceOver, fraction: dimmedAlpha)
            return true
        }
        image.isTemplate = true
        return image
    }
}

/// Stand-in when the Keychain identity cannot be loaded: never connects.
private final class OfflineServer: PeerServing, @unchecked Sendable {
    weak var delegate: PeerServerDelegate?
    var isConnected: Bool { false }
    func start() throws {}
    func restart() throws {}
    func send(_ message: KopiMessage) {}
    func sendImage(id: UUID, data: Data) {}
}
