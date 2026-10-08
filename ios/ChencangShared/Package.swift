// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ChencangShared",
    defaultLocalization: "en",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "ChencangShared", targets: ["ChencangShared"]),
    ],
    dependencies: [
        .package(path: "../../bindings/swift"),
    ],
    targets: [
        .target(
            name: "ChencangShared",
            dependencies: [
                .product(name: "Chencang", package: "swift"),
            ],
            path: "Sources/ChencangShared",
            resources: [.process("Resources")]
        ),
        .testTarget(
            name: "ChencangSharedTests",
            dependencies: ["ChencangShared"],
            path: "Tests/ChencangSharedTests"
        ),
    ]
)
