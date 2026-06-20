# 一百伏特 — 跨平台架构

> 配套计划文档：`docs/plans/v1.6-cross-platform-review.md`
> 配套设计：`docs/DESIGN.md`
> v1.6 之后状态：共享层接入完成、平台差异收敛

## §1 模块依赖图

```
┌─────────────────────────────────────────────────────────────┐
│                    iOS App (SwiftUI)                        │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ AudioPlayer  │  │  NowPlaying  │  │  LoginWebView    │  │
│  │ Service      │  │  Service     │  │  (WKWebView)     │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                    │            │
│  ┌──────▼─────────────────▼────────────────────▼─────────┐  │
│  │           iOS 适配层 (Swift)                          │  │
│  │  - Kotlin 异常 → iOS Notification 映射                │  │
│  │  - @MainActor 包装                                   │  │
│  │  - WKWebsiteDataStore 清理                            │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │ import Shared                     │
│  ┌──────────────────────▼───────────────────────────────┐  │
│  │         Shared.xcframework (Kotlin/Native)           │  │
│  │         commonMain + iosMain                         │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                  Android App (Compose)                      │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ AudioPlayer  │  │ AudioPlayback│  │  LoginWebView    │  │
│  │ Manager      │  │ Service      │  │  (WebView)       │  │
│  │ (StateFlow)  │  │ (Media3)     │  │                  │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                    │            │
│  ┌──────▼─────────────────▼────────────────────▼─────────┐  │
│  │           Android 适配层 (Kotlin)                      │  │
│  │  - Coroutine 绑定 + StateFlow                         │  │
│  │  - Media3 MediaController                             │  │
│  │  - MediaStore.Downloads                               │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │ depends on :shared                │
│  ┌──────────────────────▼───────────────────────────────┐  │
│  │         :shared module (Kotlin/JVM)                  │  │
│  │         commonMain + androidMain                     │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

**关键边界**：
- `commonMain` 同时被两个平台 target 编译（iOS → Kotlin/Native，Android → Kotlin/JVM）
- `iosMain` / `androidMain` 提供 expect/actual 实现，**不允许**跨目标引用
- 平台层（App + Features）只依赖 shared 模块的 public API，不直接访问 iosMain/androidMain 的 actual

---

## §2 共享部分 (commonMain)

| 模块 | 内容 |
|---|---|
| `models/` | Creator、Album、AudioItem 数据类（`@Serializable`） |
| `api/` | AfdianApiService（Ktor 3.x）、ApiModels、ApiError |
| `db/` | DatabaseService（JSON 文件 + StateFlow + 原子写） |
| `sync/` | SyncService（三阶段同步协调：creators → albums → probe → catalog） |
| `progress/` | PlaybackProgressStore（播放进度 + 完播标记 + 防抖持久化） |
| `platform/SecureStorage` | expect/actual 密钥存储抽象 |
| `platform/KeyValueStore` | expect/actual KV 持久化抽象 |
| `platform/PlatformPaths` | expect/actual 文件路径抽象 |
| `platform/WebSessionCleaner` | expect/actual Web 会话清理（v1.6 新增） |

**设计原则**：
- commonMain 中**不**允许出现 `android.*` / `androidx.*` / `UIKit` / `AVFoundation` 等平台 API
- 时间统一用 `Long`（epoch ms），避免 `kotlinx.datetime` 在两端的不一致
- URL 用 `String` 而非 `URL` 类型（避免 Foundation ↔ java.net.URL 桥接复杂度）
- 所有 IO 用 `kotlinx.coroutines.withContext(Dispatchers.IO)` 切换

---

## §3 iOS 独立部分 (iosApp + iosMain)

| 模块 | 内容 | 不共享的原因 |
|---|---|---|
| `AudioPlayerService.swift` | AVPlayer 封装 + 状态机 | AVFoundation 是 iOS 专属 |
| `NowPlayingService.swift` | MPNowPlayingInfoCenter + MPRemoteCommandCenter | iOS 锁屏 / 控制中心 API |
| `LoudnessBoostTap.swift` | MTAudioProcessingTap + tanh 软削波 | Core Audio 专属 |
| `CachedImage.swift` | NSCache + FileManager | SwiftUI 集成更直接 |
| Onboarding Views | SwiftUI fullScreenCover 链 | SwiftUI 专属 |
| iOS 适配层 | Kotlin 异常 → Notification 映射 | SwiftUI `.onReceive` 模式 |
| `Shared.WebSessionCleaner` actual | WKWebsiteDataStore 操作 | iOS WebKit 专属 |

**iOS 特有配置**：
- `Info.plist` 的 `UIBackgroundModes: audio` 支持后台播放
- `OneHundredVolt.entitlements` 当前为空（无特殊 entitlement）
- Keychain service identifier: `com.onehundredvolt.afdian`

---

## §4 Android 独立部分 (androidApp + androidMain)

| 模块 | 内容 | 不共享的原因 |
|---|---|---|
| `AudioPlaybackService.kt` | MediaSessionService + ExoPlayer | Media3 是 Android 专属 |
| `AudioPlayerManager.kt` | MediaController + StateFlow | Android 服务绑定模式 |
| `TanhLoudnessAudioProcessor.kt` | BaseAudioProcessor + tanh | Media3 音频管线 |
| `InAppBrowser.kt` | ModalBottomSheet + WebView | Material 3 模式 |
| `UpdateDialog.kt` + `AppUpdater.kt` | OTA 更新（v1.5 起） | Android 专属 OTA 流程 |
| `OhvApplication.kt` | 应用初始化 + POST_NOTIFICATIONS | Android Application 生命周期 |
| Compose Screens | 全套 `@Composable` | Compose 专属 |
| `MainActivity.kt` | 单 Activity 入口 | Android 入口约定 |
| `Shared.WebSessionCleaner` actual | CookieManager 操作 | Android WebView 专属 |

**Android 特有配置**：
- `foregroundServiceType="mediaPlayback"` 支持前台播放服务
- `POST_NOTIFICATIONS`（Android 13+）运行时权限
- `REQUEST_INSTALL_PACKAGES`（OTA 安装）
- EncryptedSharedPreferences（SecureStorage backend）

---

## §5 计划保持差异清单

### 5.1 用户决策导致的差异
- **iOS 无 OTA**（Android 有 AppUpdater）
- **Android 响度无 UI 开关**（iOS 也无；两端均硬编码）

### 5.2 平台 API 差异（不消除）
| 关注点 | iOS | Android |
|---|---|---|
| 播放引擎 | AVFoundation + AVPlayer | Media3 ExoPlayer |
| 响度增强 | MTAudioProcessingTap (tanh) | BaseAudioProcessor (tanh) |
| 后台播放 | AVAudioSession `.playback` | MediaSessionService + foregroundServiceType=mediaPlayback |
| 锁屏 / 控制中心 | MPNowPlayingInfoCenter + MPRemoteCommandCenter | MediaSession（默认 MediaNotificationProvider） |
| 安全存储 | Keychain（kSecClassGenericPassword） | EncryptedSharedPreferences（AES256_GCM/SIV） |
| KV 持久化 | NSUserDefaults | SharedPreferences |
| 图片加载 | NSCache + 自绘 CachedImage | Coil 3 |
| Web 登录 | WKWebView + WKHTTPCookieStore | WebView + CookieManager |
| HTTP 引擎 | URLSession（Ktor Darwin） | OkHttp（Ktor OkHttp） |
| JSON | kotlinx.serialization | kotlinx.serialization |
| 状态管理 | @Observable | StateFlow + collectAsStateWithLifecycle |
| UI 框架 | SwiftUI | Compose |
| 导航 | NavigationStack + NavigationPath | Navigation Compose |
| 通知渠道 | 不适用 | 自定义 channel + small icon |
| OTA | 不做 | GitHub Actions + jsDelivr/ghproxy 多节点 |
| 应用入口 | `@main App` + SceneDelegate | `Application` + `MainActivity` |

### 5.3 行为差异（v1.6 对齐后）
| 关注点 | v1.6 前 | v1.6 后 |
|---|---|---|
| 清除数据时清音频缓存 | iOS: clearData ❌ / logout ✅ | **对齐**：两端 clearData 都清缓存 |
| WebView session 清理 | iOS: logout 清全部 cookies | **对齐**：iOS 按 afdian 域名过滤；Android 用 CookieManager |
| 进度轮询频率 | Android: 5Hz（StateFlow 推送） | **降频**：1Hz + 仅 visible 时启用 |
| 数据库原子写 | iOS: ✅ / shared: ❌ | **统一**：shared 改原子写（临时文件 + rename） |
| 401 错误处理 | iOS: NotificationCenter / shared: 抛异常 | **统一**：shared 抛 ApiError.HttpError(401)，iOS 适配层翻译为 `.tokenExpired` Notification |
| MiniPlayer loading 阶段 | Android: ❌ 空白 | **对齐**：两端都渲染 currentItem |
| iOS KMP 接入 | ❌ ~1100 行 Swift 重复实现 | **接入**：删重复，改用 `Shared.*` |

---

## §6 通信模式

### 6.1 shared → 平台层
- `expect/actual` 解决平台差异（SecureStorage / KeyValueStore / PlatformPaths / WebSessionCleaner）
- actual 内部禁止回调 commonMain 代码，避免反向依赖

### 6.2 平台层 → shared
- shared 暴露 `suspend fun` 和 `StateFlow`
- iOS 用 `async/await` + `for await` 订阅；订阅端在 `@MainActor` 上下文
- Android 用协程 + `collectAsStateWithLifecycle`

### 6.3 iOS 内部
- SwiftUI views ↔ `@MainActor @Observable` services（直接方法调用）
- 跨 view 通信：`NotificationCenter` + `.onReceive`（如 `.tokenExpired`、`.didSyncComplete`）
- iOS 适配层监听 shared 的异常抛出并 post Notification（Kotlin 异常 → iOS 事件）

### 6.4 Android 内部
- Compose ↔ `ViewModel` + `StateFlow`
- 跨屏通信：`MutableSharedFlow<UiEvent>`（替代 NotificationCenter 的角色）
- 跨进程：`MediaController` ↔ `MediaSessionService`（通过 `buildAsync().addListener` 异步绑定）

### 6.5 异常处理
- shared 模块抛 `ApiError`、`SyncError` 等 sealed class 异常
- iOS 适配层 catch 后映射到 `Notification.Name`（如 `.tokenExpired`）
- Android 直接 catch 后 emit 到 UiEvent SharedFlow
- **不**在 shared 中处理 UI 反馈（保持 UI 无关）

---

## §7 构建与发布

### 7.1 shared 模块
```bash
./gradlew :shared:assembleSharedDebugXCFramework
./gradlew :shared:assembleSharedReleaseXCFramework
```
产物：`shared/build/xcframeworks/Shared.xcframework`
- `ios-arm64`（真机）
- `ios-arm64_x86_64-simulator`（模拟器）

构建脚本 `iosApp/embed_shared_framework.sh`（v1.6 改）：
1. 调用 Gradle 任务
2. 拷贝 xcframework 到 `iosApp/Frameworks/Shared.xcframework`
3. 由 `Package.swift` 的 `binaryTarget` 引用

### 7.2 Android
```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleRelease
./gradlew :androidApp:bundleRelease  # AAB（Google Play）
```

### 7.3 iOS
Xcode 构建通过 SPM 解析 `Package.swift` → 自动引入 `Shared.xcframework`。
```bash
xcodebuild -scheme OneHundredVolt -destination 'generic/platform=iOS' build
```

### 7.4 CI/CD
- Android OTA：`.github/workflows/release.yml`（push tag `v*` 触发）
- iOS：通过 Xcode Archive + TestFlight（手动或 fastlane）

---

## §8 测试策略

| 层 | 工具 | 范围 |
|---|---|---|
| Shared | `kotlin.test` (JVM target) | DatabaseService 原子写、ApiService error mapping、WebSessionCleaner expect/actual |
| iOS | XCTest + `@MainActor` 测试 | AudioPlayerService 状态机、NowPlayingService race 修复 |
| Android | JUnit + Robolectric + Compose UI Test | AudioPlayerManager 异步绑定、AppUpdater MediaStore、SettingsScreen 清空缓存 |
| 跨平台 | 手动冒烟 | 登录 → 同步 → 播放 → 杀进程 → 恢复（两端） |

**关键回归用例**：
1. 连续 100 次写入数据库文件不损坏
2. 写一半 `kill -9` 后启动不 crash
3. 同 postId 并发启动 5 次下载，仅产生一个缓存文件
4. MiniPlayerBar 在 loading 阶段持续渲染
5. 401 响应在两端都触发登出流程
6. OTA APK 下载到 Android 10+ 设备的 Downloads 目录成功