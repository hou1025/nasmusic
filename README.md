# NasMusic · 无广告的本地音乐 + NAS + 飞牛音乐播放器

> 轻量、**无广告**的 Android 音乐播放器，统一管理**本地音乐**、**NAS（SMB / WebDAV）**与**飞牛音乐（fnOS）**曲库。
> 当前版本：`v1.9.3`（versionCode 19） · `com.nasmusic.player` · minSdk 24 / targetSdk 35

---

## ✨ 核心特性

| 特性 | 说明 |
| --- | --- |
| 🚫 **无广告** | 纯净体验，无广告 SDK、无横幅、无弹窗、无开屏广告 |
| 📁 **本地音乐** | 扫描设备本地音乐，支持文件夹排除、短音频过滤、歌词与封面匹配、断点续听 |
| 🗄️ **NAS 网络音乐** | 原生支持 **SMB/CIFS** 与 **WebDAV**，可直接浏览远程目录、在线播放、离线缓存 |
| 🐮 **飞牛音乐（FNOS）** | 登录飞牛 NAS 上的飞牛音乐服务，曲库一键同步；支持**局域网 / FN ID 远程 / 自定义域名**三种接入方式 |

四种音源统一在一个曲库里管理，歌单、收藏、播放记录、缓存通用。

---

## 1. 本次改造做了什么

在原有 **本地音乐 / SMB / WebDAV** 三种音源之外，新增第四种音源 **飞牛音乐（FNOS）**，可以直接登录 NAS 上的飞牛音乐服务，把曲库同步进本应用播放，无需把音乐文件挂载成本地目录。

核心能力：

| 能力 | 说明 |
| --- | --- |
| 飞牛风格登录页 | 品牌色 + 居中卡片，支持显示/隐藏密码、连接测试、状态与错误内联提示 |
| 三种接入方式 | 局域网 IP、FN ID / FN Connect 中继、自定义域名 / DDNS / 反向代理 |
| 曲库同步 | 分页拉取 `/track/list`，直接用接口元数据入库，不依赖本地 ID3 解析 |
| 直接播放 | `/track/stream?guid=` 签名 Range 请求，支持拖动进度、缓存、歌单、收藏 |
| 封面与歌词 | 签名下载封面；歌词沿用原有链路 |
| 凭据安全 | 账号密码经 Android Keystore 加密保存在本机；token 只存内存，失效自动重登 |

---

## 2. 使用说明

### 2.1 入口

安装后进入 **设置 → 网络音乐源（NAS） → 飞牛登录**。

### 2.2 三种连接方式

登录页顶部有「连接方式」切换：**局域网 / FN ID / 域名**。

1. **局域网**
   - 填写 `192.168.1.10` 或 `192.168.1.10:5666`，默认补全 5666 端口。
   - 使用飞牛 NAS 的账号密码。

2. **FN ID（远程）**
   - 需先在 NAS 的「设置 → 远程访问 → FN Connect」中开启。
   - 输入 FN ID（如 `mynas`），选择官方域名后缀：`5ddd.com` / `fnos.net` / `fnofly.com`。
   - 自动解析为 `https://mynas.5ddd.com`，所有请求附加 `Cookie: mode=relay` 强制走官方中继。
   - 中继会限速；如 NAS 开启了「外网访问校验码」，页面会给出明确提示。

3. **域名**
   - 适用于 DDNS、反向代理、公网 IP + HTTPS。
   - 可填完整 `https://nas.example.com`，不强制补 5666 端口。

输入框下方会实时显示最终解析出的地址与连接模式；可先点「测试连接」验证可达性。

### 2.3 曲库同步

登录成功后自动同步曲库。卡片上的**编辑图标 = 重新登录**（密码不回填，需重新输入）。

⚠️ **重要提示：排除规则会过滤飞牛曲目**
飞牛曲库的「文件夹」字段就是专辑名，而扫描排除规则采用「包含匹配」。如果你已有大量以专辑名命名的排除规则，会把飞牛曲目全部挡在音乐列表外。
若出现「扫描成功但列表为空」，请到 **设置 → 扫描过滤 → 展开「已排除的文件夹」**，删除相关专辑名规则后重新扫描。删除规则只影响本应用索引，不会动 NAS 上的文件。

---

## 3. 技术实现

### 3.1 飞牛音乐网关

私有网关，无公开文档；签名规则与端点来自对飞牛 Web 客户端的公开逆向资料，集中在 `data/FnosApi.kt`。

- 网关前缀：`http(s)://<NAS>:5666/music/api/v1`
- 登录：`POST /user/password-login`，请求体 `username` + `sha256(password)` + `deviceId`
- 曲库：`GET /track/list?page=N&size=200`，取 `data.list` / `data.total`
- 封面：`GET /static/cover?coverId=…&size=…`（二进制）
- 音频：`GET /track/stream?guid=…`，HTTP `Range` 随机读取
- 连通性探测：`GET /initialization/state`

### 3.2 请求签名

所有业务请求带 `authx` 头：

```
authx = "nonce=<6位随机数>&timestamp=<毫秒时间戳>&sign=<md5>"
sign  = md5(SIGN_SALT + "_" + path + "_" + nonce + "_" + timestamp + "_" + payloadHash + "_" + CLIENT_KEY)
payloadHash = GET  : md5(按 key 排序并解码后的 query)
              POST : md5(原始 JSON body)
```

- 签名盐与客户端密钥常量集中在 `FnosApi.kt` 顶部（`CLIENT_KEY` 以字节码混淆形式存储）。
- 非登录请求另带 `X-Music-API: v1`；登录握手不带此头。
- 登录态通过 `Cookie: music-token=<token>` 携带；FN Connect 模式下合并为 `Cookie: mode=relay; music-token=…`。

### 3.3 安全处理

- `followRedirects(false)` + 受控跳转：中继在 P2P/局域网场景可能 302 到内网地址，跟随会泄露 Cookie 中的 token；实现只跟随同主机跳转（最多 3 次），跨主机直接终止并提示。
- 识别 FN Connect 返回的 HTML 校验页，避免把挑战页当成 JSON 解析。
- 二维码/校验码场景给出可操作提示，而不是含糊的「解析失败」。

### 3.4 性能优化

| 优化点 | 改动前 | 改动后 |
| --- | --- | --- |
| 播放读取 | Media3 每次 `read()` 发一个 HTTP Range 请求 | 512KB 顺序预读缓冲，请求数减少约 8 倍 |
| HTTP 连接 | 每次扫描/播放新建 OkHttpClient，重复 TCP+TLS 握手 | 全局共享客户端与连接池（飞牛 / WebDAV 共用） |
| 音频长度 | 播放前额外发一次 `Range: bytes=0-0` 探测 | 复用曲库元数据 `sizeBytes` |
| 封面刷新 | 每次刷新重下全部封面，8 路串行 | 增量跳过已有封面，去重后 8 路并发，上限 300 张 |
| 缓存拷贝 | 64KB 缓冲、单路串行 | 512KB 缓冲、3 路并发 |
| 本地曲库刷新 | 全量 REPLACE 写 Room | 仅写入变化曲目 |

---

## 4. 变更日志

### v1.9.3（当前）
- 修复扫描封面下载失败时写入 `ConcurrentHashMap` 的 null value 导致的无消息 `NullPointerException`（远程中继限速时更容易触发）→ 改用普通 `HashMap`，失败封面跳过，不影响歌曲入库。
- 修正折叠对象：「已排除的文件夹（N）」列表现在默认收起，点标题行或箭头展开/收起；「从音乐库中选择要排除并移除的文件夹」同样默认折叠。

### v1.9.2
- 修复飞牛网关返回 `code` 但 `msg` 为空串时的空消息异常：`ifBlank` 兜底并带上接口路径与返回码，例如 `曲库请求失败（/track/list，code=xxx）`。
- 扫描失败提示改为「扫描失败：异常类型或原始消息」，不再只剩兜底文案。
- 登录接口同样加空消息兜底。
- 设置页候选文件夹列表默认折叠。

### v1.9.1
- 新增 **FN ID / FN Connect 远程连接**：地址解析支持裸 FN ID、Connect 子域名、DDNS/公网域名；自动附加 `mode=relay` Cookie；跨主机重定向拦截；HTML 校验页识别。
- 登录页新增「连接方式」切换与 FN ID 域名后缀选择，实时显示解析结果。
- 曲库刷新提速（封面增量 + 8 路并发）、移出文件夹提示取消。
- 播放启动提速（512KB 预读、共享连接池、复用 sizeBytes、缓存并发）。
- 重写 `FnosApi.kt`（地址解析与中继逻辑）。

### v1.9.0
- 新增第四种音源 `SourceType.FNOS` 与飞牛风格登录页。
- 实现 `/user/password-login` 登录、`authx` 签名、`music-token` Cookie、`/track/list` 分页同步。
- 实现 `/track/stream` 签名 Range 流播放，复用现有 `nas://` → Media3 链路。
- 凭据经 Android Keystore 加密保存，token 内存会话，失效自动重登。
- 版本 1.8.0 → 1.9.0。

---

## 5. 文件改动明细

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `app/src/main/java/com/nasmusic/player/data/FnosApi.kt` | 飞牛私有网关客户端：地址解析、签名、登录、曲库分页、封面、Range 流、中继与会话管理；并定义全局共享 `NasHttp` |
| `app/src/main/java/com/nasmusic/player/ui/FnosLoginScreen.kt` | 飞牛风格全屏登录页：三种连接方式、FN ID 后缀选择、连接测试、登录并同步曲库 |

### 修改文件

| 文件 | 改动内容 |
| --- | --- |
| `core/Models.kt` | `SourceType` 增加 `FNOS`；音源显示名增加「NAS · 飞牛音乐」 |
| `data/NasClients.kt` | `NasClients.create()` 增加 FNOS 分支；新增 `FnosBrowser`；`WebDavBrowser` 改用共享 HTTP 客户端；音频长度复用已知 `sizeBytes` |
| `data/NasScanner.kt` | 新增 FNOS 扫描分支，直接以接口元数据构造 `Song` 入库；封面增量跳过 + 并发下载；修复封面缓存 NPE |
| `data/NasCache.kt` | 缓存拷贝缓冲 64KB → 512KB；批量缓存改为 3 路并发 |
| `data/MusicRepository.kt` | 本地曲库刷新改为只写入变化曲目 |
| `playback/NasDataSource.kt` | 新增 512KB 顺序预读缓冲，减少 Range 请求次数 |
| `ui/ViewModels.kt` | 新增 `testFnosConnection()` / `loginFnos()` / FNOS 扫描流程；错误诊断文案；取消移出文件夹与清理过滤的 Snackbar |
| `ui/NasScreens.kt` | 网络音乐源页增加飞牛登录入口与 FNOS 卡片，支持重新登录 |
| `ui/AppNav.kt` | 增加 `fnosLogin` 路由与登录页组合 |
| `ui/SettingsScreen.kt` | 两个文件夹列表默认折叠，点击标题展开/收起 |
| `app/build.gradle.kts` | versionCode 15 → 19，versionName 1.8.0 → 1.9.3 |

---

## 6. 构建

本机验证环境：JDK 17、Android SDK 35、Gradle 8.9（离线模式）。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug --offline
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

已验证：`assembleDebug` **BUILD SUCCESSFUL**；`aapt2 dump badging` 确认 `versionCode=19 / versionName=1.9.3`；DEX 内核对了飞牛相关功能字符串。

---

## 7. 已知限制与免责声明

1. **私有接口风险**：飞牛音乐 API 未公开，签名常量来自公开逆向资料，集中在 `FnosApi.kt` 顶部。固件升级改动签名规则会导致登录失败。
2. **版本头兼容**：非登录请求加了 `X-Music-API: v1`；若设备返回 403/签名错误，可去掉 `signedRequest()` 中该行后重试。
3. **自签 HTTPS**：当前未做自签证书放行，自签证书场景需自行调整网络安全配置。
4. **中继限速**：FN Connect 走官方中继，封面与曲库同步会比局域网慢。
5. **未做真机实测**：开发环境无飞牛 NAS 与 adb 设备，仅完成编译与静态验收；FN Connect 网关对 `/music/api/v1` 的实际代理行为需现场确认。
6. 本项目仅供学习研究使用，使用者需自行评估使用私有接口的风险。

---

## 8. 排障参考

| 现象 | 处理 |
| --- | --- |
| 扫描提示「扫描失败：曲库请求失败（/track/list，code=xxx）」 | 把完整红色错误文字贴到 issue，含 code 值 |
| 提示「服务器跳转到其他主机」 | 确认 FN Connect 已开启，或改用局域网地址 |
| 提示网页校验页 / 外网访问校验码 | 在 NAS 远程访问设置中关闭校验码，或先完成网页端校验 |
| 扫描成功但列表为空 | 检查「已排除的文件夹」是否把专辑名当文件夹过滤掉了 |
| 封面显示不全 | 中继限速会跳过失败封面，不影响播放；可重新扫描 |
