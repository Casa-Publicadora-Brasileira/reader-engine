// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ReaderEngineUI",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "ReaderEngineUI",
            targets: ["ReaderEngineUI"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "ReaderCore",
            path: "../reader-core/build/XCFrameworks/release/ReaderCore.xcframework"
        ),
        .target(
            name: "ReaderEngineUI",
            dependencies: ["ReaderCore"],
            path: "Sources/ReaderEngineUI"
        )
    ]
)
