# 一百伏特 — 播客播放器

> 爱发电音频播放增强项目

## 核心概念

### 与爱发电的关系

- **非官方应用**：一百伏特是第三方播客播放器，不隶属于爱发电
- **数据来源**：通过爱发电用户 Cookie 读取用户在发电项目的数据
- **视觉独立**：不使用爱发电品牌色和视觉体系，拥有完全独立的 UI 设计

### 专辑逻辑

| 创作者项目情况 | App 处理方式 |
|---------------|-------------|
| 有合集（专辑） | 每个合集映射为一个专辑 |
| 无合集 | 该项目所有音频合并为一个"未分类"默认专辑 |

---

## 功能列表

| 功能 | 说明 |
|------|------|
| 首次登录 | WKWebView 内嵌爱发电登录页，自动提取 `auth_token` 存入 Keychain |
| 同步数据 | 按项目/合集结构拉取音频列表，存为本地 JSON |
| 播放音频 | 播放、暂停、进度拖动、快进 30s / 快退 15s |
| 播放速度 | 0.75x / 0.85x / 1.0x / 1.25x / 1.5x / 2.0x，在播放器页切换 |
| 播放列表 | 首页底部内嵌，顺序播放，播完停止 |
| 睡眠定时 | 15 / 30 / 45 / 60 分钟 |
| 进度记忆 | 自动记住每个音频的播放位置（本地 UserDefaults） |
| 本地存储 | 所有数据存本地 JSON 文件，不上云 |
| 后台音频 | AVAudioSession `.playback`，支持锁屏 / 控制中心 |
| 图片缓存 | NSCache 内存缓存（CachedImage 组件） |

---

## 页面结构

```
     ┌─────────────────────────┐
     │    首次引导 (Onboarding)   │
     │  欢迎 → 登录 → 项目选择    │
     └───────────┬─────────────┘
                 ▼
     ┌─────────────────────────┐
     │      主页 (Home)          │
     │  继续收听 + 创作者专辑     │
     │  + 底部播放列表            │
     └───────────┬─────────────┘
                 │
       ┌─────────┴─────────┐
       ▼                   ▼
┌─────────────┐     ┌─────────────┐
│  专辑详情   │     │   播放器    │
│  (Album)    │     │  (Player)  │
└─────────────┘     └─────────────┘

设置入口：主页右上角 ⚙️
创作者主页：点击创作者名称，WKWebView 打开其爱发电主页
```

---

## 首次引导流程

### Step 1：欢迎页

```
┌─────────────────────────────────────┐
│           ⚡                        │
│         一百伏特                     │
│     专注播放的播客伴侣                │
│   [ 登录爱发电账户 ]                  │
└─────────────────────────────────────┘
```

### Step 2：App 内登录

```
┌─────────────────────────────────────┐
│  ◀ 返回           登录爱发电          │
├─────────────────────────────────────┤
│  ┌─────────────────────────────┐   │
│  │    [WKWebView]              │   │
│  │    https://afdian.com/login │   │
│  └─────────────────────────────┘   │
│  [ ✓ 我已登录，继续 ]               │
└─────────────────────────────────────┘
```

**实现方式：**
1. `WKWebView` 加载 `https://afdian.com/login`
2. 自动轮询 `WKHTTPCookieStore` 检测 `auth_token` cookie
3. 同时提供手动「我已登录，继续」按钮
4. 找到 `auth_token` 后调用 `/api/my/profile` 验证有效性
5. 验证通过后存入 **Keychain**，进入项目选择
6. App 启动时优先检查 Keychain，有 token 直接跳过 Onboarding

### Step 3：项目选择

```
┌─────────────────────────────────────┐
│  ◀ 返回          选择要同步的项目      │
├─────────────────────────────────────┤
│  ┌─────────────────────────────┐   │
│  │ ✓ 反派影评                  │   │
│  └─────────────────────────────┘   │
│  [ 确认同步 ]                       │
└─────────────────────────────────────┘
```

只显示有音频内容的项目，用户可多选。

### Step 4：同步中

```
┌─────────────────────────────────────┐
│           ⚡                        │
│         正在同步...                  │
│    ✓ 反派影评 — N 期音频             │
└─────────────────────────────────────┘
```

同步完成后进入主页。

---

## 页面详情

### 主页（Home）

```
┌─────────────────────────────────────┐
│  一百伏特              [⚙️ 设置]    │
├─────────────────────────────────────┤
│  创作者名称            >            │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐      │
│  │封面│ │封面│ │封面│ │封面│      │
│  └────┘ └────┘ └────┘ └────┘      │
├─────────────────────────────────────┤
│  播放列表               [清空]      │
│  ┃ ▐ 封面  标题              时长   │  ← 当前播放（高亮 + 竖条）
│    封面  标题              时长     │
│    封面  标题              时长     │
└─────────────────────────────────────┘
```

**Section 说明：**
1. **创作者区**：按创作者分组，横向滚动专辑卡片；专辑标题超长截断不换行；点击创作者名称打开其爱发电主页
2. **播放列表**：内嵌在 ScrollView 底部；支持拖拽排序和左滑删除；当前播放高亮 + 左侧竖条 + 播放中波形图标；已播放进度条；点击任意单集将其置顶并立即播放，自动打开全屏播放页；`+` 添加、「清空」按钮
3. **迷你播放器**：在屏幕最下方的显示当前播放单集的播放条

### 专辑详情（Album Detail）

```
┌─────────────────────────────────────┐
│  ◀ 返回        专辑标题（inline）    │
├─────────────────────────────────────┤
│         ┌──────────────┐            │
│         │   专辑封面   │            │
│         └──────────────┘            │
│  全 N 期 · 约 X 小时                │
│  [ ▶ 全部播放 ]                     │
├─────────────────────────────────────┤
│  ① 封面  E01 标题            45:30 ＋│
│  ② 封面  E02 标题            38:12 ＋│
└─────────────────────────────────────┘
```

**功能：**
- 「全部播放」：从第一期顺序播放并打开播放器
- 单期点击：直接从该期开始播放并打开播放器
- `+` 按钮：追加到首页播放列表末尾（已在列表中变为 `✓`）
- 已播放进度条显示在标题下方

### 播放器页（Player）

```
┌─────────────────────────────────────┐
│  ∨ 收起          正在播放           │
│                                     │
│           ┌───────────┐             │
│           │   封面    │  ← 最大 320pt│
│           └───────────┘             │
│         标题                        │
├─────────────────────────────────────┤
│  10:24 ════════●═════  45:30       │
│                                     │
│  ◀◀  ⏪15  ⏸  30⏩  ▶▶             │
│                                     │
│  [1.0x]      🌙 定时      ☰ 列表   │
└─────────────────────────────────────┘
```

**功能：**
- 可拖动进度条 + 时间显示
- 播放/暂停、上一首/下一首、快进 30s / 快退 15s
- 播放速度（弹窗选择：0.75x / 1.0x / 1.25x / 1.5x / 2.0x）
- 睡眠定时（弹窗选择：15 / 30 / 45 / 60 分钟）
- 列表按钮：收起播放器并滚动到首页播放列表
- 详情跳转：点击标题跳转到详情网页

### 设置页（Settings）

```
┌─────────────────────────────────────┐
│  设置                              │
├─────────────────────────────────────┤
│  账户                              │
│  ├── 登录状态：已登录              │
│  └── [退出登录]                    │
├─────────────────────────────────────┤
│  同步                              │
│  ├── 上次同步：时间                │
│  └── [立即同步]                    │
├─────────────────────────────────────┤
│  本地数据                          │
│  ├── 创作者 N 个                   │
│  ├── 专辑 N 个                     │
│  ├── 音频 N 条                     │
│  └── [清除所有数据]                │
├─────────────────────────────────────┤
│  关于                              │
│  └── 版本 1.0.0                    │
└─────────────────────────────────────┘
```

---

## 数据模型

```swift
struct Creator: Identifiable, Codable {
    let id: String          // user_id
    var name: String
    var avatarUrl: String?
    var urlSlug: String     // 用于拼接主页 URL
    var doing: String?      // 创作者分类描述
}

struct Album: Identifiable, Codable {
    let id: String          // album_id
    var creatorId: String
    var title: String
    var coverUrl: String?
    var audioCount: Int
    var totalDuration: TimeInterval
    var sortOrder: Int
}

struct AudioItem: Identifiable, Codable {
    let id: String          // post_id
    var albumId: String
    var creatorId: String
    var title: String
    var coverUrl: String?
    var duration: TimeInterval
    var sortOrder: Int      // rank
    var publishTime: Date
    var audioUrl: String?   // 带签名，有时效性，不持久化
}
```

播放进度通过 `PlaybackProgressStore` 单独管理，以 `postId → 秒数` 存入 UserDefaults，不放在 `AudioItem` 中。

---

## 技术选型

| 层级 | 技术方案 |
|------|----------|
| UI 框架 | SwiftUI (iOS 17+) |
| 状态管理 | `@Observable` |
| 音频播放 | AVFoundation + AVPlayer |
| 后台音频 | AVAudioSession category: `.playback` |
| 控制中心 | MPNowPlayingInfoCenter（异步加载封面） |
| 数据持久化 | JSON 文件（DatabaseService）+ UserDefaults（进度/设置） |
| 安全存储 | Keychain（auth_token） |
| 网络层 | URLSession |
| 图片缓存 | NSCache（CachedImage 组件） |
| 登录 | WKWebView + WKHTTPCookieStore |

---

## 项目结构

```
OneHundredVolt/
├── App/
│   └── OneHundredVoltApp.swift
├── Features/
│   ├── Onboarding/
│   │   ├── WelcomeView.swift
│   │   ├── LoginWebView.swift
│   │   ├── CreatorSelectView.swift
│   │   └── SyncProgressView.swift
│   ├── Home/
│   │   ├── HomeView.swift        ← 含内嵌播放列表
│   │   └── HomeViewModel.swift
│   ├── Album/
│   │   ├── AlbumDetailView.swift
│   │   └── AlbumViewModel.swift
│   ├── Creator/
│   │   ├── CreatorView.swift
│   │   └── CreatorWebView.swift
│   ├── Player/
│   │   ├── PlayerView.swift
│   │   └── MiniPlayerView.swift
│   └── Settings/
│       └── SettingsView.swift
├── Services/
│   ├── AudioPlayerService.swift
│   ├── NowPlayingService.swift
│   ├── AfdianAPIService.swift
│   ├── DatabaseService.swift
│   ├── SyncService.swift
│   ├── KeychainService.swift
│   └── PlaybackProgressStore.swift
├── Models/
│   ├── Creator.swift
│   ├── Album.swift
│   └── AudioItem.swift
└── Shared/
    ├── Theme.swift
    ├── CachedImage.swift
    └── Extensions.swift
```

---

## 播放器架构

### 核心原则

> `AudioPlayerService` 是全局单例状态机，所有 UI 只读取状态、调用命令，**绝不直接写状态**。

### 播放列表语义

播放列表是一个**先进先出队列**，而非随机索引数组：

| 规则 | 说明 |
|------|------|
| `playlist[0]` | 始终是当前正在播放（或加载中）的单集 |
| 播放完成 | 自动将 `playlist[0]` 移除，播放新的 `playlist[0]` |
| 手动下一首 | 同播放完成：移除当前，播放新 `playlist[0]` |
| 手动上一首 | 若进度 > 5 秒则 seek(0)；否则无操作（已移除的单集不可恢复） |
| 点击追加 | 追加到末尾，不打断当前播放 |
| 立即播放 | 移到队首，立刻加载播放 |
| 列表清空 | `clearAll()` → `currentItem = nil` → MiniPlayer 消失 |

**为什么废弃 `currentIndex`？**  
播完移除的模式下当前项永远在索引 0，`currentIndex` 是冗余状态，冗余状态会导致不一致 Bug。

### AudioPlayerService 状态机

```
                  ┌───────────────────────────────────────┐
                  │           AudioPlayerService          │
                  │                                       │
  play() ──────►  │  [idle]                               │
                  │    │                                  │
                  │    ▼                                  │
                  │  [loading]  isLoading = true           │
                  │    │  AVPlayerItem.status == .readyToPlay
                  │    ▼                                  │
                  │  [playing]  isPlaying = true           │
                  │    │  ◄──── resume()                  │
                  │    │  ────► pause()                   │
                  │    ▼                                  │
                  │  [paused]   isPlaying = false          │
                  │    │                                  │
                  │    │  AVPlayerItemDidPlayToEndTime     │
                  │    ▼                                  │
                  │  [finished] → 移除当前 → 播下一首     │
                  │               或 clearAll()           │
                  └───────────────────────────────────────┘
```

**状态字段一览：**

| 字段 | 类型 | 含义 |
|------|------|------|
| `currentItem` | `AudioItem?` | 当前播放单集，`nil` 时 MiniPlayer 自动消失 |
| `playlist` | `[AudioItem]` | 播放队列，`[0]` 为当前 |
| `isPlaying` | `Bool` | 是否正在播放（非 loading） |
| `isLoading` | `Bool` | 是否正在加载 AVPlayer |
| `currentTime` | `TimeInterval` | 当前播放进度（秒） |
| `duration` | `TimeInterval` | 当前单集总时长 |
| `playbackRate` | `Float` | 播放速度，持久化到 UserDefaults |
| `sleepRemainingSeconds` | `Int` | 睡眠定时剩余秒数，0 = 未启用 |
| `loadError` | `String?` | 加载失败错误信息 |

### 公共 API

```swift
// 播放控制
play(playlist: [AudioItem], startAt: Int)  // 设置新队列并从 startAt 位置开始
play(item: AudioItem)                      // 将单集移到队首播放
playImmediately(_ item: AudioItem)         // 同 play(item:)，语义更明确
appendToPlaylist(_ item: AudioItem)        // 追加到末尾，不打断当前播放
playNext()                                 // 跳过当前，播 playlist[0]
playPrevious()                             // 重播当前（无法返回已移除的）
pause() / resume() / togglePlayPause()
seek(to: TimeInterval)
skipForward(seconds: 30) / skipBackward(seconds: 15)

// 队列维护
clearAll()                                 // 停播并清空（MiniPlayer 消失）
didRemoveItems(deletingCurrent: Bool)      // onDelete 后通知 player
syncAfterReorder()                         // onMove 后确保 playlist[0] == currentItem

// 定时
setSleepTimer(_ duration: SleepDuration)
cancelSleepTimer()
```

---

## 导航与通知机制

App 使用 **NotificationCenter** 跨层级通信，所有通知名统一定义，避免魔法字符串。

### 通知一览

| 通知名 | 发出方 | 接收方 | 语义 |
|--------|--------|--------|------|
| `.navigateToHomePlaylist` | `PlayerView` / `MiniPlayerView` | `HomeView` + `AlbumDetailView` | 收起播放器，回首页，滚到播放列表 |
| `.showPlaylistSheet` | （预留） | `HomeView` | 只滚动到播放列表，不 pop 导航 |
| `.openPlayer` | （预留） | `HomeView` | 打开全屏播放器 |
| `.playbackDidFinishAll` | `AudioPlayerService` | `PlayerView` | 队列全部播完，自动关闭播放器 |
| `.didSyncComplete` | `SyncService` | `HomeView` | 同步完成，刷新数据 |
| `.didLogout` | `SettingsView` | `OneHundredVoltApp` | 退出登录，回 Onboarding |
| `.didClearData` | `SettingsView` | `HomeView` | 清除数据，刷新首页 |

### 「☰ 播放列表」跳转时序

```
用户点击 ☰
    │
    ├─ 若在 PlayerView（fullScreenCover）：
    │     1. dismiss() 自身（fullScreenCover 关闭动画 ~0.4s）
    │     2. asyncAfter(0.4s) → post .navigateToHomePlaylist
    │     3. AlbumDetailView 收到通知 → showPlayer = false（若有），dismiss() 自身
    │     4. HomeView 收到通知 → navigationPath = NavigationPath()（pop to root）
    │     5. asyncAfter(0.5s) → scrollToPlaylist = true
    │
    └─ 若在 MiniPlayerView（在 HomeView 或 AlbumDetailView）：
          1. post .navigateToHomePlaylist（同步发出）
          2. AlbumDetailView 收到 → dismiss()
          3. HomeView 收到 → navigationPath = NavigationPath() + 延迟滚动
```

### 为什么用 NavigationPath 而非 dismiss？

`AlbumDetailView` 是通过 `NavigationLink` push 进 `HomeView` 的 `NavigationStack` 的，没有自己的 `dismiss` 语义（dismiss 只对 `.sheet` / `.fullScreenCover` 有效）。因此：
- `HomeView` 监听通知后，将 `navigationPath = NavigationPath()` 直接 pop 到根
- `AlbumDetailView` 监听通知后，只需关闭自己的 `fullScreenCover`（`showPlayer = false`），然后调用 `dismiss()` 触发 pop（等价于 back 按钮）

---

## 设计规范

**颜色：**
```
背景色:       #0A0A0C
次级背景:     #141418
卡片背景:     #1E1E24
主强调色:     #00D4AA  (青绿)
次强调色:     #FFB800  (琥珀黄)
文字主色:     #FFFFFF
文字次色:     #8E8E93
分割线:       #2C2C30
成功色:       #30D158
警告色:       #FF9F0A
```

**圆角：**
- 卡片：16pt
- 按钮：12pt
- 封面图：8pt

**字体：** SF Pro（系统默认），品牌标识：⚡ 闪电符号
