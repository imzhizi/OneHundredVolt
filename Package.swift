// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.
//
// ⚠️ 注意：这个 Package.swift 用于声明 SPM 依赖。
// 在 Xcode 中创建 App Target 后，通过 File → Add Package Dependencies
// 手动添加：https://github.com/stephencelis/SQLite.swift
// 版本：0.15.3 或更高

import PackageDescription

let package = Package(
    name: "OneHundredVolt",
    platforms: [
        .iOS(.v17)
    ],
    dependencies: [
        .package(
            url: "https://github.com/stephencelis/SQLite.swift",
            from: "0.15.3"
        )
    ],
    targets: [
        .target(
            name: "OneHundredVolt",
            dependencies: [
                .product(name: "SQLite", package: "SQLite.swift")
            ]
        )
    ]
)
