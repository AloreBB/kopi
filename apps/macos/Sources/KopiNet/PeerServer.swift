import Foundation
import KopiCore
import Network
import Security

public protocol PeerServerDelegate: AnyObject {
    func peerConnected(name: String)
    /// A phone was paired automatically (the Mac had none). `peerConnected` follows.
    func peerPaired(name: String)
    func peerDisconnected()
    func received(_ message: KopiMessage)
    func receivedImageChunk(id: UUID, bytes: Data)
    /// A client spoke another protocol version and was turned away.
    func peerVersionMismatch(protocolVersion: Int)
}

public extension PeerServerDelegate {
    func peerPaired(name: String) {}
    func peerVersionMismatch(protocolVersion: Int) {}
}

/// The Mac side of the protocol: a mutual-TLS 1.3 WebSocket listener advertised over
/// Bonjour. Pairing is trust-on-first-use: while no phone is paired, any client
/// certificate passes TLS and the first one to complete `hello` becomes the paired
/// phone; from then on only its fingerprint is accepted until `unpair()`. Holds at most
/// one active session; a newer valid session replaces it.
///
/// All state lives on `queue`, and every delegate callback is delivered there.
public final class PeerServer: @unchecked Sendable {
    public static let defaultIdleTimeout: TimeInterval = 75
    static let maxWebSocketMessageBytes = 16 * 1024 * 1024

    public weak var delegate: PeerServerDelegate?

    private let identity: LocalIdentity
    private let pairing: PairingStore
    private let serviceName: String
    private let queue: DispatchQueue
    private let advertise: Bool
    private let pingInterval: TimeInterval
    private let idleTimeout: TimeInterval
    private let retryInitialDelay: TimeInterval
    private let retryMaxDelay: TimeInterval
    private let queueKey = DispatchSpecificKey<Void>()

    // Queue-confined state.
    private var configuredPort: UInt16
    private var listener: NWListener?
    /// True between start()/restart() and stop(); gates listener recovery.
    private var running = false
    private var retryDelay: TimeInterval
    private var retryWork: DispatchWorkItem?
    private var pending: [ObjectIdentifier: Session] = [:]
    private var active: Session?

    // Mirrors readable from any thread.
    private let mirrorLock = NSLock()
    private var connectedMirror = false
    private var portMirror: UInt16?

    /// - Parameters:
    ///   - port: 0 binds an ephemeral port; `restart()` then rebinds the same one.
    ///   - advertise: publish the Bonjour service `_kopi._tcp`.
    ///   - pingInterval / idleTimeout: WebSocket ping cadence and how long a session may
    ///     go without any frame or pong before it is closed.
    ///   - retryInitialDelay / retryMaxDelay: backoff for rebuilding a listener that failed
    ///     (e.g. port still held by the previous socket); doubles per attempt, reset on ready.
    public init(
        identity: LocalIdentity,
        pairing: PairingStore,
        port: UInt16 = Limits.serverPort,
        serviceName: String,
        queue: DispatchQueue,
        advertise: Bool = true,
        pingInterval: TimeInterval = TimeInterval(Limits.pingIntervalSeconds),
        idleTimeout: TimeInterval = PeerServer.defaultIdleTimeout,
        retryInitialDelay: TimeInterval = 0.25,
        retryMaxDelay: TimeInterval = 30
    ) {
        self.identity = identity
        self.pairing = pairing
        self.configuredPort = port
        self.serviceName = serviceName
        self.queue = queue
        self.advertise = advertise
        self.pingInterval = pingInterval
        self.idleTimeout = idleTimeout
        self.retryInitialDelay = retryInitialDelay
        self.retryMaxDelay = retryMaxDelay
        self.retryDelay = retryInitialDelay
        queue.setSpecific(key: queueKey, value: ())
    }

    deinit {
        queue.setSpecific(key: queueKey, value: nil)
    }

    public var isConnected: Bool { mirrorLock.withLock { connectedMirror } }

    /// The bound port once the listener is ready; nil otherwise.
    public var listeningPort: UInt16? { mirrorLock.withLock { portMirror } }

    public func start() throws {
        try onQueue {
            guard !running else { return }
            try startListenerLocked()
        }
    }

    /// Tears down the listener and every session, then listens again on the same port.
    /// Used after wake from sleep, when the old listener and Bonjour record may be stale.
    /// The old socket is released asynchronously, so the first rebind may fail; the
    /// listener then recovers through the retry backoff.
    public func restart() throws {
        try onQueue {
            stopLocked()
            try startListenerLocked()
        }
    }

    public func stop() {
        onQueue { stopLocked() }
    }

    /// Name of the paired phone, or nil while unpaired.
    public var pairedPhoneName: String? { pairing.pairedPhone?.name }

    /// Forgets the paired phone and drops every session. The listener keeps running, so
    /// the next phone that connects pairs automatically.
    public func unpair() {
        onQueue {
            pairing.unpair()
            peerLog.info("unpaired; dropping sessions")
            let sessions = Array(pending.values) + (active.map { [$0] } ?? [])
            sessions.forEach { $0.close() }
        }
    }

    public func send(_ message: KopiMessage) {
        queue.async { [self] in
            active?.send(message)
        }
    }

    /// Sends `clip_data` followed by binary frames of at most `Limits.imageChunkBytes`.
    public func sendImage(id: UUID, data: Data) {
        queue.async { [self] in
            guard let session = active else { return }
            session.send(.clipData(ClipData(id: id, size: data.count)))
            var offset = data.startIndex
            while offset < data.endIndex {
                let end = min(offset + Limits.imageChunkBytes, data.endIndex)
                session.sendImageChunk(id: id, chunk: data[offset..<end])
                offset = end
            }
            peerLog.info("sent image id=\(id.uuidString, privacy: .public) size=\(data.count, privacy: .public)")
        }
    }

    // MARK: - Listener

    private func makeParameters() -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let sec = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        if let localIdentity = sec_identity_create(identity.secIdentity) {
            sec_protocol_options_set_local_identity(sec, localIdentity)
        }
        sec_protocol_options_set_peer_authentication_required(sec, true)
        sec_protocol_options_set_verify_block(sec, { [weak self] _, trust, complete in
            complete(self?.acceptsPeer(trust) ?? false)
        }, queue)

        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        parameters.allowLocalEndpointReuse = true
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = Self.maxWebSocketMessageBytes
        parameters.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        return parameters
    }

    private func startListenerLocked() throws {
        running = true
        let listener = try makeListener()
        self.listener = listener
        listener.start(queue: queue)
    }

    /// Drops a dead listener and rebuilds it after the current backoff delay.
    private func scheduleRetryLocked() {
        discardListenerLocked()
        guard running else { return }
        retryWork?.cancel()
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, retryMaxDelay)
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.running, self.listener == nil else { return }
            self.retryWork = nil
            do {
                try self.startListenerLocked()
            } catch {
                peerLog.error("listener create failed: \(String(describing: error), privacy: .public)")
                self.scheduleRetryLocked()
            }
        }
        retryWork = work
        peerLog.info("listener retry in \(delay, privacy: .public)s")
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func discardListenerLocked() {
        listener?.stateUpdateHandler = nil
        listener?.newConnectionHandler = nil
        listener?.cancel()
        listener = nil
        mirrorLock.withLock { portMirror = nil }
    }

    private func makeListener() throws -> NWListener {
        let parameters = makeParameters()
        let listener: NWListener
        if configuredPort == 0 {
            listener = try NWListener(using: parameters)
        } else {
            listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: configuredPort)!)
        }
        if advertise {
            listener.service = NWListener.Service(name: serviceName, type: Limits.bonjourServiceType)
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self, let listener, listener === self.listener else { return }
            switch state {
            case .ready:
                let port = listener.port?.rawValue
                // Keep the ephemeral port so restart() rebinds the same one.
                if self.configuredPort == 0, let port { self.configuredPort = port }
                self.retryDelay = self.retryInitialDelay
                self.mirrorLock.withLock { self.portMirror = port }
                peerLog.info("listening on port \(port ?? 0, privacy: .public)")
            case .failed(let error):
                peerLog.error("listener failed: \(String(describing: error), privacy: .public)")
                self.scheduleRetryLocked()
            case .waiting(let error):
                // NWListener reports some bind failures (address in use) as waiting.
                peerLog.error("listener waiting: \(String(describing: error), privacy: .public)")
                self.scheduleRetryLocked()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        return listener
    }

    /// TLS verify block (runs on `queue`): anyone while unpaired, else only the paired phone.
    private func acceptsPeer(_ trust: sec_trust_t) -> Bool {
        guard let fingerprint = PeerTLS.leafFingerprint(trust) else { return false }
        guard let paired = pairing.pairedPhone else { return true }
        let accepted = fingerprint == paired.fingerprint
        if !accepted { peerLog.info("rejected unknown client certificate") }
        return accepted
    }

    private func stopLocked() {
        running = false
        retryWork?.cancel()
        retryWork = nil
        retryDelay = retryInitialDelay
        discardListenerLocked()
        let sessions = Array(pending.values) + (active.map { [$0] } ?? [])
        sessions.forEach { $0.close() }
    }

    // MARK: - Sessions

    private func accept(_ connection: NWConnection) {
        let session = Session(
            connection: connection, queue: queue, pingInterval: pingInterval, idleTimeout: idleTimeout)
        pending[ObjectIdentifier(session)] = session
        session.onFrame = { [weak self] session, frame in self?.handle(frame, from: session) }
        session.onClose = { [weak self] session in self?.sessionClosed(session) }
        session.start()
        // A connection that never completes the handshake is dropped.
        queue.asyncAfter(deadline: .now() + idleTimeout) { [weak session] in
            guard let session, session.phase != .active else { return }
            session.close()
        }
    }

    private func handle(_ frame: Session.Frame, from session: Session) {
        switch (session.phase, frame) {
        case (.awaitingHello, .message(.hello(let hello))):
            handleHello(hello, from: session)

        case (.awaitingHello, _):
            peerLog.info("first message was not hello; closing")
            session.close()

        case (.active, .message(let message)):
            if case .hello = message { return }
            delegate?.received(message)

        case (.active, .imageChunk(let id, let bytes)):
            delegate?.receivedImageChunk(id: id, bytes: bytes)
        }
    }

    private func handleHello(_ hello: Hello, from session: Session) {
        guard hello.protocolVersion == Limits.protocolVersion else {
            peerLog.info("version mismatch: \(hello.protocolVersion, privacy: .public)")
            session.sendAndClose(.error(KopiError(code: .versionMismatch)))
            delegate?.peerVersionMismatch(protocolVersion: hello.protocolVersion)
            return
        }
        guard let fingerprint = session.peerFingerprint else {
            session.sendAndClose(.error(KopiError(code: .notPaired)))
            return
        }
        let name = Self.displayName(hello.name)
        let reply = KopiMessage.hello(
            Hello(protocolVersion: Limits.protocolVersion, app: Limits.appVersion, name: serviceName))

        guard let paired = pairing.pairedPhone else {
            // Trust on first use: the Mac had no phone, so this one becomes it.
            pairing.setPaired(fingerprint: fingerprint, name: name)
            peerLog.info("auto-paired new phone")
            session.send(reply)
            session.send(.paired(Paired(name: serviceName)))
            delegate?.peerPaired(name: name)
            activate(session, name: name)
            return
        }
        guard paired.fingerprint == fingerprint else {
            // Passed TLS while unpaired, but another phone paired first.
            peerLog.info("hello from a phone that is not the paired one")
            session.sendAndClose(.error(KopiError(code: .notPaired)))
            return
        }
        if paired.name != name { pairing.setPaired(fingerprint: fingerprint, name: name) }
        session.send(reply)
        activate(session, name: name)
    }

    /// The phone's name as shown in the menu; never empty.
    static func displayName(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Android" : String(trimmed.prefix(64))
    }

    private func activate(_ session: Session, name: String) {
        pending[ObjectIdentifier(session)] = nil
        session.phase = .active
        session.peerName = name
        let previous = active
        active = session
        mirrorLock.withLock { connectedMirror = true }
        // Replace silently: the old session is no longer `active` when its onClose fires.
        previous?.close()
        delegate?.peerConnected(name: name)
    }

    private func sessionClosed(_ session: Session) {
        pending[ObjectIdentifier(session)] = nil
        guard session === active else { return }
        active = nil
        mirrorLock.withLock { connectedMirror = false }
        delegate?.peerDisconnected()
    }

    private func onQueue<T>(_ body: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return try body()
        }
        return try queue.sync(execute: body)
    }
}
