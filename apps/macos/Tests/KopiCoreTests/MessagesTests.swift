import Testing
import KopiCore

@Test(arguments: ["hello", "pair", "paired", "clip_text", "clip_text_sensitive", "clip_offer",
                  "clip_request", "clip_data", "clip_unavailable", "error_version_mismatch",
                  "error_not_paired", "error_invalid_code"])
func fixtureRoundTrips(name: String) throws {
    let (original, message) = try decodedFixture(name)
    #expect(try jsonDictionariesEqual(original, reencoded(message)))
}

@Test func unknownTypeFailsToDecode() {
    #expect(throws: (any Error).self) { try decodeUnknownType() }
}
