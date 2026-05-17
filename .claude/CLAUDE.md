# OneHundredVolt 开发规范

## 工作流：文档先行 → 编码 → 更新日志 → 提交

每次版本更新必须按以下顺序执行，不可跳步：

```
1. 写/更新设计文档（docs/plans/vX.Y-xxx.md）
      ↓
2. 编码实现
      ↓
3. 构建验证（Android + iOS）
      ↓
4. 更新 CHANGELOG.md
      ↓
5. 提交 commit（含版本号和变更摘要）
```

### Step 1: 设计文档

- 新功能或大改动先在 `docs/plans/` 下创建设计文档，命名格式：`v<版本>-<语义名>.md`
- 文档需包含：目标版本、背景、具体方案、涉及文件列表
- 小修 bug / 纯 UI 微调可跳过此步，直接编码

### Step 2: 编码

- 按设计文档实现代码
- 遵循现有架构：KMP shared 层 + iOS SwiftUI + Android Compose

### Step 3: 构建验证

提交前必须确认：
- **Android**: `JAVA_HOME=/Library/Java/JavaVirtualMachines/microsoft-17.jdk/Contents/Home ./gradlew assembleDebug testDebugUnitTest`
- **iOS**: Xcode 编译通过（Swift 零错误）

### Step 4: 更新 CHANGELOG

在 `docs/CHANGELOG.md` **顶部**插入新版本记录，格式：

```markdown
## v<版本> — <一句话摘要>

> 状态：✅ 已实现
> 日期：YYYY-MM-DD
> 平台：iOS / Android / Shared
> 对应设计文档：`plans/v<版本>-<xxx>.md`

### iOS 变更

| # | 功能 | 说明 |
|---|------|------|
| 1 | ... | ... |

### Android 变更

| # | 功能 | 说明 |
|---|------|------|
| 1 | ... | ... |
```

### Step 5: 提交

commit message 格式：

```
<type>: <中文摘要>

<type> = feat | fix | docs | refactor | chore
```

示例：
- `feat: MiniPlayer 进度条置顶重构 (v1.4)`
- `fix: 登出时清理音频缓存`
- `docs: add v1.4 changelog entry`

**一次版本迭代的所有变更（代码+文档+CHANGELOG）应在同一个 commit 或同一批紧密关联的 commits 中完成。**

---

## 项目结构速览

```
docs/
├── CHANGELOG.md              # 版本记录（必读，每次更新必改）
├── DESIGN.md                 # 项目总设计文档
├── api-reference.md          # 爱发电 API 参考
├── assets/                   # 图片资源
└── plans/                    # 版本设计文档（按版本号排序）
    ├── v1.0-android-implementation.md
    ├── v1.1-ios-fixes.md
    ├── v1.1-playlist-loudness.md
    └── v1.4-ui-polish.md

shared/                       # KMP 共享业务逻辑
iosApp/                       # iOS (SwiftUI)
androidApp/                   # Android (Jetpack Compose)
```

## 当前版本

- **最新版本**: v1.5（2026-05-17）
- **版本线**: v1.0 → v1.1 → v1.2 → v1.4 → v1.5

## 注意事项

- `memory/` 目录为本地笔记，不纳入版本控制（已在 .gitignore）
- Java 构建需指定 JAVA_HOME=JDK17（系统默认 JDK8 不兼容 Gradle 8.x）

## 签名密钥

Android release 签名所需文件保存在本机（不纳入版本控制）：

| 项目 | 路径 / 值 |
|------|----------|
| Keystore 文件 | `~/.config/ohv/ohv-release.keystore` |
| Key alias | `ohv` |
| 密码配置 | `local.properties`（项目根目录，已在 .gitignore） |

> CI/CD（GitHub Actions）使用 Repository Secrets：`SIGNING_KEY_BASE64` / `KEY_ALIAS` / `KEY_PASSWORD`
