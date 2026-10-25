import AppKit
import KopiCore

// AppKit pasteboard mechanics for ClipboardWatcherTests. They live here because
// a file importing both Foundation (pulled in by AppKit) and Testing does not
// compile on the Command Line Tools toolchain.
//
// Every board is a uniquely named private pasteboard; tests never touch
// NSPasteboard.general.
@MainActor
final class TestBoard {
    let pasteboard: NSPasteboard

    init() {
        pasteboard = NSPasteboard(name: .init("dev.alorebb.kopi.test.\(UUID().uuidString)"))
        pasteboard.clearContents()
    }

    func makeWatcher() -> ClipboardWatcher {
        ClipboardWatcher(pasteboard: pasteboard, interval: 60)
    }

    func writeString(_ text: String, concealed: Bool = false) {
        pasteboard.clearContents()
        var types: [NSPasteboard.PasteboardType] = [.string]
        if concealed { types.append(.init("org.nspasteboard.ConcealedType")) }
        pasteboard.declareTypes(types, owner: nil)
        pasteboard.setString(text, forType: .string)
        if concealed {
            pasteboard.setData(Data(), forType: .init("org.nspasteboard.ConcealedType"))
        }
    }

    func writeTiff() {
        let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: 4, pixelsHigh: 4, bitsPerSample: 8,
            samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        pasteboard.clearContents()
        pasteboard.setData(rep.tiffRepresentation!, forType: .tiff)
    }

    func pngData() -> [UInt8]? {
        pasteboard.data(forType: .png).map { [UInt8]($0) }
    }

    func release() {
        pasteboard.releaseGlobally()
    }
}

func bytes(_ data: Data?) -> [UInt8]? {
    data.map { [UInt8]($0) }
}

func makeData(_ bytes: [UInt8]) -> Data {
    Data(bytes)
}
