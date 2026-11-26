// swift-tools-version:5.9
// The Tether SDK's "hello world": a complete encrypted session between a phone and a client, in memory.
//   swift run            (macOS or Linux, from this directory)
// In your own package use `.package(url: "https://github.com/amirwmr/tether", from: "1.1.0")` instead of the path.
import PackageDescription

let package = Package(
    name: "Loopback",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(name: "Tether", path: "../../.."),
    ],
    targets: [
        .executableTarget(name: "Loopback", dependencies: [.product(name: "TetherCore", package: "Tether")]),
    ]
)
