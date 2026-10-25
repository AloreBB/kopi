import Foundation

/// Append-only event log at ~/Library/Logs/Kopi/kopi.log, rotated at 1 MB.
/// The API takes no free-form content on purpose: only an event name, `id`, `kind`,
/// `size` and a fixed `reason` — clipboard contents must never reach the log.
public final class FileLog: @unchecked Sendable {
    public static let defaultURL = FileManager.default.homeDirectoryForCurrentUser
        .appending(path: "Library/Logs/Kopi/kopi.log")
    public static let defaultMaxBytes = 1024 * 1024

    public let url: URL
    private let maxBytes: Int
    private let lock = NSLock()
    private let formatter = ISO8601DateFormatter()

    public init(url: URL = FileLog.defaultURL, maxBytes: Int = FileLog.defaultMaxBytes) {
        self.url = url
        self.maxBytes = maxBytes
    }

    /// `name` and `reason` must be fixed strings chosen by the caller, never user data.
    public func event(
        _ name: String, id: UUID? = nil, kind: String? = nil, size: Int? = nil, reason: String? = nil
    ) {
        var line = "\(formatter.string(from: Date())) \(name)"
        if let reason { line += " reason=\(reason)" }
        if let id { line += " id=\(id.uuidString.lowercased())" }
        if let kind { line += " kind=\(kind)" }
        if let size { line += " size=\(size)" }
        write(line + "\n")
    }

    /// Creates the log file if needed so it can be opened from the menu.
    public func touch() {
        lock.withLock { _ = openHandle() }
    }

    private func write(_ line: String) {
        lock.withLock {
            let data = Data(line.utf8)
            rotateIfNeeded(adding: data.count)
            guard let handle = openHandle() else { return }
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        }
    }

    private func openHandle() -> FileHandle? {
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) {
            try? fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            fm.createFile(atPath: url.path, contents: nil)
        }
        return try? FileHandle(forWritingTo: url)
    }

    private func rotateIfNeeded(adding count: Int) {
        let fm = FileManager.default
        let current = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
        guard current > 0, current + count > maxBytes else { return }
        let backup = url.appendingPathExtension("1")
        try? fm.removeItem(at: backup)
        try? fm.moveItem(at: url, to: backup)
    }
}
