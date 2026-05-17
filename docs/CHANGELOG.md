# 版本更新日志

> 本文件记录所有版本的功能变更。每个版本标注涉及平台（iOS / Android / Shared）和状态。

---

## v1.5 — Android OTA 应用内更新

> 状态：✅ 已实现
> 日期：2026-05-17
> 平台：Android
> 对应设计文档：`plans/v1.5-ota-update.md`

### 新增功能

| # | 功能 | 说明 |
|---|------|------|
| 1 | GitHub Actions 自动发布 | push tag `v*` 触发 CI/CD，自动构建签名 APK 并上传到 GitHub Release，同步更新 `version.json` |
| 2 | 应用内版本检测（多节点降级） | 通过 jsDelivr CDN 读取 `version.json`，失败依次降级到 ghproxy → raw.githubusercontent.com |
| 3 | 国内镜像加速下载（多节点降级） | 下载依次尝试 github.com → ghproxy → ghfast，开了代理必然成功 |
| 4 | 更新弹窗 UI | 三态弹窗：确认更新 → 下载进度 → 安装引导；下载失败可重试 |
| 5 | 设置页手动检查更新 | "关于"区域「检查更新」按钮，失败显示错误信息并支持重试 |
| 6 | 启动自动检测 + 频率控制 | 启动时自动检测，有新版本弹窗提示；同版本 7 天内只提示一次，出新版本立即提示并重置计时 |
| 7 | APK 下载到公共 Downloads | 下载到系统 Downloads 目录，文件管理器可见，安装失败也可手动安装 |
| 8 | 安装权限引导 | 点击安装前检查 `canRequestPackageInstalls()`，未授权自动跳到系统设置引导开启 |

### 涉及文件

**新建：**
- `.github/workflows/release.yml`
- `version.json` — 仓库根目录版本信息（jsDelivr CDN 分发）
- `androidApp/.../platform/AppUpdater.kt`
- `androidApp/.../components/UpdateDialog.kt`
- `androidApp/src/main/res/xml/file_paths.xml`

**修改：**
- `androidApp/src/main/AndroidManifest.xml` — 新增 `REQUEST_INSTALL_PACKAGES`、`WRITE_EXTERNAL_STORAGE`(≤API28)、FileProvider
- `androidApp/src/main/kotlin/com/ohv/android/OhvApplication.kt` — 启动检测 + 频率控制 + StateFlow 暴露结果
- `androidApp/src/main/kotlin/com/ohv/android/MainActivity.kt` — 订阅 pendingUpdate 展示弹窗
- `androidApp/src/main/kotlin/com/ohv/android/features/settings/SettingsScreen.kt` — 手动检查入口、错误信息展示、竞态修复

---

## v1.4 — UI 精细打磨 + 播放器优化

> 状态：✅ 已实现
> 日期：2026-05-16
> 平台：iOS / Android
> 对应设计文档：`plans/v1.4-ui-polish.md`

### iOS 变更

| # | 功能 | 说明 |
|---|------|------|
| 1 | MiniPlayer 进度条置顶 | 进度条从标题下方提取为顶栏贯穿式（2pt），内容区更紧凑 |
| 2 | MiniPlayer 结构重构 | 外层 VStack（进度条+内容），ScaleButtonStyle 精确控制 |
| 3 | Player 控制栏精简 | 移除上/下一首按钮，保留快退15s/播放暂停/快进30s，图标放大到 30pt |
| 4 | 倍速选项扩展 | 新增 0.9x、1.1x 档位（原 0.75/0.85/1.0/1.25/1.5/2.0） |
| 5 | Creator 头部居中布局 | HStack→VStack 垂直居中，头像放大至 80pt，名称/doing/统计纵向排列 |
| 6 | Home 创作者卡片增强 | 新增头像(36pt)+doing 描述行，列表项间 Divider 分隔线 |
| 7 | 播放列表右滑删除 | `.onDelete` → `.swipeActions(edge: .trailing)` 更符合 iOS 习惯 |
| 8 | 播放列表剩余时间 | 当前播放项显示"还有 X 分钟"而非总时长 |
| 9 | 外链按钮统一风格 | Album/Creator 页面统一为"爱发电"+arrow.up.right 文字按钮 |
| 10 | 登出清理音频缓存 | `logout()` 新增 `audioCache.clearCache()` 调用 |

### Android 变更

| # | 功能 | 说明 |
|---|------|------|
| 1 | InAppBrowserSheet 组件 | 新增底部弹窗 WebView 浏览器（对齐 iOS SafariView） |
| 2 | 导航栏标题居中 | CreatorSelectScreen / LoginWebViewScreen 标题 TextAlign.Center |

### 版本号

- Marketing Version: 1.1 → **1.4**
- Build Version: 2 → **3**

---

## v1.2.0 — Android 功能对齐 + Tanh 响度增强

> 状态：✅ 已实现
> 日期：2026-05-13
> 平台：Android
> 对应设计文档：`plans/v1.1-playlist-loudness.md`、`plans/v1.0-android-implementation.md`

### 新增功能

| # | 功能 | 说明 |
|---|------|------|
| 1 | 创作者详情页 | 对应 iOS `CreatorView`：头像、名称、doing、统计、专辑列表（含未购买锁标志）、爱发电主页链接 |
| 2 | 全部创作者页 | 对应 iOS `AllCreatorsView`：创作者列表（头像、名称、doing/专辑数），点击进入创作者详情 |
| 3 | 播放列表拖拽排序 | 长按拖拽手柄（≡）可重新排列播放列表顺序，自动保持当前播放项在队首 |
| 4 | 播放列表滑动删除 | 左滑播放列表项可删除；删除当前播放项时自动播放下一首 |
| 5 | 剧集完成状态 | 专辑详情中已播完的剧集显示为 0.4 透明度 + checkmark 覆盖，对应 iOS 完成 UI |
| 6 | 加入队列按钮 | 专辑详情每行新增 "+" 按钮（已在队列变为 ✓）和 "▶ 播放" 按钮，对应 iOS 双按钮布局 |
| 7 | Tanh 软削波响度增强 | 替换原 `LoudnessEnhancer` 为 `TanhLoudnessAudioProcessor`（`tanh(sample * gain)` 软削波），6dB 增益无硬削波，与 iOS `MTAudioProcessingTap` 对齐 |
| 8 | 应用图标 | 闪电图标（黄色 #FFE500 + 深黑 #0A0A0A），Adaptive Icon 支持圆形/方形 |

### 涉及文件

**新建：**
- `androidApp/.../features/creator/CreatorScreen.kt`
- `androidApp/.../platform/TanhLoudnessAudioProcessor.kt`
- `androidApp/src/main/res/drawable/ic_launcher_foreground.xml`
- `androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `androidApp/src/main/res/values/ic_launcher_background.xml`

**修改：**
- `androidApp/.../features/navigation/AppNavHost.kt` — 新增 AllCreators/Creator 路由
- `androidApp/.../features/home/HomeScreen.kt` — "查看全部创作者" + 播放列表拖拽/滑动删除
- `androidApp/.../features/album/AlbumDetailScreen.kt` — 完成状态 + 加入队列/播放双按钮
- `androidApp/.../platform/AudioPlaybackService.kt` — TanhLoudnessAudioProcessor 替换 LoudnessEnhancer
- `androidApp/.../platform/AudioPlayerManager.kt` — reorderPlaylist / removeFromPlaylist
- `androidApp/src/main/AndroidManifest.xml` — 添加 icon 引用

---

## v1.1.0 — iOS 修复与优化 + Android 首页播放列表 + 响度增强

> 状态：✅ 已实现
> 日期：2026-05-12
> 平台：iOS / Android
> 对应设计文档：`plans/v1.1-ios-fixes.md`、`plans/v1.1-playlist-loudness.md`

### iOS 变更

| # | 功能 | 说明 |
|---|------|------|
| 1 | MiniPlayer 进度恢复 | 杀 App 重启后恢复 currentTime 和 duration |
| 2 | 时长格式统一 | 列表显示总分钟数，播放页显示 mm:ss |
| 3 | 音频缓存 | `AudioCacheService`（LRU 500MB），播放同时后台缓存 |
| 4 | 播放列表行布局 | 统一封面+标题+时长+拖拽手柄+状态图标 |
| 5 | 专辑列表行布局 | 统一封面+标题+时长+加入队列/播放双按钮+完成状态 |
| 6 | 响度增强 | `MTAudioProcessingTap` + tanh 软削波 +6dB |
| 7 | 设置页响度开关 | 播放 section 增加音量增强开关 |

### Android 变更

| # | 功能 | 说明 |
|---|------|------|
| 1 | 首页播放列表 | 队列内嵌首页底部，对应 iOS HomeView |
| 2 | 响度增强（初版） | `LoudnessEnhancer` +6dB + 设置页开关 |

---

## v1.0.0 — Android 版首发 + KMP 迁移

> 状态：✅ 已实现
> 日期：2026-05-11
> 平台：iOS / Android / Shared
> 对应设计文档：`plans/v1.0-android-implementation.md`、`DESIGN.md`、`api-reference.md`

### 核心变更

| # | 功能 | 说明 |
|---|------|------|
| 1 | KMP Monorepo 架构 | `iosApp + androidApp + shared` 三层结构 |
| 2 | shared 业务逻辑 | Creator/Album/AudioItem 模型、AfdianApiService、DatabaseService、SyncService、PlaybackProgressStore |
| 3 | expect/actual 平台层 | SecureStorage、KeyValueStore、PlatformPaths |
| 4 | Android Onboarding | WelcomeScreen → LoginWebView → CreatorSelect → SyncProgress |
| 5 | Android 主界面 | HomeScreen、AlbumDetailScreen、SettingsScreen |
| 6 | Android 播放器 | AudioPlaybackService（Media3）+ AudioPlayerManager + PlayerScreen + MiniPlayerBar |
| 7 | Android 主题 | 深色主题 #0A0A0A 背景 + #FFE500 黄色 accent |
