// swift-tools-version:5.9
// A Mac Bluetooth client for a Tether phone: pair with the pairing code, then print the phone's status and the
// latest text messages. macOS only (CoreBluetooth).
//   swift build && .build/debug/MacCentral pair "tether1:…"
// In your own package use `.package(url: "https://github.com/amirwmr/tether", from: "1.1.0")` instead of the path.
import PackageDescription

let package = Package(
    name: "MacCentral",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(name: "Tether", path: "../../.."),
    ],
    targets: [
        // macOS grants Bluetooth only to a program that carries a usage string: the Info.plist is linked into the
        // binary's __TEXT,__info_plist section.
        .executableTarget(name: "MacCentral", dependencies: [.product(name: "TetherCore", package: "Tether")],
                          exclude: ["Info.plist"],
                          linkerSettings: [.unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                                                         "-Xlinker", "__info_plist",
                                                         "-Xlinker", "Sources/MacCentral/Info.plist"])]),
    ]
)
