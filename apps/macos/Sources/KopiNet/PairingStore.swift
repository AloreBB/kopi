import Foundation
import KopiCore
import Security

enum KopiNetConstants {
    static let bundleID = Limits.bundleID
}

// One-time pairing code, QR URL and the paired phone's fingerprint.
// The code lives only in memory; the paired phone is persisted in UserDefaults.
// Thread-safe: the menu (main thread) and the server queue both use it.
public final class PairingStore: @unchecked Sendable {
    static let fingerprintKey = "pairedPhone.fingerprint"
    static let nameKey = "pairedPhone.name"
    static let codeByteCount = 16

    private let defaults: UserDefaults
    private let now: () -> Int64
    private let lock = NSLock()
    private var code: String?
    private var issuedAt: Int64 = 0

    public init(defaults: UserDefaults, now: @escaping () -> Int64) {
        self.defaults = defaults
        self.now = now
    }

    /// Issues a fresh code (invalidating any previous one) and returns the QR URL.
    public func beginPairing(host: String, fingerprint: String) -> URL {
        let newCode = Self.randomCode()
        lock.withLock {
            code = newCode
            issuedAt = now()
        }
        var components = URLComponents()
        components.scheme = "kopi"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "v", value: String(Limits.protocolVersion)),
            URLQueryItem(name: "host", value: host),
            URLQueryItem(name: "port", value: String(Limits.serverPort)),
            URLQueryItem(name: "fp", value: fingerprint),
            URLQueryItem(name: "code", value: newCode),
        ]
        return components.url!
    }

    /// True exactly once for the current code, while the pairing window is open.
    public func consume(code candidate: String) -> Bool {
        lock.withLock {
            guard windowOpenLocked, let code else { return false }
            guard constantTimeEquals(Array(code.utf8), Array(candidate.utf8)) else { return false }
            self.code = nil
            return true
        }
    }

    /// Invalidates the current code (the pairing window was closed).
    public func endPairing() {
        lock.withLock { code = nil }
    }

    public var isPairingWindowOpen: Bool {
        lock.withLock { windowOpenLocked }
    }

    private var windowOpenLocked: Bool {
        code != nil && now() - issuedAt < Limits.pairingCodeExpiryMs
    }

    public var pairedPhone: (fingerprint: String, name: String)? {
        guard let fingerprint = defaults.string(forKey: Self.fingerprintKey),
              let name = defaults.string(forKey: Self.nameKey)
        else { return nil }
        return (fingerprint, name)
    }

    /// Pairing a new phone replaces the previous one.
    public func setPaired(fingerprint: String, name: String) {
        defaults.set(fingerprint, forKey: Self.fingerprintKey)
        defaults.set(name, forKey: Self.nameKey)
    }

    public func unpair() {
        defaults.removeObject(forKey: Self.fingerprintKey)
        defaults.removeObject(forKey: Self.nameKey)
    }

    /// en0 first; otherwise the first active non-loopback IPv4 address.
    public static func primaryIPv4() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }

        var candidates: [(name: String, address: String)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            let flags = Int32(entry.ifa_flags)
            guard let addr = entry.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0
            else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                              nil, 0, NI_NUMERICHOST) == 0
            else { continue }
            let address = String(decoding: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            candidates.append((String(cString: entry.ifa_name), address))
        }
        return (candidates.first { $0.name == "en0" } ?? candidates.first)?.address
    }

    static func randomCode() -> String {
        var bytes = [UInt8](repeating: 0, count: codeByteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private func constantTimeEquals(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in a.indices { diff |= a[i] ^ b[i] }
        return diff == 0
    }
}
