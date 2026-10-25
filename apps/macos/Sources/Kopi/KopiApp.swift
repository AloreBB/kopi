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
    private let log = FileLog()
    private let notifier = PairingNotifier()
    private var clock: Timer?

    @Published private(set) var launchAtLogin = SMAppService.mainApp.status == .enabled
    /// Ticks every second while there is a last item, so "hace N s" stays current.
    @Published private(set) var now = Date()

    init() {
        let server: any PeerServing
        do {
            let identity = try Identity.loadOrCreate()
            server = PeerServer(
                identity: identity, pairing: PairingStore(defaults: .standard),
                serviceName: Host.current().localizedName ?? "Mac",
                queue: DispatchQueue(label: "\(Limits.bundleID).server"))
        } catch {
            log.event("identity_failed")
            server = OfflineServer()
        }
        coordinator = AppCoordinator(
            watcher: ClipboardWatcher(), server: server,
            engine: SyncEngine(strategy: .lazy, now: AppModel.nowMs), log: log)
        // Pairing is automatic: tell the user which phone it was and offer the undo.
        coordinator.onAutoPaired = { [notifier, log] name in
            notifier.announcePaired(name: name) { log.event("notify_unavailable") }
        }
        notifier.onUnpair = { [weak self] in self?.unpair() }
        coordinator.start()

        clock = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.coordinator.last != nil else { return }
                self.now = Date()
            }
        }
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
        coordinator.unpair()
        notifier.clearPaired()
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
        Button(Copy.unpair) { model.unpair() }
            .disabled(coordinator.pairedName == nil)
        Toggle(Copy.openAtLogin, isOn: Binding(
            get: { model.launchAtLogin },
            set: { model.setLaunchAtLogin($0) }))
        Button(Copy.showLog) { model.showLog() }
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
    var pairedPhoneName: String? { nil }
    func start() throws {}
    func restart() throws {}
    func send(_ message: KopiMessage) {}
    func sendImage(id: UUID, data: Data) {}
    func unpair() {}
}
