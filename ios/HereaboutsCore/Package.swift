// swift-tools-version: 6.0
// HereaboutsCore: the iPhone app's logic, with no UI. It reads the bundle that `npm run app:bundle` makes
// (apps/shared/bundle) and must give the same answers as the website's own code, which the tests check
// against apps/shared/bundle/expected/. Builds for iOS 17, macOS 14 (for Xcode previews) and Linux (for CI).
import PackageDescription

let package = Package(
    name: "HereaboutsCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "HereaboutsCore", targets: ["HereaboutsCore"]),
    ],
    targets: [
        .target(name: "HereaboutsCore"),
        .testTarget(name: "HereaboutsCoreTests", dependencies: ["HereaboutsCore"]),
    ],
    swiftLanguageModes: [.v6]
)
