import Foundation
import KopiNet

// Foundation-dependent helpers live here because the Command Line Tools ship
// _Testing_Foundation without its Swift module: a file importing both
// Foundation and Testing fails to compile.

func bytes(_ values: [UInt8]) -> Data {
    Data(values)
}

// Unique Keychain label per test run; never the real "dev.alorebb.kopi.identity".
func makeTestIdentityLabel() -> String {
    "dev.alorebb.kopi.test.\(UUID().uuidString)"
}

func makeTestDefaults() -> (defaults: UserDefaults, suite: String) {
    let suite = "dev.alorebb.kopi.test.\(UUID().uuidString)"
    return (UserDefaults(suiteName: suite)!, suite)
}

func removeTestDefaults(_ suite: String) {
    UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
}

func queryItems(_ url: URL) -> [String: String] {
    let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { _, last in last })
}

func scheme(_ url: URL) -> String? { url.scheme }
func host(_ url: URL) -> String? { url.host }

func isBase64URLWithoutPadding(_ s: String) -> Bool {
    s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
}

func base64URLDecodedCount(_ s: String) -> Int? {
    var b64 = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while b64.count % 4 != 0 { b64 += "=" }
    return Data(base64Encoded: b64)?.count
}
