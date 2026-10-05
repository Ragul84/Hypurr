// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "HypurrKit",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "HypurrKit", targets: ["HypurrKit"]),
        .library(name: "HypurrUI", targets: ["HypurrUI"]),
    ],
    dependencies: [
        // libwebrtc for the remote screen viewer (hardware H.264 over WebRTC).
        .package(url: "https://github.com/stasel/WebRTC.git", exact: "153.0.0"),
        // Terminal emulator for agent install and sign-in. 1.19+ adds a build-tool
        // plugin every Xcode build would have to trust.
        .package(url: "https://github.com/migueldeicaza/SwiftTerm.git", exact: "1.18.0"),
    ],
    targets: [
        .target(name: "HypurrKit", resources: [.copy("Resources/ThirdPartyNotices"), .process("Resources/ProviderIcons.xcassets")]),
        .target(
            name: "HypurrUI",
            dependencies: [
                "HypurrKit",
                .product(name: "WebRTC", package: "WebRTC", condition: .when(platforms: [.iOS])),
                .product(name: "SwiftTerm", package: "SwiftTerm"),
            ],
            resources: [.process("Resources")]
        ),
        .testTarget(name: "HypurrKitTests", dependencies: ["HypurrKit"], resources: [.copy("Fixtures")]),
        .testTarget(name: "HypurrUITests", dependencies: ["HypurrUI", "HypurrKit"]),
    ]
)
