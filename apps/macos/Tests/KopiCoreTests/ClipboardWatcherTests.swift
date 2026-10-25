import Testing
import KopiCore

private let pngSignature: [UInt8] = [0x89, 0x50, 0x4E, 0x47]

@MainActor @Suite struct ClipboardWatcherTests {
    @Test func readsPlainText() {
        let board = TestBoard()
        defer { board.release() }
        board.writeString("hola")
        let clip = board.makeWatcher().read()
        #expect(clip?.content == .text("hola", sensitive: false))
        #expect(clip?.imageData == nil)
    }

    @Test func readsConcealedAsSensitive() {
        let board = TestBoard()
        defer { board.release() }
        board.writeString("s3cret", concealed: true)
        #expect(board.makeWatcher().read()?.content == .text("s3cret", sensitive: true))
    }

    @Test func readsTiffAsPng() throws {
        let board = TestBoard()
        defer { board.release() }
        board.writeTiff()
        let clip = try #require(board.makeWatcher().read())
        let data = try #require(bytes(clip.imageData))
        #expect(Array(data.prefix(4)) == pngSignature)
        guard case .image(let mime, let size, let sha256) = clip.content else {
            Issue.record("expected image content")
            return
        }
        #expect(mime == "image/png")
        #expect(size == data.count)
        #expect(sha256.count == 32)
    }

    @Test func emptyPasteboardReadsNil() {
        let board = TestBoard()
        defer { board.release() }
        #expect(board.makeWatcher().read() == nil)
    }

    @Test func externalCopyIsReported() {
        let board = TestBoard()
        defer { board.release() }
        let watcher = board.makeWatcher()
        var reported: [LocalContent] = []
        watcher.onCopy = { reported.append($0.content) }
        watcher.start()
        defer { watcher.stop() }
        watcher.poll()
        #expect(reported.isEmpty)
        board.writeString("de fuera")
        watcher.poll()
        watcher.poll()
        #expect(reported == [.text("de fuera", sensitive: false)])
    }

    @Test func ownWriteIsNotReported() {
        let board = TestBoard()
        defer { board.release() }
        let watcher = board.makeWatcher()
        var calls = 0
        watcher.onCopy = { _ in calls += 1 }
        watcher.start()
        defer { watcher.stop() }
        watcher.writeText("x")
        watcher.poll()
        #expect(calls == 0)
        #expect(watcher.read()?.content == .text("x", sensitive: false))
    }

    @Test func promiseProvidesOnRead() {
        let board = TestBoard()
        defer { board.release() }
        let watcher = board.makeWatcher()
        var calls = 0
        watcher.onCopy = { _ in calls += 1 }
        watcher.start()
        defer { watcher.stop() }
        let payload: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 1, 2, 3]
        watcher.writeImagePromise(mime: "image/png", provide: { makeData(payload) })
        watcher.poll()
        #expect(calls == 0)
        #expect(board.pngData() == payload)
    }
}
