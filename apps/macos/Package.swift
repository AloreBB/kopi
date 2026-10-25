// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Kopi",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "KopiCore", targets: ["KopiCore"]),
        .library(name: "KopiNet", targets: ["KopiNet"]),
    ],
    targets: [
        .target(name: "KopiCore"),
        .target(name: "KopiNet", dependencies: ["KopiCore"]),
        .executableTarget(name: "Kopi", dependencies: ["KopiCore", "KopiNet"]),
        .testTarget(name: "KopiCoreTests", dependencies: ["KopiCore"]),
        .testTarget(name: "KopiNetTests", dependencies: ["KopiNet"]),
    ]
)
