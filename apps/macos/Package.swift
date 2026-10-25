// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Kopi",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "KopiCore", targets: ["KopiCore"]),
        .library(name: "KopiNet", targets: ["KopiNet"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-certificates", from: "1.6.0"),
    ],
    targets: [
        .target(name: "KopiCore"),
        .target(
            name: "KopiNet",
            dependencies: [
                "KopiCore",
                .product(name: "X509", package: "swift-certificates"),
            ]
        ),
        .executableTarget(name: "Kopi", dependencies: ["KopiCore", "KopiNet"]),
        .testTarget(name: "KopiCoreTests", dependencies: ["KopiCore"]),
        .testTarget(name: "KopiNetTests", dependencies: ["KopiCore", "KopiNet"]),
    ]
)
