import Testing
import KopiCore
import KopiNet

// Each test owns a server on an ephemeral localhost port plus two test identities
// (Mac and phone) that are deleted in deinit. Waits are bounded so a hang fails.
@Suite(.serialized) final class PeerServerTests {
    let h: PeerHarness

    init() throws {
        h = try PeerHarness()
    }

    deinit {
        h.tearDown()
    }

    @Test func pairingHandshakeSucceeds() async throws {
        #expect(try await h.start())
        let code = h.openPairingWindow()
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(helloV1())
        client.send(.pair(Pair(code: code, name: "Test Phone")))
        #expect(await client.next() == .text(helloV1()))
        #expect(await client.next() == .text(.paired(Paired(name: h.serviceName))))
        #expect(h.pairedFingerprint == h.phone.fingerprint)
        #expect(await eventually { self.h.delegate.connected == ["Test Phone"] })
        #expect(h.server.isConnected)
    }

    @Test func pairedClientReconnects() async throws {
        h.markPhonePaired(name: "Tecno")
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(helloV1())
        #expect(await client.next() == .text(helloV1()))
        #expect(await eventually { self.h.delegate.connected == ["Tecno"] })
        #expect(h.server.isConnected)
    }

    @Test func unknownClientRejected() async throws {
        #expect(try await h.start())
        let client = h.makeClient()
        client.start()
        client.send(helloV1())
        #expect(await client.waitClosed())
        #expect(await client.next(timeout: 0.3) == nil)
        #expect(h.delegate.connected.isEmpty)
        #expect(!h.server.isConnected)
    }

    @Test func wrongCodeRejected() async throws {
        #expect(try await h.start())
        _ = h.openPairingWindow()
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(helloV1())
        client.send(.pair(Pair(code: "AAAAAAAAAAAAAAAAAAAAAA", name: "Test Phone")))
        #expect(await client.next() == .text(helloV1()))
        #expect(await client.next() == .text(.error(KopiError(code: .invalidCode))))
        #expect(await client.waitClosed())
        #expect(h.pairedFingerprint == nil)
        #expect(h.delegate.connected.isEmpty)
    }

    @Test func unpairedClientWithoutPairGetsNotPaired() async throws {
        #expect(try await h.start())
        _ = h.openPairingWindow()
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(helloV1())
        client.send(.clip(makeTestClip()))
        #expect(await client.next() == .text(helloV1()))
        #expect(await client.next() == .text(.error(KopiError(code: .notPaired))))
        #expect(await client.waitClosed())
        #expect(h.delegate.connected.isEmpty)
        #expect(h.delegate.received.isEmpty)
    }

    @Test func versionMismatchRejected() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(.hello(Hello(protocolVersion: 2, app: "2.0.0")))
        #expect(await client.next() == .text(.error(KopiError(code: .versionMismatch))))
        #expect(await client.waitClosed())
        #expect(h.delegate.connected.isEmpty)
    }

    @Test func textRoundTrip() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(helloV1())
        #expect(await client.next() == .text(helloV1()))
        let clip = makeTestClip()
        client.send(.clip(clip))
        #expect(await eventually { self.h.delegate.received == [.clip(clip)] })

        // And back: Mac to phone.
        let reply = makeTestClip()
        h.server.send(.clip(reply))
        #expect(await client.next() == .text(.clip(reply)))
    }

    @Test func imageChunksArrive() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(helloV1())
        #expect(await client.next() == .text(helloV1()))
        #expect(await eventually { self.h.server.isConnected })

        let id = makeTestClip().id
        h.sendImage(id: id, size: 600_000)
        #expect(await client.next() == .text(.clipData(ClipData(id: id, size: 600_000))))
        // 600_000 = 262_144 + 262_144 + 75_712.
        #expect(await client.next() == .binary(id: id, length: 262_144))
        #expect(await client.next() == .binary(id: id, length: 262_144))
        #expect(await client.next() == .binary(id: id, length: 75_712))
        #expect(await client.next(timeout: 0.3) == nil)
    }

    @Test func clientImageChunkReachesDelegate() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(helloV1())
        #expect(await client.next() == .text(helloV1()))
        let id = makeTestClip().id
        client.sendBinary(id: id, length: 1_000)
        #expect(await eventually { self.h.delegate.chunks == [.init(id: id, length: 1_000)] })
    }

    @Test func newSessionReplacesOld() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let first = h.makeClient()
        #expect(await first.connect())
        first.send(helloV1())
        #expect(await first.next() == .text(helloV1()))

        let second = h.makeClient()
        #expect(await second.connect())
        second.send(helloV1())
        #expect(await second.next() == .text(helloV1()))
        #expect(await first.waitClosed())
        #expect(await eventually { self.h.delegate.connected.count == 2 })
        #expect(h.server.isConnected)

        let clip = makeTestClip()
        h.server.send(.clip(clip))
        #expect(await second.next() == .text(.clip(clip)))
    }
}

// Restart runs with Bonjour advertising on, as in the app (Review Focus 1).
@Suite(.serialized) final class PeerServerRestartTests {
    let h: PeerHarness

    init() throws {
        h = try PeerHarness(advertise: true)
    }

    deinit {
        h.tearDown()
    }

    @Test func restartAcceptsNewConnection() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let port = h.server.listeningPort
        let before = h.makeClient()
        #expect(await before.connect())
        before.send(helloV1())
        #expect(await before.next() == .text(helloV1()))

        #expect(try await h.restart())
        #expect(h.server.listeningPort == port)
        #expect(await before.waitClosed())
        #expect(await eventually { self.h.delegate.disconnects == 1 })

        // Measured from the moment the server reports ready again (rebind retries excluded).
        let elapsed = await ContinuousClock().measure {
            let after = h.makeClient()
            #expect(await after.connect())
            after.send(helloV1())
            #expect(await after.next() == .text(helloV1()))
        }
        #expect(elapsed < .seconds(2))
        #expect(await eventually { self.h.delegate.connected.count == 2 })
    }
}

@Suite(.serialized) final class PeerServerIdleTests {
    let h: PeerHarness

    init() throws {
        h = try PeerHarness(idleTimeout: 0.6)
    }

    deinit {
        h.tearDown()
    }

    @Test func pingsKeepSessionAlive() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let client = h.makeClient(autoReplyPing: true)
        #expect(await client.connect())
        client.send(helloV1())
        #expect(await client.next() == .text(helloV1()))
        #expect(await eventually { self.h.server.isConnected })
        try await Task.sleep(nanoseconds: 1_500_000_000)
        #expect(h.server.isConnected)
        #expect(!client.isClosed)
        #expect(h.delegate.disconnects == 0)
    }

    @Test func silentSessionIsClosed() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let client = h.makeClient(autoReplyPing: false)
        #expect(await client.connect())
        client.send(helloV1())
        #expect(await client.next() == .text(helloV1()))
        #expect(await client.waitClosed(timeout: 3))
        #expect(await eventually { self.h.delegate.disconnects == 1 })
        #expect(!h.server.isConnected)
    }

    @Test func clientThatNeverSendsHelloIsClosed() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        #expect(await client.waitClosed(timeout: 3))
        #expect(h.delegate.connected.isEmpty)
    }
}

// The listener retries with backoff when its port is busy (bind failure after restart or
// wake), instead of staying dead.
@Suite(.serialized) final class PeerServerRecoveryTests {
    @Test func listenerRecoversWhenPortFreesUp() async throws {
        let holder = try PortHolder()
        let held = try #require(await holder.port())
        let h = try PeerHarness(port: held)
        defer {
            holder.release()
            h.tearDown()
        }
        h.markPhonePaired()

        try h.server.start()
        #expect(await !eventually(timeout: 0.5) { h.server.listeningPort != nil })

        holder.release()
        #expect(await eventually(timeout: 3) { h.server.listeningPort == held })

        let client = h.makeClient()
        #expect(await client.connect())
        client.send(helloV1())
        #expect(await client.next() == .text(helloV1()))
        #expect(await eventually { h.delegate.connected.count == 1 })
    }
}
