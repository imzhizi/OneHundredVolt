# v1.9 Debug 面板设计方案

## 目标

将 Android/iOS 的 Debug 面板收敛为仅在开发构建可用、可审计且不泄露敏感数据的诊断工具。

本方案不把 Debug 面板当成生产环境的隐藏管理后台。

## 测试方案引用

测试范围、稳定版 macOS XCTest 与 beta + Computer Use 双通道、证据格式和环境故障分流，以 [测试方案](TEST_PLAN.md) 和 [执行手册](cross-platform-test-runbook.md) 为准。本设计只定义 Debug 面板的产品和技术边界，不重复测试流程。

## 非目标

- 不在 Release、TestFlight 或 App Store 包中保留可通过手势、URL、账号或远程开关打开的 Debug 面板。
- 不在面板、fixture、日志或截图中保存登录凭据、cookie、token、音频直链、完整个人资料或设备标识符。
- 不用 Debug 构建的启动/帧率数据给 Release 性能下结论；Android 官方明确不建议以 Debug 构建进行性能测量。[Android performance guidance](https://developer.android.com/topic/performance/measuring-performance)
- 不用一次目录响应的缺页、空页或异常推断远端已删除本地单集。

## Debug 面板设计原则

### 1. 编译期隔离优先，运行期保护补充

Android 使用 `debug` source set/`BuildConfig`，iOS 使用 `#if DEBUG` 或 Debug-only target/source。Android 的 build variant 可以为特定构建组合使用独立 source set，因此 Debug 工具不应放进 `main` 后仅由普通条件分支隐藏。[Android build variants](https://developer.android.com/build/build-variants)

Release 构建验收应包含：

1. Android Release 不显示 Debug 面板入口，且包内无 fixture/editor UI 文案。
2. iOS Release archive 不编译 Debug UI 和测试 fixture 入口。
3. Shared 层的 Debug 诊断与 fixture 在非 Debug 构建为 no-op 或不可达，不能访问生产数据。
4. CI 对 Release APK/IPA 进行静态字符串/符号检查，防止新增入口意外带入。

### 2. 采用能力清单，不采用任意命令控制台

平台 UI 只渲染共享层定义的、带元数据的诊断能力，而不是让 Android/iOS 分别堆叠临时按钮。每项能力定义以下字段：

| 字段 | 含义 |
|---|---|
| `id` | 稳定能力标识，用于测试和日志归类 |
| `category` | 状态、同步、fixture、缓存或数据维护 |
| `risk` | 只读、可逆、本地破坏性、账号破坏性 |
| `preconditions` | Debug 构建、已登录、已选专辑、是否需要二次确认 |
| `action` | 共享层提供的窄接口，不能执行任意 SQL、Shell 或网络请求 |
| `result` | 用户可读摘要与结构化诊断事件，不含敏感原文 |

建议按风险分组：

- **只读**：构建信息、登录状态（布尔值）、数据库计数、缓存大小、同步阶段、最近诊断事件。
- **可逆测试**：标记专辑待检查、触发一次增量检查、注入进程内目录 fixture、清除 fixture、清除未读提醒。
- **本地破坏性**：删除指定单集元数据、清除缓存、清除数据库、清除进度。必须展示影响范围、输入确认词或二次确认，并在执行后显示实际删除数量。
- **账号破坏性**：退出登录。仅放在普通设置的既有登录操作中，不在 Debug 面板复制第二套实现。

### 3. Fixture 是受控边界，不是通用数据编辑器

保留当前进程内、按专辑 ID 注入目录 fixture 的方向：它能稳定覆盖新增、变更、空目录、重复 ID、缺页、错误和超时，且退出、登出或清除数据后失效。

禁止把真实服务器响应、完整数据库导出或自由 SQL 编辑器直接暴露在 App 内。Android Studio 已提供 Database Inspector 用于调试运行中的 SQLite 数据；应用内面板应专注于用户旅程不可替代的受控场景，而不是复制 IDE 的任意数据修改功能。[Database Inspector](https://developer.android.com/studio/inspect/database)

每次 fixture 执行都记录：fixture 名称/哈希、目标专辑标识（只写不可逆摘要或内部测试 ID）、前后条目数、差异数、`lastCheckedAt` 是否前进和清理状态。发生异常、空/缺页目录时，不推进检查时间也不删除本地条目。

### 4. 日志默认最小化和脱敏

只允许结构化、可预测字段，例如操作名、状态类别、尝试次数、耗时、分页数和计数变化。禁止记录 token、cookie、Authorization、音频 URL、密码、完整异常请求体以及未经审核的 `Throwable.message`。

Android 官方建议生产版本避免 Logcat 中的敏感数据、使用专门的日志清洗组件，并可通过 R8 去除生产日志。[Android log disclosure](https://developer.android.com/privacy-and-security/risks/log-info-disclosure) Apple 的 `OSLogPrivacy` 支持将插值字段标记为私密或敏感，避免日志中暴露账户等信息。[OSLogPrivacy](https://developer.apple.com/documentation/os/oslogprivacy)

因此改造原则为：

1. `DebugDiagnostics` 保留有限容量的内存 ring buffer，并使用 allowlist 过滤字段。
2. 共享层只接受 `DiagnosticEvent`，不直接接受任意异常字符串；异常输出映射成稳定错误类别。
3. 导出前再次扫描敏感键名和值形态；发现风险字段时只导出 `<redacted>` 和事件计数。
4. Debug 面板显示最近事件，不自动上传；导出是明确用户动作，并提示不应发送含个人数据的文件。
5. Release 只保留面向用户的错误提示和必要的崩溃报告 SDK，不复用 Debug 导出通道。

### 5. 不混淆正确性诊断与性能诊断

Debug 面板可以展示同步耗时、条目数和缓存总量，帮助定位功能状态；它不提供性能通过/失败结论。启动、滚动、切页和播放性能应在接近 Release 的构建上使用 Android Studio Profiler/Perfetto、Instruments 或 Xcode performance tests 单独测量。

## 目标架构

```text
Shared Debug Capability Registry
        |             |
        |             +-- DiagnosticEvent sanitizer + bounded buffer
        |
        +-- Fixture controller + safe maintenance actions
              |                         |
       Android Debug UI           iOS #if DEBUG UI
              |                         |
              +------ Test-only entry points ------+

Release Android/iOS: no panel UI, no fixture resolver, no diagnostic export
```

所有 UI 操作经窄接口返回结构化 `ActionResult`，由平台层渲染；Shared 层不能依赖 SwiftUI/Compose。界面本身不含业务逻辑，因而可用共享单元测试验证能力的前置条件、fixture 清理和脱敏规则，再用两端少量 UI 测试验证显示与二次确认。

## 实施阶段

### Phase 1：诊断能力收敛

1. 为现有 Debug 按钮补齐 `id`、风险、前置条件、确认要求与结构化结果。
2. 将 Android/iOS 面板接到同一份共享能力描述；保留平台层展示差异。
3. 将任意异常文本替换为错误类别和已审核的上下文，补充敏感字段回归测试。

### Phase 2：构建与发布验证

1. 增加 Android Debug/Release 与 iOS Debug/Release 的入口存在性测试。
2. 检查 Release 产物不含 fixture 关键字、调试面板标题、测试入口和敏感日志格式。
3. 在 Release 性能基线中分离功能指标与性能指标，避免 Debug 面板影响结果。

## 验收标准

1. Debug 面板只在 Debug 构建中出现；Release 静态和运行时检查均无入口、fixture 和导出能力。
2. 所有破坏性操作有影响范围、二次确认和结果计数；未授权测试不改变用户数据。
3. 导出的诊断信息与 Android Logcat/iOS OSLog 均不含凭据、token、cookie、音频 URL 或原始敏感异常文本。
4. 新增诊断能力至少有一个 Shared 单测和 Android/iOS 各一个 UI 可见性或交互测试。
