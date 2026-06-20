# 一百伏特 (OneHundredVolt)

> 爱发电音频播放增强项目 · iOS + Android + Kotlin Multiplatform

非官方的第三方播客播放器，通过爱发电用户 Cookie 读取已赞助项目数据，提供专注的播放体验。

## 项目结构

```
OneHundredVolt/
├── iosApp/                       iOS 应用（SwiftUI + AVFoundation，iOS 17+）
├── androidApp/                   Android 应用（Compose + Media3，minSdk 26）
├── shared/                       KMP 共享模块（数据模型 / API / DB / 同步 / 进度）
│   └── src/
│       ├── commonMain/           共享业务代码
│       ├── androidMain/          Android expect/actual
│       ├── iosMain/              iOS expect/actual
│       ├── commonTest/           共享单元测试
│       ├── androidInstrumentedTest/  Android 设备测试（占位）
│       └── iosTest/              iOS 测试（占位）
├── docs/                         项目文档
│   ├── DESIGN.md                 设计文档
│   ├── architecture.md           跨平台架构（v1.6+）
│   ├── api-reference.md
│   ├── CHANGELOG.md              版本日志
│   └── plans/                    各版本实施计划
├── scripts/                      构建辅助脚本
├── gradle/                       Gradle 版本目录
└── version.json                  Android OTA 版本信息（jsDelivr CDN）
```

## 构建

### 前置条件
- **Android**：JDK 21、Android SDK (API 35)、cmdline-tools
- **iOS**：Xcode 26+、iOS 17 SDK
- **KMP 共享模块**：Gradle 8.14.4（通过 wrapper 管理）

### Android
```bash
./gradlew :shared:assembleDebug         # 共享模块（Android target）
./gradlew :androidApp:assembleDebug     # Debug APK
./gradlew :androidApp:assembleRelease   # Release APK（需要签名配置）
```

### iOS
打开 `iosApp/OneHundredVolt.xcodeproj`，选择 OneHundredVolt scheme，⌘B 构建。

或在命令行：
```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild -project iosApp/OneHundredVolt.xcodeproj \
             -scheme OneHundredVolt \
             -destination 'generic/platform=iOS Simulator' \
             -configuration Debug build
```

### 共享模块（iOS Framework）
```bash
./gradlew :shared:linkIosSimulatorArm64FrameworkRelease
./gradlew :shared:linkIosArm64FrameworkRelease
```
产物：`shared/build/bin/{iosSimulatorArm64,iosArm64}/releaseFramework/Shared.framework`

## 测试

```bash
./gradlew :shared:testDebugUnitTest     # 共享单元测试（JVM）
./gradlew :androidApp:testDebugUnitTest # Android 单元测试
./gradlew :shared:allTests              # 共享所有测试
```

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | 整体设计（UI / 数据模型 / 播放器架构） |
| [docs/architecture.md](docs/architecture.md) | 跨平台架构（共享层 / 平台独立层 / 差异清单） |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 版本变更日志 |
| [docs/plans/v1.6-cross-platform-review.md](docs/plans/v1.6-cross-platform-review.md) | 当前进行中的 v1.6 实施计划 |

## 技术栈

| 层 | iOS | Android | 共享 |
|---|---|---|---|
| UI | SwiftUI | Compose (Material 3) | — |
| 状态管理 | @Observable | StateFlow + collectAsStateWithLifecycle | StateFlow |
| 音频播放 | AVFoundation + AVPlayer | Media3 ExoPlayer | — |
| 响度增强 | MTAudioProcessingTap | BaseAudioProcessor | — |
| 后台播放 | AVAudioSession | MediaSessionService | — |
| 锁屏 | MPNowPlayingInfoCenter | MediaSession | — |
| HTTP | URLSession | OkHttp | Ktor 3.x |
| JSON | — | — | kotlinx.serialization |
| 安全存储 | Keychain | EncryptedSharedPreferences | SecureStorage expect/actual |
| 图片加载 | NSCache | Coil 3 | — |

## 关联链接

- [爱发电](https://afdian.com)
- 仓库：https://github.com/imzhizi/OneHundredVolt

## License

MIT — 见 [LICENSE](LICENSE)