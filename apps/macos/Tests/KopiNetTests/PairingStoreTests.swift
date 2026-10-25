import Testing
import KopiNet

private let fp = String(repeating: "ab", count: 32)
private let otherFp = String(repeating: "cd", count: 32)

@Suite final class PairingStoreTests {
    let testDefaults = makeTestDefaults()

    deinit {
        removeTestDefaults(testDefaults.suite)
    }

    func makeStore() -> PairingStore {
        PairingStore(defaults: testDefaults.defaults)
    }

    @Test func startsUnpaired() {
        #expect(makeStore().pairedPhone == nil)
    }

    @Test func pairedPhonePersists() {
        let store = makeStore()
        store.setPaired(fingerprint: fp, name: "Tecno Camon 40 Pro")
        let reloaded = makeStore()
        #expect(reloaded.pairedPhone?.fingerprint == fp)
        #expect(reloaded.pairedPhone?.name == "Tecno Camon 40 Pro")
    }

    @Test func pairingANewPhoneReplacesThePrevious() {
        let store = makeStore()
        store.setPaired(fingerprint: fp, name: "Viejo")
        store.setPaired(fingerprint: otherFp, name: "Nuevo")
        #expect(makeStore().pairedPhone?.fingerprint == otherFp)
        #expect(makeStore().pairedPhone?.name == "Nuevo")
    }

    @Test func unpairClearsThePhone() {
        let store = makeStore()
        store.setPaired(fingerprint: fp, name: "Tecno")
        store.unpair()
        #expect(store.pairedPhone == nil)
        #expect(makeStore().pairedPhone == nil)
    }
}
