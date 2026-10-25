import Foundation
import KopiCore

enum KopiNetConstants {
    static let bundleID = Limits.bundleID
}

/// The paired phone's certificate fingerprint and display name, persisted in UserDefaults.
/// Pairing is trust-on-first-use: while nothing is stored, the server pairs the first
/// phone that completes `hello`. Thread-safe: the menu (main thread) and the server
/// queue both use it.
public final class PairingStore: @unchecked Sendable {
    static let fingerprintKey = "pairedPhone.fingerprint"
    static let nameKey = "pairedPhone.name"

    private let defaults: UserDefaults
    private let lock = NSLock()

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    public var pairedPhone: (fingerprint: String, name: String)? {
        lock.withLock {
            guard let fingerprint = defaults.string(forKey: Self.fingerprintKey),
                  let name = defaults.string(forKey: Self.nameKey)
            else { return nil }
            return (fingerprint, name)
        }
    }

    /// Pairing a new phone replaces the previous one.
    public func setPaired(fingerprint: String, name: String) {
        lock.withLock {
            defaults.set(fingerprint, forKey: Self.fingerprintKey)
            defaults.set(name, forKey: Self.nameKey)
        }
    }

    public func unpair() {
        lock.withLock {
            defaults.removeObject(forKey: Self.fingerprintKey)
            defaults.removeObject(forKey: Self.nameKey)
        }
    }
}
