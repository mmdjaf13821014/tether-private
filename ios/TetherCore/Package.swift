// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "TetherCore",
    platforms: [.iOS(.v16), .macOS(.v14)],
    products: [
        .library(name: "TetherCore", targets: ["TetherCore"]),
        .executable(name: "tether-vectors", targets: ["tether-vectors"]),
        .executable(name: "tether-central", targets: ["tether-central"]),
    ],
    targets: [
        // libopus 1.5.2 (BSD-3-Clause), vendored, floating point, no assembly.
        .target(name: "COpus", path: "Sources/COpus", publicHeadersPath: "include",
                cSettings: [.headerSearchPath("config"), .headerSearchPath("celt"), .headerSearchPath("silk"),
                            .headerSearchPath("silk/float"), .headerSearchPath("src"),
                            .define("HAVE_CONFIG_H")]),
        .target(name: "TetherCore", dependencies: ["COpus"]),
        .executableTarget(name: "tether-vectors", dependencies: ["TetherCore"]),
        // Mac test central; the embedded Info.plist carries the Bluetooth usage string TCC requires.
        .executableTarget(name: "tether-central", dependencies: ["TetherCore"], exclude: ["Info.plist"],
                          linkerSettings: [.unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                                                         "-Xlinker", "__info_plist", "-Xlinker", "Sources/tether-central/Info.plist"])]),
        .testTarget(name: "TetherCoreTests", dependencies: ["TetherCore"],
                    resources: [.copy("vectors.json")]),
    ]
)
