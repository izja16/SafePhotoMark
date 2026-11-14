// swift-tools-version:6.0
//
// 脱敏水印 / Safe Photo Mark 工程内嵌的本地 Swift 包清单。
//
// 上游：https://github.com/swhitty/SwiftDraw
// 提交：见同目录 UPSTREAM_COMMIT.txt
//
// 相对上游 Package.swift 的改动（源码本身未修改）：
// 1. 只保留 SwiftDraw / SwiftDrawDOM 两个库目标，去掉测试与可执行产品，
//    避免在应用工程中一并编译。
// 2. CommandLine 目录仍编入库目标：其中包含 SVG.Options 扩展和 Insets 等类型，
//    库代码会引用它们；该目录不是 @main 可执行入口。
//
// 许可证见 LICENSE.txt（zlib）。

import PackageDescription

let package = Package(
    name: "SwiftDraw",
    platforms: [
        .macOS(.v10_15), .iOS(.v13), .tvOS(.v13), .watchOS(.v6), .visionOS(.v1)
    ],
    products: [
        .library(name: "SwiftDraw", targets: ["SwiftDraw"]),
    ],
    targets: [
        .target(
            name: "SwiftDraw",
            dependencies: ["SwiftDrawDOM"],
            path: "SwiftDraw/Sources",
            swiftSettings: .upcomingFeatures
        ),
        .target(
            name: "SwiftDrawDOM",
            dependencies: [],
            path: "DOM/Sources"
        ),
    ]
)

extension Array where Element == SwiftSetting {
    static var upcomingFeatures: [SwiftSetting] {
        [
            .enableUpcomingFeature("ExistentialAny"),
            .enableUpcomingFeature("InternalImportsByDefault"),
            .enableUpcomingFeature("MemberImportVisibility"),
            .swiftLanguageMode(.v6)
        ]
    }
}
