# 爱发电 API 验证报告

> 验证日期：2026-04-27  
> 验证方式：浏览器内 fetch，使用真实账号登录后抓取  
> 所有接口均已实测可用 ✅

---

## 一、登录机制

### 登录方式

爱发电使用 **Cookie 认证**，登录成功后服务端通过 `Set-Cookie` 写入 `auth_token`。

| 项目 | 说明 |
|------|------|
| 登录页 URL | `https://afdian.com/login` |
| 登录 API | `POST /api/passport/login` |
| 快速登录（验证码）| `POST /api/passport/quick-login` |
| 发送快速登录验证码 | `POST /api/passport/send-quick-login-code` |
| 登出 | `POST /api/passport/logout` |

### 登录后 Cookie 结构

```
_ga=...                    # Google Analytics（无关紧要）
__qc_wId=115               # QQ Connect（无关紧要）
auth_token=<TOKEN>         # ✅ 核心登录凭证，必须保存
_ga_6STWKR7T9E=...         # Google Analytics（无关紧要）
```

**关键：** 只需保存 `auth_token` 即可维持登录态，后续所有 API 请求携带此 Cookie 即可。

### iOS WKWebView 实现方案

1. `WKWebView` 加载 `https://afdian.com/login`（账号密码登录页）
2. 监听 `WKNavigationDelegate.webView(_:didFinish:)`，检测当前 URL
3. 当 `URL.path != "/login"` 时，登录成功
4. 从 `WKWebsiteDataStore.default().httpCookieStore` 提取 cookie
5. 找到 name 为 `auth_token` 的 cookie，存入 Keychain

```swift
// 检测登录成功的判断逻辑
func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
    guard let url = webView.url else { return }
    // 登录成功后会跳转到 /feed 或 /dashboard，不再是 /login
    if url.path != "/login" {
        extractAuthToken()
    }
}

// 从 WKHTTPCookieStore 提取 auth_token
func extractAuthToken() {
    WKWebsiteDataStore.default().httpCookieStore.getAllCookies { cookies in
        if let authCookie = cookies.first(where: { $0.name == "auth_token" }) {
            // 存入 Keychain
            KeychainService.save(key: "afdian_auth_token", value: authCookie.value)
            // 继续流程
            DispatchQueue.main.async { self.onLoginSuccess() }
        }
    }
}
```

### 后续 API 请求携带 Cookie

```swift
// URLRequest 中手动设置 Cookie header
var request = URLRequest(url: url)
let authToken = KeychainService.load(key: "afdian_auth_token") ?? ""
request.addValue("auth_token=\(authToken)", forHTTPHeaderField: "Cookie")
```

---

## 二、已验证 API 清单

### 基础 URL

```
https://afdian.com
```

所有接口均为相对路径，拼接到基础 URL 后使用。

---

### 1. 获取支持的创作者列表

```
GET /api/my/sponsoring
```

**认证：** 需要 `auth_token` Cookie  
**返回数据结构（关键字段）：**

```json
{
  "ec": 200,
  "data": {
    "sponsoring": [
      {
        "user": {
          "user_id": "25f894145a9011ed88fc52540025c377",
          "name": "反派影评",
          "avatar": "https://pic1.afdiancdn.com/...",
          "url_slug": "AManforAllSeasons",    // ← 创作者主页 slug，拼 URL 用
          "creator": {
            "doing": "电影评论",              // ← 创作者分类描述
            "show_album": 1                  // ← 是否展示专辑（1=有专辑功能）
          }
        }
      }
    ],
    "sponsoring_expired": []                 // ← 已过期的支持
  }
}
```

**创作者主页 URL 拼接：**
```
https://afdian.com/a/{url_slug}
// 示例：https://afdian.com/a/AManforAllSeasons
```

**注意：** `get-sponsoring` 接口返回空 `{}`，应使用 `/api/my/sponsoring`。

---

### 2. 获取创作者专辑列表

```
GET /api/user/get-album-list?user_id={userId}&page=1&per_page=20
```

**认证：** 需要 `auth_token` Cookie  
**参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `user_id` | String | 创作者的 user_id（从 sponsoring 接口获取）|
| `page` | Int | 页码，从 1 开始 |
| `per_page` | Int | 每页数量，最大值待确认，建议 20 |

**返回数据结构（关键字段）：**

```json
{
  "ec": 200,
  "data": {
    "list": [
      {
        "album_id": "bb5062d02d9711f085025254001e7c00",
        "user_id": "25f894145a9011ed88fc52540025c377",
        "title": "长青集",
        "cover": "https://pic1.afdiancdn.com/...",
        "content": "",                        // ← 专辑简介
        "post_count": 10,                     // ← 专辑内帖子数量
        "order_by": "rank asc",               // ← 排序方式
        "create_time": 1746879015,            // ← Unix 时间戳
        "update_time": 1775207299,
        "bought": 1                           // ← 1=已购买/有权限，0=无权限
      }
    ],
    "has_more": 1                             // ← 是否还有更多页
  }
}
```

**过滤建议：** 只显示 `bought == 1` 的专辑（用户已购买/有权限访问的）。

---

### 3. 获取专辑完整目录 ⭐️ 推荐

```
GET /api/user/get-album-catalog?album_id={albumId}
```

**认证：** 需要 `auth_token` Cookie  
**特点：** 一次返回专辑内**全部**帖子（`has_more: 0`），无需翻页，是加载专辑目录的首选接口。  
**已验证：** 「金马十年」专辑 11 集，一次全部返回 ✅

**⚠️ 注意：** 此接口返回的帖子**不含** `audio` URL，只有标题、时长、排序等元数据。播放时需单独请求 `post/get-detail` 获取带签名的音频链接。

**返回数据结构（关键字段）：**

```json
{
  "ec": 200,
  "data": {
    "list": [
      {
        "post_id": "b7da45045aad11ed874b52540025c377",
        "title": "金马十年①颁奖礼：禁忌之言",
        "cover": "https://pic1.afdiancdn.com/...",
        "rank": 1,                               // ← 在专辑内的排序序号
        "has_audio": 1,                          // ← 1=有音频
        "ext": {
          "audio_duration": 2314,               // ← 音频时长（秒）✅
          "video_duration": 0
        },
        "publish_time": 1667393767
      }
    ],
    "has_more": 0                               // ← 全部返回，无需翻页
  }
}
```

---

### 3b. 获取专辑内帖子（含音频 URL）

```
GET /api/user/get-album-post?album_id={albumId}&page=1&per_page=20
```

**认证：** 需要 `auth_token` Cookie  
**说明：** 含 `audio` URL，但只能分页获取（最多每次 10 条）。推荐仅在需要批量预加载音频 URL 时使用；通常用接口 3 获取目录，用接口 4 按需获取单条播放 URL 更高效。  
**参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `album_id` | String | 专辑 ID |
| `page` | Int | 页码 |
| `per_page` | Int | 每页数量（实测最多返回 10 条）|

**返回数据结构（关键字段）：**

```json
{
  "ec": 200,
  "data": {
    "list": [
      {
        "post_id": "5a36cee4280011f1835652540025c377",
        "user_id": "25f894145a9011ed88fc52540025c377",
        "title": "简·坎皮恩电影长片回顾（1986-2009）",
        "cover": "https://pic1.afdiancdn.com/...",   // ← 封面图
        "audio": "https://vod.afdiancdn.com/...mp3?t=...&sign=...",  // ← 音频直链 ✅
        "audio_thumb": "https://pic1.afdiancdn.com/...",             // ← 音频封面
        "cate": "audio",                             // ← "audio" 表示这是音频帖子
        "publish_time": 1775207299,                  // ← 发布时间戳
        "ext": {
          "audio_duration": 6882,                    // ← 音频时长（秒）✅
          "video_duration": 0
        },
        "has_right": 1,                              // ← 1=有播放权限，0=无权限
        "process": {
          "process": 0,                              // ← 上次播放位置（秒）✅
          "process_type": 1
        },
        "has_audio": 1,                              // ← 1=有音频
        "albums": [{ "album_id": "...", "title": "..." }]  // ← 所属专辑
      }
    ],
    "has_more": 0
  }
}
```

**关键字段说明：**
- `audio`：音频直链 URL，可直接传入 `AVPlayer`；URL 带签名参数（`t`, `sign`），**有时效性**，需要实时获取不要缓存
- `ext.audio_duration`：音频时长（秒），整数
- `process.process`：服务端记录的播放进度（秒），可用于恢复播放位置
- `has_right`：是否有播放权限（购买后为 1）
- `cate`：`"audio"` 表示音频内容，过滤时只取 `cate == "audio"` 的帖子

---

### 4. 获取单个帖子详情（播放时获取音频 URL）

```
GET /api/post/get-detail?post_id={postId}
```

**认证：** 需要 `auth_token` Cookie  
**用途：** 播放前调用，获取带签名的 `audio` URL（URL 有时效性，不可缓存）  
**返回关键字段：**

```json
{
  "ec": 200,
  "data": {
    "post": {
      "post_id": "...",
      "title": "...",
      "audio": "https://vod.afdiancdn.com/...mp3?t=...&sign=...",  // ← 带签名的音频直链 ✅
      "ext": { "audio_duration": 2314 },
      "has_right": 1    // ← 0=无权限（未购买）
    }
  }
}
```

**⚠️ 播放进度：** App 不调用爱发电的进度同步接口，**完全本地管理**（UserDefaults 存储 `postId → 播放秒数` 映射）。

---

### 5. 其他已发现的有用接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/my/profile` | GET | 获取当前登录用户信息 |
| `/api/user/get-profile-by-slug` | GET | 通过 slug 获取创作者信息 |
| `/api/user/get-album-info` | GET | 获取单个专辑详情 |
| `/api/user/get-album-catalog` | GET | 获取专辑目录 |
| `/api/post/get-detail` | GET | 获取单个帖子详情 |
| `/api/passport/logout` | POST | 登出 |

---

## 三、数据模型映射（爱发电 → App）

| 爱发电概念 | App 概念 | 关键字段 |
|-----------|---------|---------|
| `sponsoring[].user` | Creator（创作者）| `user_id`, `name`, `avatar`, `url_slug` |
| `get-album-list` 结果 | Album（专辑）| `album_id`, `title`, `cover`, `post_count` |
| `get-album-post` 结果 | AudioItem（音频）| `post_id`, `title`, `audio`, `ext.audio_duration` |

---

## 四、注意事项

### 音频 URL 时效性
`audio` 字段的 URL 含签名参数（`t=` 时间戳 + `sign=` 签名），**疑似有时效性**。建议：
- 不要在本地数据库缓存 audio URL
- 每次播放前实时调用 `get-album-post` 获取最新 URL
- 或调用 `post/get-detail` 获取单个帖子的最新音频链接

### 分页策略
- **专辑列表**（`get-album-list`）：支持 `page` + `per_page` 翻页，`has_more: 1` 继续请求
- **专辑目录**（`get-album-catalog`）：一次返回全部，`has_more: 0`，无需翻页 ⭐️
- **专辑帖子**（`get-album-post`）：每次最多返回 10 条，`has_more: 1` 时需继续翻页；但因目录接口已覆盖元数据需求，此接口通常不必遍历完整

### bought 字段
专辑列表中 `bought: 0` 的专辑用户没有访问权限，对应帖子的 `has_right` 也会为 0，`audio` 字段为空字符串。App 中应只展示 `bought: 1` 的专辑。

### CORS
爱发电 API 在其自身域名下没有跨域限制，但从 iOS App 使用 `URLSession` 请求时需要手动设置 `Cookie` header（不像浏览器会自动携带）。
