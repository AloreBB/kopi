import Foundation
import KopiCore
import KopiNet
import Network
import Security

// Foundation/Network-dependent test support for PeerServerTests (see NetHelpers.swift
// for why test files cannot import Foundation and Testing together).

// Concurrent key generation in the login keychain fails (-25300), and suites run in
// parallel, so every test-side keychain mutation goes through this lock.
private let keychainLock = NSLock()

func loadTestIdentity(label: String) throws -> LocalIdentity {
    keychainLock.lock()
    defer { keychainLock.unlock() }
    return try Identity.loadOrCreate(label: label)
}

func deleteTestIdentity(label: String) {
    keychainLock.lock()
    defer { keychainLock.unlock() }
    try? Identity.delete(label: label)
}

/// Polls `condition` until it holds or `timeout` elapses, so a hang fails instead of blocking.
func eventually(timeout: TimeInterval = 2, _ condition: () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if condition() { return true }
        try? await Task.sleep(nanoseconds: 10_000_000)
    }
    return condition()
}

func helloV1() -> KopiMessage {
    .hello(Hello(protocolVersion: 1, app: "1.0.0"))
}

func makeTestClip() -> Clip {
    Clip(id: UUID(), ts: 1_761_400_000_000, kind: .text, text: "hola", sensitive: false)
}

final class RecordingDelegate: PeerServerDelegate, @unchecked Sendable {
    struct Chunk: Equatable {
        let id: UUID
        let length: Int
    }

    private let lock = NSLock()
    private var _connected: [String] = []
    private var _disconnects = 0
    private var _received: [KopiMessage] = []
    private var _chunks: [Chunk] = []

    var connected: [String] { lock.withLock { _connected } }
    var disconnects: Int { lock.withLock { _disconnects } }
    var received: [KopiMessage] { lock.withLock { _received } }
    var chunks: [Chunk] { lock.withLock { _chunks } }

    func peerConnected(name: String) { lock.withLock { _connected.append(name) } }
    func peerDisconnected() { lock.withLock { _disconnects += 1 } }
    func received(_ message: KopiMessage) { lock.withLock { _received.append(message) } }
    func receivedImageChunk(id: UUID, bytes: Data) {
        lock.withLock { _chunks.append(Chunk(id: id, length: bytes.count)) }
    }
}

/// A phone stand-in: WebSocket over mutual TLS with its own identity, pinning the Mac.
final class TestPhoneClient: @unchecked Sendable {
    enum Frame: Equatable {
        case text(KopiMessage)
        case binary(id: UUID, length: Int)
        case undecodable
    }

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.alorebb.kopi.test.phone")
    private let lock = NSLock()
    private var frames: [Frame] = []
    private var _isReady = false
    private var _isClosed = false

    var isReady: Bool { lock.withLock { _isReady } }
    var isClosed: Bool { lock.withLock { _isClosed } }

    init(identity: LocalIdentity, serverFingerprint: String, port: UInt16, autoReplyPing: Bool = true) {
        let tls = NWProtocolTLS.Options()
        let sec = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_set_local_identity(sec, sec_identity_create(identity.secIdentity)!)
        sec_protocol_options_set_verify_block(sec, { _, trust, complete in
            complete(PeerTLS.leafFingerprint(trust) == serverFingerprint)
        }, queue)
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = autoReplyPing
        ws.maximumMessageSize = 16 * 1024 * 1024
        parameters.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        connection = NWConnection(
            to: .url(URL(string: "wss://127.0.0.1:\(port)/")!), using: parameters)
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.lock.withLock { self._isReady = true }
                self.receive()
            case .failed, .cancelled:
                self.lock.withLock { self._isClosed = true }
            case .waiting(.tls):
                // A rejected TLS handshake parks the connection in .waiting to retry.
                self.lock.withLock { self._isClosed = true }
                self.connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    /// Starts and waits for TLS + WebSocket to be ready.
    func connect(timeout: TimeInterval = 2) async -> Bool {
        start()
        return await eventually(timeout: timeout) { isReady }
    }

    func send(_ message: KopiMessage) {
        let data = try! JSONEncoder().encode(message)
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .idempotent)
    }

    func sendBinary(id: UUID, length: Int) {
        var data = withUnsafeBytes(of: id.uuid) { Data($0) }
        data.append(Data(repeating: 0xAB, count: length))
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binary", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .idempotent)
    }

    /// The next frame, or nil if none arrives within `timeout`.
    func next(timeout: TimeInterval = 2) async -> Frame? {
        let arrived = await eventually(timeout: timeout) { lock.withLock { !frames.isEmpty } }
        guard arrived else { return nil }
        return lock.withLock { frames.removeFirst() }
    }

    func waitClosed(timeout: TimeInterval = 2) async -> Bool {
        await eventually(timeout: timeout) { isClosed }
    }

    func close() {
        connection.cancel()
    }

    private func receive() {
        connection.receiveMessage { [weak self] content, context, isComplete, error in
            guard let self else { return }
            let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                as? NWProtocolWebSocket.Metadata
            // A server-side close shows up as a receive error, a close frame, or end of stream.
            if error != nil || metadata?.opcode == .close || (content == nil && isComplete && metadata == nil) {
                self.lock.withLock { self._isClosed = true }
                self.connection.cancel()
                return
            }
            let data = content ?? Data()
            switch metadata?.opcode {
            case .text:
                let frame = (try? JSONDecoder().decode(KopiMessage.self, from: data))
                    .map(Frame.text) ?? .undecodable
                self.lock.withLock { self.frames.append(frame) }
            case .binary:
                var frame = Frame.undecodable
                if data.count >= 16 {
                    let id = data.prefix(16).withUnsafeBytes { raw in
                        UUID(uuid: raw.loadUnaligned(as: uuid_t.self))
                    }
                    frame = .binary(id: id, length: data.count - 16)
                }
                self.lock.withLock { self.frames.append(frame) }
            default:
                break
            }
            self.receive()
        }
    }
}

/// Holds a TCP port with a plain listener so another bind to it fails until `release()`.
final class PortHolder: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "dev.alorebb.kopi.test.holder")

    init() throws {
        listener = try NWListener(using: .tcp)
        listener.newConnectionHandler = { $0.cancel() }
        listener.start(queue: queue)
    }

    /// Waits for the ephemeral port the holder bound.
    func port(timeout: TimeInterval = 2) async -> UInt16? {
        _ = await eventually(timeout: timeout) { listener.port?.rawValue ?? 0 != 0 }
        return listener.port?.rawValue
    }

    func release() {
        listener.cancel()
    }
}

/// One Mac server plus the identities, defaults and delegate it needs; cleans up after itself.
final class PeerHarness: @unchecked Sendable {
    let macLabel = makeTestIdentityLabel()
    let phoneLabel = makeTestIdentityLabel()
    let mac: LocalIdentity
    let phone: LocalIdentity
    let testDefaults = makeTestDefaults()
    let queue = DispatchQueue(label: "dev.alorebb.kopi.test.server")
    let serviceName = "Kopi Test \(UUID().uuidString.prefix(8))"
    let pairing: PairingStore
    let server: PeerServer
    let delegate = RecordingDelegate()
    private var clients: [TestPhoneClient] = []

    init(idleTimeout: TimeInterval = 75, advertise: Bool = false, port: UInt16 = 0) throws {
        do {
            mac = try loadTestIdentity(label: macLabel)
            phone = try loadTestIdentity(label: phoneLabel)
        } catch {
            deleteTestIdentity(label: macLabel)
            deleteTestIdentity(label: phoneLabel)
            throw error
        }
        pairing = PairingStore(
            defaults: testDefaults.defaults,
            now: { Int64(Date().timeIntervalSince1970 * 1000) })
        server = PeerServer(
            identity: mac, pairing: pairing, port: port, serviceName: serviceName, queue: queue,
            advertise: advertise, pingInterval: min(30, idleTimeout / 3), idleTimeout: idleTimeout,
            retryInitialDelay: 0.05, retryMaxDelay: 0.4)
        server.delegate = delegate
    }

    func tearDown() {
        clients.forEach { $0.close() }
        server.stop()
        deleteTestIdentity(label: macLabel)
        deleteTestIdentity(label: phoneLabel)
        removeTestDefaults(testDefaults.suite)
    }

    /// Starts the server and waits for its ephemeral port.
    func start() async throws -> Bool {
        try server.start()
        return await eventually { server.listeningPort != nil }
    }

    /// Restarts and waits for the listener to be ready again. The first rebind may fail
    /// while the old socket is released, so this allows time for the retry backoff.
    func restart(timeout: TimeInterval = 5) async throws -> Bool {
        try server.restart()
        return await eventually(timeout: timeout) { server.listeningPort != nil }
    }

    func openPairingWindow() -> String {
        queue.sync {
            let url = pairing.beginPairing(host: "127.0.0.1", fingerprint: mac.fingerprint)
            return queryItems(url)["code"]!
        }
    }

    func markPhonePaired(name: String = "Test Phone") {
        queue.sync { pairing.setPaired(fingerprint: phone.fingerprint, name: name) }
    }

    var pairedFingerprint: String? { queue.sync { pairing.pairedPhone?.fingerprint } }

    func makeClient(autoReplyPing: Bool = true) -> TestPhoneClient {
        let client = TestPhoneClient(
            identity: phone, serverFingerprint: mac.fingerprint, port: server.listeningPort ?? 0,
            autoReplyPing: autoReplyPing)
        clients.append(client)
        return client
    }

    func sendImage(id: UUID, size: Int) {
        server.sendImage(id: id, data: Data(repeating: 0x5A, count: size))
    }
}
