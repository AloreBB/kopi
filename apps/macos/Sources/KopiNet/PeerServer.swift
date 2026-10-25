import Foundation
import KopiCore
import Network
import Security

public protocol PeerServerDelegate: AnyObject {
    func peerConnected(name: String)
    func peerDisconnected()
    func received(_ message: KopiMessage)
    func receivedImageChunk(id: UUID, bytes: Data)
}

/// The Mac side of the protocol: a mutual-TLS 1.3 WebSocket listener advertised over
/// Bonjour. Accepts only the paired phone's certificate, or any certificate while the
/// pairing window is open (that session must then send a valid `pair`). Holds at most
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
    private let queueKey = DispatchSpecificKey<Void>()

    // Queue-confined state.
    private var configuredPort: UInt16
    private var listener: NWListener?
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
    public init(
        identity: LocalIdentity,
        pairing: PairingStore,
        port: UInt16 = Limits.serverPort,
        serviceName: String,
        queue: DispatchQueue,
        advertise: Bool = true,
        pingInterval: TimeInterval = TimeInterval(Limits.pingIntervalSeconds),
        idleTimeout: TimeInterval = PeerServer.defaultIdleTimeout
    ) {
        self.identity = identity
        self.pairing = pairing
        self.configuredPort = port
        self.serviceName = serviceName
        self.queue = queue
        self.advertise = advertise
        self.pingInterval = pingInterval
        self.idleTimeout = idleTimeout
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
            guard listener == nil else { return }
            let listener = try makeListener()
            self.listener = listener
            listener.start(queue: queue)
        }
    }

    /// Tears down the listener and every session, then listens again on the same port.
    /// Used after wake from sleep, when the old listener and Bonjour record may be stale.
    public func restart() throws {
        try onQueue {
            stopLocked()
            let listener = try makeListener()
            self.listener = listener
            listener.start(queue: queue)
        }
    }

    public func stop() {
        onQueue { stopLocked() }
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
                self.mirrorLock.withLock { self.portMirror = port }
                peerLog.info("listening on port \(port ?? 0, privacy: .public)")
            case .failed(let error):
                peerLog.error("listener failed: \(String(describing: error), privacy: .public)")
                self.mirrorLock.withLock { self.portMirror = nil }
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        return listener
    }

    /// TLS verify block (runs on `queue`): the paired phone, or anyone while pairing.
    private func acceptsPeer(_ trust: sec_trust_t) -> Bool {
        guard let fingerprint = PeerTLS.leafFingerprint(trust) else { return false }
        let accepted = fingerprint == pairing.pairedPhone?.fingerprint || pairing.isPairingWindowOpen
        if !accepted { peerLog.info("rejected unknown client certificate") }
        return accepted
    }

    private func stopLocked() {
        listener?.stateUpdateHandler = nil
        listener?.newConnectionHandler = nil
        listener?.cancel()
        listener = nil
        mirrorLock.withLock { portMirror = nil }
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
            guard hello.protocolVersion == Limits.protocolVersion else {
                peerLog.info("version mismatch: \(hello.protocolVersion, privacy: .public)")
                session.sendAndClose(.error(KopiError(code: .versionMismatch)))
                return
            }
            session.send(.hello(Hello(protocolVersion: Limits.protocolVersion, app: Limits.appVersion)))
            if let paired = pairing.pairedPhone, paired.fingerprint == session.peerFingerprint {
                activate(session, name: paired.name)
            } else {
                session.phase = .awaitingPair
            }

        case (.awaitingHello, _):
            peerLog.info("first message was not hello; closing")
            session.close()

        case (.awaitingPair, .message(.pair(let request))), (.active, .message(.pair(let request))):
            handlePair(request, from: session)

        case (.awaitingPair, _):
            session.sendAndClose(.error(KopiError(code: .notPaired)))

        case (.active, .message(let message)):
            if case .hello = message { return }
            delegate?.received(message)

        case (.active, .imageChunk(let id, let bytes)):
            delegate?.receivedImageChunk(id: id, bytes: bytes)
        }
    }

    private func handlePair(_ request: Pair, from session: Session) {
        guard let fingerprint = session.peerFingerprint, pairing.consume(code: request.code) else {
            peerLog.info("pairing rejected: invalid code")
            session.sendAndClose(.error(KopiError(code: .invalidCode)))
            return
        }
        pairing.setPaired(fingerprint: fingerprint, name: request.name)
        peerLog.info("paired new phone")
        session.send(.paired(Paired(name: serviceName)))
        if session.phase != .active {
            activate(session, name: request.name)
        } else {
            session.peerName = request.name
        }
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
