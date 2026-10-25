import Testing
import KopiCore
import KopiNet

private let pngSignature: [UInt8] = [0x89, 0x50, 0x4E, 0x47]
private let jpegSignature: [UInt8] = [0xFF, 0xD8, 0xFF]

@MainActor @Suite struct AppCoordinatorTests {
    @Test func localCopySendsWhenConnected() {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.watcher.emitText("hola")
        guard case .clip(let clip)? = rig.server.sent.last else {
            Issue.record("expected a clip to be sent")
            return
        }
        #expect(clip.text == "hola")
        #expect(rig.watcher.started)
    }

    @Test func copyWhileDisconnectedIsResentOnConnect() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.server.isConnected = false
        rig.watcher.emitText("hola")
        let before = rig.server.sent.count
        rig.coordinator.peerConnected(name: "Tecno")
        await flushMain()
        #expect(rig.server.sent.count == before + 1)
        #expect(rig.coordinator.status == .connected("Tecno"))
    }

    @Test func remoteTextWritesPasteboard() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.coordinator.received(remoteClip("desde el teléfono"))
        await flushMain()
        #expect(rig.watcher.writtenTexts == ["desde el teléfono"])
        #expect(rig.coordinator.pulse)
        #expect(rig.coordinator.last?.preview == "desde el teléfono")
    }

    @Test func remoteOfferRegistersPromiseAndRequestsOnPaste() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        let png = makePNG()
        let id = makeID()
        rig.server.reply = .image(png)
        rig.coordinator.received(remoteOffer(id: id, mime: "image/png", size: png.count))
        await flushMain()
        #expect(rig.watcher.promisedMime == "image/png")
        #expect(rig.server.sent.isEmpty)

        // The test runs on the main thread, exactly like NSPasteboard's provider callback:
        // chunks must reach the buffer without hopping to the (blocked) main actor.
        let pasted = rig.watcher.paste()
        #expect(pasted == [UInt8](png))
        #expect(rig.server.sent == [.clipRequest(ClipRequest(id: id))])
    }

    @Test func remoteJpegIsPastedAsPng() async throws {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        let jpeg = makeJPEG()
        #expect(Array([UInt8](jpeg).prefix(3)) == jpegSignature)
        rig.server.reply = .image(jpeg)
        rig.coordinator.received(remoteOffer(id: makeID(), mime: "image/jpeg", size: jpeg.count))
        await flushMain()
        #expect(rig.watcher.promisedMime == "image/png")
        let pasted = try #require(rig.watcher.paste())
        #expect(Array(pasted.prefix(4)) == pngSignature)
    }

    @Test func pasteYieldsNothingWhenPhoneDisconnects() async {
        let rig = CoordinatorRig(pasteWait: 5)
        defer { rig.cleanUp() }
        rig.server.reply = .disconnect
        rig.coordinator.received(remoteOffer(id: makeID(), mime: "image/png", size: 10))
        await flushMain()
        var pasted: [UInt8]? = [0]
        let elapsed = ContinuousClock().measure { pasted = rig.watcher.paste() }
        #expect(pasted == nil)
        #expect(elapsed < .seconds(2))
    }

    @Test func pasteYieldsNothingOnTimeout() async {
        let rig = CoordinatorRig(pasteWait: 0.2)
        defer { rig.cleanUp() }
        rig.server.reply = .none
        rig.coordinator.received(remoteOffer(id: makeID(), mime: "image/png", size: 10))
        await flushMain()
        #expect(rig.watcher.paste() == nil)
    }

    @Test func pasteYieldsNothingWhenNotConnected() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.server.reply = .image(makePNG())
        rig.coordinator.received(remoteOffer(id: makeID(), mime: "image/png", size: 10))
        await flushMain()
        rig.server.isConnected = false
        let elapsed = ContinuousClock().measure { #expect(rig.watcher.paste() == nil) }
        #expect(elapsed < .seconds(1))
    }

    @Test func imageRequestServedFromMemory() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        let png = makePNG()
        rig.watcher.emitImage(png)
        guard case .clipOffer(let offer)? = rig.server.sent.last else {
            Issue.record("expected an offer to be sent")
            return
        }
        rig.coordinator.received(.clipRequest(ClipRequest(id: offer.id)))
        await flushMain()
        #expect(rig.server.sentImages.count == 1)
        #expect(rig.server.sentImages.first?.id == offer.id)
        #expect(rig.server.sentImages.first?.bytes == [UInt8](png))
    }

    @Test func unknownImageRequestIsUnavailable() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        let id = makeID()
        rig.coordinator.received(.clipRequest(ClipRequest(id: id)))
        await flushMain()
        #expect(rig.server.sentImages.isEmpty)
        #expect(rig.server.sent == [.clipUnavailable(ClipUnavailable(id: id, reason: .replaced))])
    }

    @Test func wakeRestartsServer() {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.postWake()
        #expect(rig.server.restarts == 1)
    }

    @Test func disconnectShowsNotConnected() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        #expect(rig.coordinator.status == .waiting)
        rig.server.pairedPhoneName = "Tecno"
        rig.coordinator.peerConnected(name: "Tecno")
        rig.coordinator.peerDisconnected()
        await flushMain()
        #expect(rig.coordinator.status == .disconnected)
    }

    @Test func startsWaitingWhenUnpairedAndDisconnectedWhenPaired() {
        let unpaired = CoordinatorRig()
        defer { unpaired.cleanUp() }
        #expect(unpaired.coordinator.status == .waiting)
        #expect(unpaired.coordinator.pairedName == nil)

        let paired = CoordinatorRig(pairedPhoneName: "Tecno")
        defer { paired.cleanUp() }
        #expect(paired.coordinator.status == .disconnected)
        #expect(paired.coordinator.pairedName == "Tecno")
    }

    @Test func autoPairPulsesAndAnnounces() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        var announced: [String] = []
        rig.coordinator.onAutoPaired = { announced.append($0) }
        rig.server.pairedPhoneName = "Tecno"
        rig.coordinator.peerPaired(name: "Tecno")
        rig.coordinator.peerConnected(name: "Tecno")
        await flushMain()
        #expect(announced == ["Tecno"])
        #expect(rig.coordinator.pulse)
        #expect(rig.coordinator.pairedName == "Tecno")
        #expect(rig.coordinator.status == .connected("Tecno"))
        #expect(rig.logContents().contains("auto_paired"))
    }

    @Test func unpairForgetsPhoneAndWaitsForTheNextOne() async {
        let rig = CoordinatorRig(pairedPhoneName: "Tecno")
        defer { rig.cleanUp() }
        rig.coordinator.peerConnected(name: "Tecno")
        await flushMain()
        rig.coordinator.unpair()
        #expect(rig.server.unpairs == 1)
        #expect(rig.coordinator.pairedName == nil)
        #expect(rig.coordinator.status == .waiting)
        // The dropped session reports its disconnect afterwards: still waiting.
        rig.coordinator.peerDisconnected()
        await flushMain()
        #expect(rig.coordinator.status == .waiting)
        #expect(rig.logContents().contains("unpaired"))
    }

    @Test func copyWhileOfflineLogsSendSkipped() {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.server.isConnected = false
        rig.watcher.emitText("hola")
        rig.watcher.emitImage(makePNG())
        let log = rig.logContents()
        #expect(!log.contains("copy_sent"))
        #expect(!log.contains("offer_sent"))
        #expect(log.contains("send_skipped") && log.contains("reason=offline") && log.contains("kind=text"))
        #expect(log.contains("kind=image"))
    }

    @Test func copyWhileConnectedLogsSent() {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.watcher.emitText("hola")
        let log = rig.logContents()
        #expect(log.contains("copy_sent"))
        #expect(!log.contains("send_skipped"))
    }

    @Test func outdatedPhoneIsReported() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.coordinator.peerVersionMismatch(protocolVersion: 0)
        await flushMain()
        #expect(rig.coordinator.phoneOutdated)
        rig.coordinator.peerConnected(name: "Tecno")
        await flushMain()
        #expect(!rig.coordinator.phoneOutdated)
    }

    @Test func logNeverContainsContent() async {
        let rig = CoordinatorRig()
        defer { rig.cleanUp() }
        rig.watcher.emitText("4242 4242 4242 4242")
        rig.coordinator.received(remoteClip("4242 4242 4242 4242 remoto"))
        await flushMain()
        let log = rig.logContents()
        #expect(log.contains("kind=text"))
        #expect(!log.contains("4242"))
        #expect(!log.contains("remoto"))
    }
}
