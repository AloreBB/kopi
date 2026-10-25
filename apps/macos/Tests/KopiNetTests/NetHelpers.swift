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
