import Testing
import KopiNet

@Test func fingerprintIsLowerHex64() {
    #expect(Identity.fingerprint(der: bytes([1, 2, 3]))
        == "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
}

// The legacy login keychain fails concurrent key generation ("failed to generate CDSA key").
@Suite(.serialized) final class KeychainIdentityTests {
    let label = makeTestIdentityLabel()

    deinit {
        deleteTestIdentity(label: label)
    }

    @Test func loadOrCreateIsStable() throws {
        let first = try loadTestIdentity(label: label)
        let second = try loadTestIdentity(label: label)
        #expect(first.fingerprint == second.fingerprint)
        #expect(first.fingerprint.count == 64)
        #expect(first.fingerprint == Identity.fingerprint(der: first.certificateDER))
    }

    @Test func deleteRemovesIdentity() throws {
        let first = try loadTestIdentity(label: label)
        deleteTestIdentity(label: label)
        let second = try loadTestIdentity(label: label)
        #expect(first.fingerprint != second.fingerprint)
    }
}
