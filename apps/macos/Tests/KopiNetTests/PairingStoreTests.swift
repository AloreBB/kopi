import Testing
import KopiNet

private let fp = String(repeating: "ab", count: 32)

@Suite final class PairingStoreTests {
    let testDefaults = makeTestDefaults()
    var clock: Int64 = 1_000_000

    deinit {
        removeTestDefaults(testDefaults.suite)
    }

    func makeStore() -> PairingStore {
        PairingStore(defaults: testDefaults.defaults, now: { [unowned self] in self.clock })
    }

    @Test func pairingUrlFormat() {
        let store = makeStore()
        let url = store.beginPairing(host: "192.168.1.20", fingerprint: fp)
        #expect(scheme(url) == "kopi")
        #expect(host(url) == "pair")
        let items = queryItems(url)
        #expect(items["v"] == "1")
        #expect(items["host"] == "192.168.1.20")
        #expect(items["port"] == "47474")
        #expect(items["fp"] == fp)
        let code = items["code"] ?? ""
        #expect(code.count == 22)
        #expect(isBase64URLWithoutPadding(code))
        #expect(base64URLDecodedCount(code) == 16)
        #expect(store.isPairingWindowOpen)
    }

    @Test func closingThePairingWindowInvalidatesTheCode() {
        let store = makeStore()
        let code = queryItems(store.beginPairing(host: "10.0.0.2", fingerprint: fp))["code"]!
        store.endPairing()
        #expect(!store.isPairingWindowOpen)
        #expect(!store.consume(code: code))
    }

    @Test func codeIsSingleUse() {
        let store = makeStore()
        let code = queryItems(store.beginPairing(host: "10.0.0.2", fingerprint: fp))["code"]!
        #expect(store.consume(code: code))
        #expect(!store.consume(code: code))
        #expect(!store.isPairingWindowOpen)
    }

    @Test func codeExpires() {
        let store = makeStore()
        let code = queryItems(store.beginPairing(host: "10.0.0.2", fingerprint: fp))["code"]!
        clock += 300_000
        #expect(!store.isPairingWindowOpen)
        #expect(!store.consume(code: code))
    }

    @Test func codeValidJustBeforeExpiry() {
        let store = makeStore()
        let code = queryItems(store.beginPairing(host: "10.0.0.2", fingerprint: fp))["code"]!
        clock += 299_999
        #expect(store.consume(code: code))
    }

    @Test func wrongCodeIsRejected() {
        let store = makeStore()
        _ = store.beginPairing(host: "10.0.0.2", fingerprint: fp)
        #expect(!store.consume(code: "AAAAAAAAAAAAAAAAAAAAAA"))
        #expect(!store.consume(code: ""))
    }

    @Test func newPairingInvalidatesOldCode() {
        let store = makeStore()
        let old = queryItems(store.beginPairing(host: "10.0.0.2", fingerprint: fp))["code"]!
        let new = queryItems(store.beginPairing(host: "10.0.0.2", fingerprint: fp))["code"]!
        #expect(old != new)
        #expect(!store.consume(code: old))
        #expect(store.consume(code: new))
    }

    @Test func pairedPhonePersists() {
        let store = makeStore()
        #expect(store.pairedPhone == nil)
        store.setPaired(fingerprint: fp, name: "Tecno Camon 40 Pro")
        let reloaded = makeStore()
        #expect(reloaded.pairedPhone?.fingerprint == fp)
        #expect(reloaded.pairedPhone?.name == "Tecno Camon 40 Pro")
        reloaded.unpair()
        #expect(makeStore().pairedPhone == nil)
    }

    @Test func primaryIPv4IsDottedQuadWhenPresent() {
        if let ip = PairingStore.primaryIPv4() {
            #expect(ip.split(separator: ".").count == 4)
            #expect(ip != "127.0.0.1")
        }
    }
}
