// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MCPiOS",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MCPiOSCore", targets: ["MCPiOSCore"]),
    ],
    targets: [
        .target(name: "MCPiOSCore", path: "Sources/MCPiOSCore"),
        .testTarget(name: "MCPiOSTests", dependencies: ["MCPiOSCore"], path: "Tests/MCPiOSTests"),
    ]
)
