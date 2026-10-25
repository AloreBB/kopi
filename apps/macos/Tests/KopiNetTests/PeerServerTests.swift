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

    @Test func autoPairWhenUnpaired() async throws {
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(phoneHello(name: "Test Phone"))
        #expect(await client.next() == .text(h.macHello()))
        #expect(await client.next() == .text(.paired(Paired(name: h.serviceName))))
        #expect(h.pairedFingerprint == h.phone.fingerprint)
        #expect(h.pairedName == "Test Phone")
        #expect(await eventually { self.h.delegate.paired == ["Test Phone"] })
        #expect(await eventually { self.h.delegate.connected == ["Test Phone"] })
        #expect(h.server.isConnected)
    }

    @Test func pairedClientReconnects() async throws {
        h.markPhonePaired(name: "Tecno")
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(phoneHello(name: "Tecno"))
        #expect(await client.next() == .text(h.macHello()))
        #expect(await eventually { self.h.delegate.connected == ["Tecno"] })
        #expect(await client.next(timeout: 0.3) == nil)
        #expect(h.delegate.paired.isEmpty)
        #expect(h.server.isConnected)
    }

    @Test func helloCarriesName() async throws {
        h.markPhonePaired(name: "Nombre viejo")
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(phoneHello(name: "Nombre nuevo"))
        guard case .text(.hello(let reply))? = await client.next() else {
            Issue.record("expected hello")
            return
        }
        #expect(reply.name == h.serviceName)
        #expect(await eventually { self.h.delegate.connected == ["Nombre nuevo"] })
        #expect(h.pairedName == "Nombre nuevo")
    }

    @Test func pairedRejectsOtherClient() async throws {
        h.markPaired(fingerprint: String(repeating: "ab", count: 32), name: "Otro")
        #expect(try await h.start())
        let client = h.makeClient()
        client.start()
        client.send(phoneHello())
        #expect(await client.waitClosed())
        #expect(await client.next(timeout: 0.3) == nil)
        #expect(h.delegate.connected.isEmpty)
        #expect(h.pairedName == "Otro")
        #expect(!h.server.isConnected)
    }

    @Test func unpairAllowsNewPhone() async throws {
        #expect(try await h.start())
        let first = h.makeClient()
        #expect(await first.connect())
        first.send(phoneHello(name: "Primero"))
        #expect(await first.next() == .text(h.macHello()))
        #expect(await first.next() == .text(.paired(Paired(name: h.serviceName))))

        let other = try h.makeOtherPhone()
        let rejected = h.makeClient(identity: other)
        rejected.start()
        rejected.send(phoneHello(name: "Segundo"))
        #expect(await rejected.waitClosed())
        #expect(h.pairedFingerprint == h.phone.fingerprint)

        h.server.unpair()
        #expect(h.pairedFingerprint == nil)
        #expect(await first.waitClosed())
        #expect(await eventually { self.h.delegate.disconnects == 1 })

        let second = h.makeClient(identity: other)
        #expect(await second.connect())
        second.send(phoneHello(name: "Segundo"))
        #expect(await second.next() == .text(h.macHello()))
        #expect(await second.next() == .text(.paired(Paired(name: h.serviceName))))
        #expect(h.pairedFingerprint == other.fingerprint)
        #expect(await eventually { self.h.delegate.connected == ["Primero", "Segundo"] })
    }

    // Two phones pass TLS while the Mac is unpaired: the first hello wins, the other is
    // turned away with not_paired and nothing is replaced.
    @Test func secondUnpairedPhoneGetsNotPaired() async throws {
        #expect(try await h.start())
        let first = h.makeClient()
        let other = try h.makeOtherPhone()
        let second = h.makeClient(identity: other)
        #expect(await first.connect())
        #expect(await second.connect())
        first.send(phoneHello(name: "Primero"))
        #expect(await first.next() == .text(h.macHello()))
        #expect(await first.next() == .text(.paired(Paired(name: h.serviceName))))

        second.send(phoneHello(name: "Segundo"))
        #expect(await second.next() == .text(.error(KopiError(code: .notPaired))))
        #expect(await second.waitClosed())
        #expect(h.pairedFingerprint == h.phone.fingerprint)
        #expect(h.delegate.connected == ["Primero"])
        #expect(h.server.isConnected)
    }

    @Test func versionMismatchRejected() async throws {
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(.hello(Hello(protocolVersion: 2, app: "2.0.0", name: "Test Phone")))
        #expect(await client.next() == .text(.error(KopiError(code: .versionMismatch))))
        #expect(await client.waitClosed())
        #expect(h.delegate.connected.isEmpty)
        // A phone that cannot talk to this Mac must not claim it.
        #expect(h.pairedFingerprint == nil)
    }

    @Test func textRoundTrip() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let client = h.makeClient()
        #expect(await client.connect())
        client.send(phoneHello())
        #expect(await client.next() == .text(h.macHello()))
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
        client.send(phoneHello())
        #expect(await client.next() == .text(h.macHello()))
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
        client.send(phoneHello())
        #expect(await client.next() == .text(h.macHello()))
        let id = makeTestClip().id
        client.sendBinary(id: id, length: 1_000)
        #expect(await eventually { self.h.delegate.chunks == [.init(id: id, length: 1_000)] })
    }

    @Test func newSessionReplacesOld() async throws {
        h.markPhonePaired()
        #expect(try await h.start())
        let first = h.makeClient()
        #expect(await first.connect())
        first.send(phoneHello())
        #expect(await first.next() == .text(h.macHello()))

        let second = h.makeClient()
        #expect(await second.connect())
        second.send(phoneHello())
        #expect(await second.next() == .text(h.macHello()))
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
        before.send(phoneHello())
        #expect(await before.next() == .text(h.macHello()))

        #expect(try await h.restart())
        #expect(h.server.listeningPort == port)
        #expect(await before.waitClosed())
        #expect(await eventually { self.h.delegate.disconnects == 1 })

        // Measured from the moment the server reports ready again (rebind retries excluded).
        let elapsed = await ContinuousClock().measure {
            let after = h.makeClient()
            #expect(await after.connect())
            after.send(phoneHello())
            #expect(await after.next() == .text(h.macHello()))
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
        client.send(phoneHello())
        #expect(await client.next() == .text(h.macHello()))
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
        client.send(phoneHello())
        #expect(await client.next() == .text(h.macHello()))
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
        client.send(phoneHello())
        #expect(await client.next() == .text(h.macHello()))
        #expect(await eventually { h.delegate.connected.count == 1 })
    }
}
