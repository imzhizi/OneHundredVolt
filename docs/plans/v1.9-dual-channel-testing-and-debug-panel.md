# v1.9 双通道 iOS 测试与 Debug 面板方案

## 目标

保留两条互补、证据不混淆的 iOS 验证通道，并将 Android/iOS 的 Debug 面板收敛为仅在开发构建可用、可审计且不泄露敏感数据的诊断工具。

本方案不把 beta 系统上的人工/Computer Use 回归伪装成 XCTest 成功，也不把 Debug 面板当成生产环境的隐藏管理后台。

## 非目标

- 不在 Release、TestFlight 或 App Store 包中保留可通过手势、URL、账号或远程开关打开的 Debug 面板。
- 不在面板、fixture、日志或截图中保存登录凭据、cookie、token、音频直链、完整个人资料或设备标识符。
- 不用 Debug 构建的启动/帧率数据给 Release 性能下结论；Android 官方明确不建议以 Debug 构建进行性能测量。[Android performance guidance](https://developer.android.com/topic/performance/measuring-performance)
- 不用一次目录响应的缺页、空页或异常推断远端已删除本地单集。

## 测试通道

### 通道 A：macOS 正式版 + XCTest

**目的**：提供可重复、可比较、可归档的自动化质量门禁。

**环境**：稳定版 macOS、稳定版 Xcode、匹配的 iOS Simulator runtime。显式设置稳定版工具链，避免被 beta 覆盖。

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./scripts/build-shared-framework.sh Debug
./scripts/test-ios-tests.sh
```

**适用测试**：共享层单元测试、Swift 单元/集成测试、关键无登录 UI 流程、fixture 驱动的增量同步、回归性崩溃与状态恢复。

**门禁证据**：命令退出状态、测试总数/失败数、完整 `.xcresult`（或明确标记为不可生成的日志证据）、失败时的 `XCTAttachment` 截图和文本。Xcode 的 XCTest/XCUIAutomation 适合自动化 UI 流程；测试计划可按开发和发布前阶段分别选择测试与配置。[XCTest](https://developer.apple.com/documentation/xctest/) [Xcode test plans](https://developer.apple.com/documentation/xcode/organizing-tests-to-improve-feedback)

**推荐配置**：在 Xcode 新建两个 Test Plan，而不是复用一个无差别的 Test action。

| Test Plan | 范围 | 运行频率 | 结果要求 |
|---|---|---|---|
| `OneHundredVolt-PR` | 共享单测、Swift 单测、确定性 fixture 与最短 UI smoke | 每次 PR/提交 | 全绿 |
| `OneHundredVolt-Release` | PR 覆盖 + 关键 UI 流程、冷启动恢复、错误映射和性能相关测试 | 发布前 | 全绿，保存 `.xcresult` |

为两份计划分别设置语言、地区、启动参数和诊断收集策略；在会失败的 UI 用例上保留截图/附件，避免把每次成功运行的敏感或高体积附件长期保存。Apple 的 Test Plan 支持按配置收集失败诊断、截图和附件。[Test plan diagnostics](https://developer.apple.com/documentation/xcode/organizing-tests-to-improve-feedback) [XCTAttachment](https://developer.apple.com/documentation/XCTest/adding-attachments-to-tests-activities-and-issues)

### 通道 B：beta macOS/Xcode + Computer Use

**目的**：尽早验证 beta SDK、系统 UI、WebView 登录、播放器交互和真实页面布局，发现尚无法稳定由 XCTest 覆盖的行为。

**环境**：beta macOS、`/Applications/Xcode-beta.app`、Xcode GUI/DeviceHub 以及已授权的 Computer Use。显式设置 beta 工具链：

```bash
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
./scripts/build-shared-framework.sh Debug
```

**执行方式**：

1. 用 Xcode 或 DeviceHub 启动已安装的模拟器；记录设备型号和 runtime 版本。
2. 用 Computer Use 执行可见的核心旅程：欢迎页、人工 WebView 登录、创作者同步、首页冷启动、专辑详情、播放/暂停、队列、速度和睡眠计时。
3. 对每个关键状态保存截图和可见文本；同步场景记录前后创作者、专辑和音频数。
4. 结束后把结果标记为 `exploratory passed`、`failed` 或 `blocked`，不得标为 `XCTest passed`。

**故障分流**：`simctl`、CoreSimulatorService 或 simulator disk-image 服务无法使用时，先标记为工具链/运行时阻塞。若 Xcode GUI/DeviceHub 能运行 App，可继续执行 Computer Use 探索性验证；CLI XCTest、`xcodebuild test` 和 `simctl` 截图仍必须标记为 `not run`。GUI 启动成功不等价于 XCTest 已执行。

**限制**：Computer Use 不应输入或保存账号密码，也不应执行退出登录、清空数据、清空缓存、删除元数据等破坏性操作，除非测试负责人明确授权该次数据重置。物理设备音频输出和锁屏控制仍需单独真机验证。

### 通道共存规则

| 维度 | 正式版 XCTest | beta + Computer Use |
|---|---|---|
| 主要价值 | 可重复门禁与回归基线 | beta 平台兼容性和真实交互探索 |
| 登录处理 | 注入受控测试态或跳过需要真实账户的用例 | 测试人员手动登录，代理不得持有凭据 |
| 结果产物 | `.xcresult`、JUnit/测试摘要、附件 | 截图、可见文本、步骤记录、状态计数 |
| 发布结论 | 可作为自动化通过证据 | 仅补充人工验证，不能替代自动化门禁 |
| 工具链故障 | 修复/替换 runtime 后重跑 | 标记阻塞，必要时用 GUI 验证非 CLI 能力 |

两条通道使用同一份用例编号和期望，但分别保存运行环境和结论。最终报告必须显示各通道的 `passed`、`failed`、`blocked`、`not run`，禁止合并成单一“iOS 已通过”。

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

### Phase 1：测试通道和报告规范

1. 在项目测试文档中维护上述双通道，不改变现有 beta 回归能力。
2. 为稳定 Xcode 创建 PR/Release 两个 Test Plan，并把 `xcodebuild test` 产物归档为 CI 证据。
3. 在测试报告模板中分列 `stable-xctest` 与 `beta-computer-use`，记录工具链版本与阻塞原因。

### Phase 2：诊断能力收敛

1. 为现有 Debug 按钮补齐 `id`、风险、前置条件、确认要求与结构化结果。
2. 将 Android/iOS 面板接到同一份共享能力描述；保留平台层展示差异。
3. 将任意异常文本替换为错误类别和已审核的上下文，补充敏感字段回归测试。

### Phase 3：构建与发布验证

1. 增加 Android Debug/Release 与 iOS Debug/Release 的入口存在性测试。
2. 检查 Release 产物不含 fixture 关键字、调试面板标题、测试入口和敏感日志格式。
3. 在 Release 性能基线中分离功能指标与性能指标，避免 Debug 面板影响结果。

## 验收标准

1. 稳定版 macOS 可以独立运行并归档 XCTest 结果；beta 通道可以独立执行 Computer Use 回归，两者不互相覆盖结论。
2. beta CoreSimulator CLI 故障时，报告清晰区分“GUI 探索通过”和“XCTest 未执行”。
3. Debug 面板只在 Debug 构建中出现；Release 静态和运行时检查均无入口、fixture 和导出能力。
4. 所有破坏性操作有影响范围、二次确认和结果计数；未授权测试不改变用户数据。
5. 导出的诊断信息与 Android Logcat/iOS OSLog 均不含凭据、token、cookie、音频 URL 或原始敏感异常文本。
6. 新增诊断能力至少有一个 Shared 单测和 Android/iOS 各一个 UI 可见性或交互测试。
