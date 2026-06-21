// swift-tools-version: 5.9
//
// iOS 端的本地 Package 描述（SPM 方案 X）
//
// 用法：Xcode → File → Add Package Dependencies → Add Local...
//      选择本 Package.swift 所在目录，OneHundredVolt target 即可依赖 Shared。
//
// 或将 Shared.xcframework 直接拖入 Xcode project：
//      1. ./scripts/build-shared-framework.sh 生成产物
//      2. Xcode → File → Add Files... → 选择 shared/build/Shared.xcframework
//      3. Target → Frameworks, Libraries, and Embedded Content → Embed & Sign
//
// v1.6 改动：添加 Shared 二进制 framework 依赖（KMP 编译产物）

import PackageDescription

let package = Package(
    name: "OneHundredVolt",
    platforms: [
        .iOS(.v17)
    ],
    products: [
        .library(
            name: "Shared",
            targets: ["Shared"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "Shared",
            path: "../shared/build/Shared.xcframework"
        )
    ]
)