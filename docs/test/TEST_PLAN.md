# OneHundredVolt 双端功能测试方案

本文档用于 Android 真机/模拟器和 iOS Xcode beta 的回归测试。测试账号只在运行时手工登录，不写入脚本、日志、截图或仓库。

## 1. 环境准备

```bash
cd /Users/wangzhiyu04/apus/OneHundredVolt
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export https_proxy=http://127.0.0.1:7890
export http_proxy=http://127.0.0.1:7890
export all_proxy=socks5://127.0.0.1:7890
```

iOS 命令不使用机器默认 Xcode；在第 4 节选择稳定版 XCTest 或 beta Computer Use 通道后，再显式设置对应的 `DEVELOPER_DIR`。

Android 构建：

```bash
./gradlew :shared:testDebugUnitTest :androidApp:assembleDebug
```

也可以用可重复脚本完成构建、安装、启动并保存截图/UI dump。脚本只处理本地设备，不自动登录：

```bash
./scripts/test-android-debug.sh
SIMULATOR_ID=<已启动的模拟器 UDID> ./scripts/test-ios-simulator.sh
```

如果项目 wrapper 受本机 Gradle 锁或网络环境影响，使用本机 Gradle 8.14.4 直接执行同样的任务，并保留上述代理变量。

iOS 构建：

```bash
./scripts/build-shared-framework.sh Debug
xcodebuild -project iosApp/OneHundredVolt.xcodeproj \
  -scheme OneHundredVolt -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO build
```

iOS 单测可用脚本执行；脚本优先保存完整 `.xcresult`。如果 Xcode beta 在测试通过后卡在 `Finalize test log`，脚本会保存通过摘要和原始日志，并将结果包标记为不可用：

```bash
./scripts/test-ios-tests.sh
```

## 2. 测试数据和证据

测试前准备一个可以访问至少一个有声专辑的账号。登录步骤必须由测试人员在 WebView 中完成；不要把账号密码传给脚本。

每个用例记录以下证据：

- 用例编号、构建版本、设备/系统版本和时间。
- 关键页面截图或 UIAutomator/Xcode 可见文本。
- Android `logcat` 中 `AndroidRuntime`、`Media3`、`OneHundredVolt` 的异常片段；忽略 UIAutomator helper 自身日志。
- 同步前后创作者、专辑、音频数量，以及播放进度/缓存大小的变化。
- 失败时记录复现步骤、期望、实际结果和是否可以稳定复现。

## 3. Android 功能矩阵

### A. 启动和登录

1. 清除应用数据后启动，确认欢迎页只有登录入口，没有“跳过登录”。
2. 点击登录，确认 Afdian WebView 能加载；网络失败时显示可理解的错误或允许重试。
3. 在 WebView 手工登录，点击“已登录，继续”。确认 cookie 校验失败时不能进入主流程。
4. 重启应用，确认登录态仍然存在；设置页显示“已登录”。
5. 退出登录，确认播放、进度、缓存和数据库按产品定义清理，并回到欢迎页。

### B. 创作者和同步

1. 进入创作者选择页，确认列表加载、默认选择和空选择校验。
2. 取消所有选择，确认同步按钮不可用并显示“至少选择一个项目”。
3. 选择一个创作者并同步，验证阶段文本依次覆盖创作者、专辑权限和目录。
4. 同步成功后验证首页创作者、专辑数、音频数和上次同步时间。
5. 期间断网、返回后台、杀进程后重新启动，确认不会把未完成同步标记成成功，也不会清空已有数据库。
6. 使用过期登录态或服务端 401，确认回到登录提示/错误提示，不出现假成功。

### C. 浏览和播放

1. 首页打开专辑详情，验证封面、标题、音频数、总时长和单集顺序。
2. 播放免费单集和有权限单集，确认缓冲、播放、暂停、切换和迷你播放器状态一致。
3. 暂停后快进 30 秒、快退 15 秒，确认时间标签和进度条立即变化，重启后进度保留。
4. 拖动进度条到 0、接近末尾和超过边界，确认值被限制在合法范围。
5. 切换 0.75x、1.0x、1.5x、2.0x，确认实际播放速度和按钮文本一致。
6. 设置 15/30/45/60 分钟睡眠计时，确认倒计时更新，计时结束后暂停且可再次播放。
7. 加入多个单集、调整顺序、删除当前项和非当前项，确认队列及持久化恢复正确。
8. 清空缓存后重新播放，确认从网络取流且不因缓存文件缺失崩溃。

### D. 生命周期和设置

1. 播放中切到后台、锁屏、回到前台，确认音频和通知控制符合预期。
2. 播放中杀进程并重启，确认队列、当前单集、播放速度和进度恢复，不自动意外播放。
3. 设置页验证退出登录、立即同步、清除所有数据、清空缓存和 OTA 检查。
4. Debug APK 进入“调试诊断”，确认日志数量、刷新、清除日志、清除进度、清除缓存和清除数据库动作生效。
5. Release APK 不显示“调试诊断”入口；Shared 的诊断日志和目录 fixture 在 Release 中不执行，且构建产物中不包含测试账号或敏感日志。

### E. 单集增量更新

1. 首次全量同步后重启应用，确认仅检查 `lastCheckedAt` 超过 6 小时或从未检查的专辑。
2. 远端新增单集时，确认首页专辑卡出现未读数量，目录只新增对应条目，已有进度和缓存 URL 不变。
3. 远端修改标题、时长或排序时，确认同一 id 被更新且不产生重复条目。
4. 进入专辑详情后，确认该专辑未读提醒清零；重复检查无变化时未读数不继续增长。
5. 远端返回空目录、重复 id 或请求失败时，确认旧单集保留、`lastCheckedAt` 不前移并显示失败日志。
6. Debug 面板点击“标记待检查”“立即检查”“清除提醒”；输入指定单集 ID，先用“填首条 ID”辅助填充，再点击“删除指定单集”，确认状态反馈、数据库数量和缓存文件符合预期。
7. Debug 面板选择专辑后，分别点击目录 fixture 的“新增”“变更”“空目录”“重复 ID”“缺最后一条”“模拟错误”“模拟超时”，再点击“应用 JSON”和“立即检查”，确认成功场景产生正确 diff，失败场景保留旧目录且不推进 `lastCheckedAt`。
8. fixture 验证完成后点击“清除 fixture”，重启应用确认进程内覆盖已失效，不影响正式远端目录。
9. 若同一帖子同时出现在多个专辑，确认两个专辑都能显示该单集，重复检查不会反复增加未读数。

## 4. iOS 双通道功能矩阵

Android A-D 的同一业务顺序在两个通道共用用例编号和期望，但产物与通过结论必须分开记录。

### A. macOS 正式版 + XCTest

1. 使用稳定 Xcode：`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`。确认 Shared.xcframework 的 device 和 simulator slice 都生成。
2. 运行共享/Swift 单测与可自动化 UI 用例，保存 `.xcresult`、测试总数、失败数和失败附件。
3. 使用 PR 与 Release 两份 Xcode Test Plan 分别执行快速回归和发布前全量回归；Release 计划收集失败诊断。
4. 终止并重启应用，验证 Keychain 登录态、数据库、播放进度和队列恢复；自动化无法安全持有真实登录态时，明确标记为人工/Computer Use 用例。
5. 验证网络错误、401、无音频权限和空专辑目录的提示；错误不得被静默转成空成功。
6. Debug 构建设置页显示诊断面板；Release 构建静态和运行时均无 `#if DEBUG` 面板入口、fixture 和导出能力。

### B. beta 系统 + Computer Use

1. 使用 `DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer` 构建并在 Xcode/DeviceHub 启动模拟器，记录设备型号和 runtime 版本。
2. 测试人员手工完成 WebView 登录后，用 Computer Use 验证同步、首页、专辑详情、播放、队列、速度、睡眠计时和冷启动恢复。
3. 保存每个关键步骤的截图、可见文本及同步前后数据数量，结果标记为 `exploratory passed`、`failed`、`blocked` 或 `not run`。
4. `simctl`/CoreSimulatorService/disk-image 服务异常时，将 CLI XCTest 和 `simctl` 证据标记为 `not run`；Xcode GUI/DeviceHub 的成功运行仅能证明探索性流程，不等价于 XCTest 通过。
5. Debug 面板的 fixture 场景仅可在获得明确的测试数据授权后执行；fixture 必须进程内生效，退出、登出或清除数据后失效。
6. 已接入的 iOS 启动检查遵循 6 小时阈值；后台 `BGAppRefreshTask` 尚未实现，不能把后台准时执行当作已验收能力。

## 5. 通过标准

- P0：崩溃、登录态泄漏、凭据/音频 URL 泄漏、同步成功但数据损坏、播放不可恢复。必须阻断发布。
- P1：主流程功能错误、进度丢失、队列错乱、401 未引导登录、后台播放失效。修复后才可发布。
- P2：布局、提示文案、偶发重试、缓存统计不准确。可进入下一迭代，但必须登记。
- 所有 P0/P1 用例通过，Android 单测和 Debug 构建通过，稳定版 macOS XCTest 的可自动化用例通过并有归档结果，才算本轮回归完成。beta Computer Use 的结论单列为兼容性/探索性证据，不能覆盖未执行的 XCTest。
