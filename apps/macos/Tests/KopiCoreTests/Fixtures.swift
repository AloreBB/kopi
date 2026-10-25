import Foundation
import KopiCore

// Locates the shared JSON fixtures at <repo>/protocol/fixtures/<name>.json,
// relative to this file inside apps/macos/Tests/KopiCoreTests.
func loadFixture(_ name: String) -> Data {
    let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    let url = testsDir
        .appendingPathComponent("../../../../protocol/fixtures")
        .appendingPathComponent("\(name).json")
        .standardized
    return try! Data(contentsOf: url)
}

// JSON mechanics live here (not in the test file) because the Command Line
// Tools ship _Testing_Foundation without its Swift module: any file importing
// both Foundation and Testing fails to compile. Keeping Foundation out of the
// Testing files works around that.
func decodedFixture(_ name: String) throws -> (original: Data, message: KopiMessage) {
    let data = loadFixture(name)
    return (data, try JSONDecoder().decode(KopiMessage.self, from: data))
}

func reencoded(_ message: KopiMessage) throws -> Data {
    try JSONEncoder().encode(message)
}

func jsonDictionariesEqual(_ a: Data, _ b: Data) throws -> Bool {
    try JSONSerialization.jsonObject(with: a) as! NSDictionary
        == JSONSerialization.jsonObject(with: b) as! NSDictionary
}

func decodeUnknownType() throws {
    _ = try JSONDecoder().decode(
        KopiMessage.self, from: Data(#"{"type":"nope"}"#.utf8))
}
