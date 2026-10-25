import Foundation
import KopiCore

/// Collects the bytes of a remote image requested from a pasteboard promise.
///
/// NSPasteboard calls the promise provider on the main thread and that call blocks
/// until the image arrives, so this buffer is fed straight from the server's queue
/// (never via the main actor) and is safe to use from any thread.
public final class IncomingImageBuffer: @unchecked Sendable {
    private enum Phase {
        case waiting
        case receiving(expected: Int)
        case done
        case failed
    }

    private let condition = NSCondition()
    private var phases: [UUID: Phase] = [:]
    private var buffers: [UUID: Data] = [:]

    public init() {}

    /// Registers interest in `id`; bytes for ids nobody waits for are dropped.
    public func expect(_ id: UUID) {
        update {
            phases[id] = .waiting
            buffers[id] = nil
        }
    }

    /// The `clip_data` header.
    public func begin(id: UUID, size: Int) {
        update {
            guard case .waiting? = phases[id] else { return }
            guard size > 0, size <= Limits.maxImageBytes else {
                phases[id] = .failed
                return
            }
            phases[id] = .receiving(expected: size)
            buffers[id] = Data(capacity: size)
        }
    }

    public func append(id: UUID, bytes: Data) {
        update {
            guard case .receiving(let expected)? = phases[id] else { return }
            buffers[id, default: Data()].append(bytes)
            let count = buffers[id]?.count ?? 0
            if count > expected {
                phases[id] = .failed
                buffers[id] = nil
            } else if count == expected {
                phases[id] = .done
            }
        }
    }

    /// `clip_unavailable` for a pending request.
    public func fail(id: UUID) {
        update {
            guard phases[id] != nil else { return }
            phases[id] = .failed
            buffers[id] = nil
        }
    }

    /// The phone disconnected: every pending wait returns nil.
    public func cancelAll() {
        update {
            for id in phases.keys { phases[id] = .failed }
            buffers.removeAll()
        }
    }

    public func discard(_ id: UUID) {
        update {
            phases[id] = nil
            buffers[id] = nil
        }
    }

    /// Blocks until the image is complete, fails, or `timeout` elapses (nil).
    public func wait(_ id: UUID, timeout: TimeInterval) -> Data? {
        let deadline = Date(timeIntervalSinceNow: timeout)
        condition.lock()
        defer {
            phases[id] = nil
            buffers[id] = nil
            condition.unlock()
        }
        while true {
            switch phases[id] {
            case .done?:
                return buffers[id]
            case .failed?, nil:
                return nil
            case .waiting?, .receiving?:
                if !condition.wait(until: deadline) {
                    if case .done? = phases[id] { return buffers[id] }
                    return nil
                }
            }
        }
    }

    private func update(_ body: () -> Void) {
        condition.lock()
        body()
        condition.broadcast()
        condition.unlock()
    }
}
