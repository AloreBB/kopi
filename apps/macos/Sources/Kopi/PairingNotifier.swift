import Foundation
@preconcurrency import UserNotifications

/// Posts "Kopi se vinculó con <name>" with a Desvincular action after automatic pairing.
/// Best effort: an ad-hoc signed app may not be allowed to notify; then `unavailable`
/// runs and the menu bar pulse is the only signal. Never includes clipboard content.
final class PairingNotifier: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let categoryID = "dev.alorebb.kopi.paired"
    static let unpairActionID = "dev.alorebb.kopi.unpair"
    static let requestID = "dev.alorebb.kopi.paired"

    /// Runs on the main thread when the user picks Desvincular in the notification.
    @MainActor var onUnpair: (() -> Void)?

    private let center: UNUserNotificationCenter?

    override init() {
        // UNUserNotificationCenter traps outside an app bundle (e.g. `swift run`).
        center = Bundle.main.bundleIdentifier == nil ? nil : UNUserNotificationCenter.current()
        super.init()
        guard let center else { return }
        center.delegate = self
        let unpair = UNNotificationAction(
            identifier: Self.unpairActionID, title: Copy.unpairAction, options: [.destructive])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.categoryID, actions: [unpair], intentIdentifiers: []),
        ])
    }

    func announcePaired(name: String, unavailable: @escaping @Sendable () -> Void) {
        guard let center else { return unavailable() }
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            guard granted, error == nil else { return unavailable() }
            let content = UNMutableNotificationContent()
            content.title = Copy.pairedTitle(name)
            content.body = Copy.pairedBody
            content.categoryIdentifier = Self.categoryID
            let request = UNNotificationRequest(identifier: Self.requestID, content: content, trigger: nil)
            center.add(request) { error in
                if error != nil { unavailable() }
            }
        }
    }

    /// Removes the pairing notification once it no longer applies.
    func clearPaired() {
        center?.removeDeliveredNotifications(withIdentifiers: [Self.requestID])
    }

    // A menu bar app counts as frontmost often; show the banner anyway.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let unpair = response.actionIdentifier == Self.unpairActionID
        completionHandler()
        guard unpair else { return }
        DispatchQueue.main.async { [self] in
            MainActor.assumeIsolated { onUnpair?() }
        }
    }
}
