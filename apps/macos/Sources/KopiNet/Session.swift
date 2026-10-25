import Foundation
import KopiCore
import Network
import Security
import os

/// Certificate pinning helpers shared by the server and test clients.
public enum PeerTLS {
    /// Fingerprint (lowercase hex SHA-256 of the DER) of the leaf certificate in `trust`.
    public static func leafFingerprint(_ trust: sec_trust_t) -> String? {
        let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
        guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
              let leaf = chain.first
        else { return nil }
        return Identity.fingerprint(der: SecCertificateCopyData(leaf) as Data)
    }

    /// Fingerprint of the peer's leaf certificate on an established TLS connection.
    static func peerFingerprint(_ connection: NWConnection) -> String? {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition)
            as? NWProtocolTLS.Metadata
        else { return nil }
        var leaf: SecCertificate?
        sec_protocol_metadata_access_peer_certificate_chain(metadata.securityProtocolMetadata) { cert in
            if leaf == nil { leaf = sec_certificate_copy_ref(cert).takeRetainedValue() }
        }
        guard let leaf else { return nil }
        return Identity.fingerprint(der: SecCertificateCopyData(leaf) as Data)
    }
}

let peerLog = Logger(subsystem: Limits.bundleID, category: "net")

/// One WebSocket connection: JSON text frames and `[16-byte UUID][chunk]` binary
/// frames, plus ping/idle liveness. All callbacks and state live on `queue`.
final class Session: @unchecked Sendable {
    enum Frame {
        case message(KopiMessage)
        case imageChunk(id: UUID, bytes: Data)
    }

    enum Phase {
        case awaitingHello
        case active
    }

    // Owned by PeerServer (handshake state).
    var phase = Phase.awaitingHello
    var peerName = ""

    private(set) var peerFingerprint: String?
    private(set) var isClosed = false

    var onReady: ((Session) -> Void)?
    var onFrame: ((Session, Frame) -> Void)?
    var onClose: ((Session) -> Void)?

    private let connection: NWConnection
    private let queue: DispatchQueue
    private let pingInterval: TimeInterval
    private let idleTimeout: TimeInterval
    private var lastActivity = DispatchTime.now()
    private var pingTimer: DispatchSourceTimer?
    private var watchdog: DispatchSourceTimer?

    init(connection: NWConnection, queue: DispatchQueue, pingInterval: TimeInterval, idleTimeout: TimeInterval) {
        self.connection = connection
        self.queue = queue
        self.pingInterval = pingInterval
        self.idleTimeout = idleTimeout
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.becameReady()
            case .failed(let error):
                peerLog.info("session failed: \(String(describing: error), privacy: .public)")
                self.finish()
            case .cancelled:
                self.finish()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    func send(_ message: KopiMessage, completion: (@Sendable () -> Void)? = nil) {
        guard !isClosed else { return }
        let data: Data
        do {
            data = try JSONEncoder().encode(message)
        } catch {
            peerLog.error("encode failed")
            return
        }
        send(data, opcode: .text, completion: completion)
    }

    func sendImageChunk(id: UUID, chunk: Data) {
        guard !isClosed else { return }
        send(Self.binaryFrame(id: id, chunk: chunk), opcode: .binary, completion: nil)
    }

    /// Sends `message` and closes once it has been handed to the transport.
    func sendAndClose(_ message: KopiMessage) {
        send(message) { [weak self] in self?.close() }
    }

    func close() {
        finish()
    }

    static func binaryFrame(id: UUID, chunk: Data) -> Data {
        var frame = withUnsafeBytes(of: id.uuid) { Data($0) }
        frame.append(chunk)
        return frame
    }

    static func parseBinaryFrame(_ data: Data) -> (id: UUID, chunk: Data)? {
        guard data.count >= 16, data.count - 16 <= Limits.imageChunkBytes else { return nil }
        let id = data.prefix(16).withUnsafeBytes { UUID(uuid: $0.loadUnaligned(as: uuid_t.self)) }
        return (id, Data(data.dropFirst(16)))
    }

    private func send(_ data: Data, opcode: NWProtocolWebSocket.Opcode, completion: (@Sendable () -> Void)?) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: opcode)
        let context = NWConnection.ContentContext(identifier: "kopi", metadata: [metadata])
        connection.send(
            content: data, contentContext: context, isComplete: true,
            completion: .contentProcessed { error in
                if let error { peerLog.info("send failed: \(String(describing: error), privacy: .public)") }
                completion?()
            })
    }

    private func becameReady() {
        guard let fingerprint = PeerTLS.peerFingerprint(connection) else {
            peerLog.error("no peer certificate; closing")
            close()
            return
        }
        peerFingerprint = fingerprint
        touch()
        startTimers()
        onReady?(self)
        receive()
    }

    private func receive() {
        connection.receiveMessage { [weak self] content, context, _, error in
            guard let self, !self.isClosed else { return }
            if let error {
                peerLog.info("receive failed: \(String(describing: error), privacy: .public)")
                self.close()
                return
            }
            self.touch()
            let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                as? NWProtocolWebSocket.Metadata
            let data = content ?? Data()
            switch metadata?.opcode {
            case .text:
                // Unknown or malformed messages are ignored (forward compatibility).
                if let message = try? JSONDecoder().decode(KopiMessage.self, from: data) {
                    self.onFrame?(self, .message(message))
                } else {
                    peerLog.info("ignored undecodable text frame size=\(data.count, privacy: .public)")
                }
            case .binary:
                if let (id, chunk) = Self.parseBinaryFrame(data) {
                    self.onFrame?(self, .imageChunk(id: id, bytes: chunk))
                } else {
                    peerLog.info("ignored malformed binary frame size=\(data.count, privacy: .public)")
                }
            case .close:
                self.close()
                return
            default:
                break
            }
            guard !self.isClosed else { return }
            self.receive()
        }
    }

    private func touch() {
        lastActivity = .now()
    }

    private func startTimers() {
        let ping = DispatchSource.makeTimerSource(queue: queue)
        ping.schedule(deadline: .now() + pingInterval, repeating: pingInterval)
        ping.setEventHandler { [weak self] in self?.sendPing() }
        ping.resume()
        pingTimer = ping

        let check = min(1, idleTimeout / 4)
        let dog = DispatchSource.makeTimerSource(queue: queue)
        dog.schedule(deadline: .now() + check, repeating: check)
        dog.setEventHandler { [weak self] in
            guard let self else { return }
            let idle = Double(DispatchTime.now().uptimeNanoseconds - self.lastActivity.uptimeNanoseconds) / 1e9
            if idle >= self.idleTimeout {
                peerLog.info("session idle for \(idle, privacy: .public)s; closing")
                self.close()
            }
        }
        dog.resume()
        watchdog = dog
    }

    private func sendPing() {
        guard !isClosed else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .ping)
        metadata.setPongHandler(queue) { [weak self] error in
            if error == nil { self?.touch() }
        }
        let context = NWConnection.ContentContext(identifier: "ping", metadata: [metadata])
        connection.send(content: Data(), contentContext: context, isComplete: true, completion: .idempotent)
    }

    private func finish() {
        guard !isClosed else { return }
        isClosed = true
        pingTimer?.cancel()
        watchdog?.cancel()
        pingTimer = nil
        watchdog = nil
        connection.stateUpdateHandler = nil
        if connection.state != .cancelled { connection.cancel() }
        let onClose = self.onClose
        self.onReady = nil
        self.onFrame = nil
        self.onClose = nil
        onClose?(self)
    }
}
