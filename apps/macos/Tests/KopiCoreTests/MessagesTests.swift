import Testing
import KopiCore

@Test(arguments: ["hello", "paired", "clip_text", "clip_text_sensitive", "clip_offer",
                  "clip_request", "clip_data", "clip_unavailable", "error_version_mismatch",
                  "error_not_paired"])
func fixtureRoundTrips(name: String) throws {
    let (original, message) = try decodedFixture(name)
    #expect(try jsonDictionariesEqual(original, reencoded(message)))
}

@Test func unknownTypeFailsToDecode() {
    #expect(throws: (any Error).self) { try decodeUnknownType() }
}

@Test func helloCarriesName() throws {
    let (_, message) = try decodedFixture("hello")
    #expect(message == .hello(Hello(protocolVersion: 1, app: "1.0.0", name: "Tecno Camon 40 Pro")))
}

@Test func helloWithoutNameFailsToDecode() {
    #expect(throws: (any Error).self) { try decodeHelloWithoutName() }
}

@Test func removedPairMessageFailsToDecode() {
    #expect(throws: (any Error).self) { try decodeRemovedPair() }
}
