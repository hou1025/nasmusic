# NasMusic 鸿蒙端（HarmonyOS NEXT / API 12+）

本目录是 NasMusic 在鸿蒙端的**工程骨架**，使用 ArkTS / ArkUI（Stage 模型）。
目标是为项目提供一个结构完整、可导入 DevEco Studio、可真实接续开发的起点，而不是一个已发布的完整播放器。

> **重要：本工程未在本机编译（本机无 DevEco SDK）。所有 ArkTS 代码仅经过静态自查，必须在 DevEco Studio 中编译并真机验证。**

## 一、导入 DevEco Studio

1. 安装 DevEco Studio 5.x（含 HarmonyOS NEXT API 12 SDK）。
2. `File > Open`，选择本目录 `harmony/`（即包含 `build-profile.json5` 的这一层，不是 `entry/`）。
3. 首次打开执行 `File > Project Structure > Project > Signing Configs`，勾选 **Automatically generate signature** 登录华为账号生成调试证书；生成的签名配置会写入工程级 `build-profile.json5` 的 `signingConfigs`（当前为空是刻意保留的，未内置任何证书）。
4. 连接真机或启动 API 12 模拟器，选择 `entry` 模块运行。
5. 若 ohpm 报依赖缺失，在工程根目录执行 `ohpm install`；本工程无三方依赖。

## 二、工程结构

```
harmony/
├── build-profile.json5              工程级构建配置（签名、product、SDK 版本）
├── hvigorfile.ts                    工程级 Hvigor 脚本（插件挂载点）
├── oh-package.json5                 工程级 ohpm 描述
└── entry/
    ├── build-profile.json5          entry 模块构建配置
    ├── oh-package.json5             entry 模块 ohpm 描述
    └── src/main/
        ├── module.json5             权限（INTERNET / READ_MEDIA / KEEP_BACKGROUND_RUNNING）、Ability
        ├── resources/base/
        │   ├── element/string.json  应用名与权限用途说明
        │   └── profile/main_pages.json  页面路由注册
        └── ets/
            ├── entryability/EntryAbility.ets   生命周期、Context 注入、媒体权限申请
            ├── model/
            │   ├── Song.ets          统一歌曲模型、SourceType、播放 URI 辅助
            │   └── NasSource.ets     音源配置模型
            ├── services/
            │   ├── MusicLibrary.ets  mediaLibrary 本地扫描、preferences 持久化、AES 凭据封装
            │   ├── OnlineMusicApi.ets Subsonic 兼容客户端
            │   ├── FnosApi.ets       飞牛音乐网关客户端（地址解析、authx 签名、曲库）
            │   └── PlaybackService.ets AVPlayer 封装、后台持续任务
            ├── components/
            │   ├── PlayerBar.ets     播放控制条
            │   └── SongList.ets      LazyForEach 歌曲列表
            └── pages/
                ├── Index.ets         四 Tab 主页面
                └── SourceSettings.ets 添加音源页
```

## 三、与 Android 端共享的协议 / 数据契约

### 3.1 SourceType

| 枚举值 | 含义 | Android 对应 |
| --- | --- | --- |
| `LOCAL` | 本机媒体库 | `SourceType.LOCAL` |
| `SMB` | NAS · SMB | `SourceType.SMB` |
| `WEBDAV` | NAS · WebDAV | `SourceType.WEBDAV` |
| `FNOS` | 飞牛音乐（fnOS `/music/api/v1`） | `SourceType.FNOS` |
| `ONLINE` | Subsonic 兼容在线音源 | Android 内核组正在接入的枚举值 |

鸿蒙端枚举使用字符串值，便于直接写入 preferences 做持久化，与 Android `enum class` 的 `name` 一致。

### 3.2 Song / NasSource 字段映射

`model/Song.ets` 对齐 Android `core/Models.kt` 的 `Song`：`id / title / uri / sourceType / artist / album / durationMs / sourceId / folder / artworkUri / lyricsUri / sizeBytes / lastModified / mimeType / isFavorite`。
差异：Android 的 nullable 字段（`artworkUri` / `lyricsUri`）在 ArkTS 中用空串表示“无”，避免跨端 null 语义差异；`durationMs` 统一为毫秒（mediaLibrary 原始秒值在扫描时乘 1000）。

`model/NasSource.ets` 对齐 Android `NasSource`，其中密码字段按需求命名为 `passwordEncrypted`（Android 为 `passwordEnc`），**只存密文**。

### 3.3 网络协议

- **Subsonic**：`GET /rest/{view}.view?u=&p=&v=1.16.1&c=NasMusic&f=json`，已实现 `ping`、`search3`（默认 `songCount=500`）、`stream`、`getCoverArt`，错误码映射为中文提示。`search3` 返回的 `Song.uri` 是 `streamUrl()` 直链，与 Android 端 `OnlineMusicApi.kt` 一致。
- **飞牛音乐**：前缀 `/music/api/v1`，`authx` 头签名规则与 Android `data/FnosApi.kt` 完全一致（`SIGN_SALT` 与 `CLIENT_KEY` 必须保持同步）；FN ID / FN Connect 子域名自动按中继模式附加 `Cookie: mode=relay`；登录态用 `Cookie: music-token=...`，token 仅存内存。
- **音源配置持久化**：`@ohos.data.preferences`，与 Android DataStore 的 `settings` / 音源表职责对应。

## 四、已完成 / 待办

### 已有可复用实现（仍需真机验证）

- 标准 HarmonyOS NEXT 工程结构、权限声明、Ability 生命周期与页面路由。
- `Song` / `NasSource` 领域模型与跨端一致的 `SourceType`。
- 本地音频扫描（`mediaLibrary` 查询 `media_type = AUDIO`）。
- 音源配置增删改查（preferences）+ AES-256/CBC/PKCS7 凭据加解密封装接口。
- Subsonic 客户端：`ping` / `testConnection` / `search3` / `streamUrl` / `coverUrl` 与中文错误映射。
- 飞牛客户端：地址解析（局域网 / FN ID / 中继 / 自定义域名）、MD5 签名、探测、登录、曲目分页、封面字节、流长度探测。
- AVPlayer 播放控制骨架（play/pause/next/prev/seek、进度回调）、四 Tab ArkUI 页面与两个基础组件。

### 骨架 / TODO（明确未完成）

- **SMB / WebDAV 扫描**：仅有配置模型与入口，未实现文件枚举，设置页探测会明确提示“骨架待实现”。
- **飞牛签名流播放**：`/track/stream` 需要 `authx` 与 Cookie 头，`streamUrl()` 返回的裸 URL **不能直接交给 AVPlayer**。需实现本地 HTTP 代理或自定义数据源；`streamHeaders()` 已提供所需请求头。
- **Subsonic 在线搜索结果未接入列表**：`search3()` 返回 `Song[]`，但 Index 页目前只展示本地扫描结果，需补充按音源筛选与切换。
- **凭据密钥管理**：当前 `AesCredentialCipher` 每次启动生成新密钥，旧密文不可解密。真机发布前必须迁移到 `@ohos.security.huks` 持久化密钥别名。
- **后台播放与通知**：`AUDIO` 持续任务 + WantAgent 骨架已写，需在真机验证保活策略；Android 式前台通知的鸿蒙等效方案是 `AVSession` 锁屏/媒体控制中心，尚未实现。
- **应用图标 / 启动窗资源**：未内置 `resources/base/media` 二进制图标，DevEco 中补齐后可在 `module.json5` 的 ability 上增加 `icon` / `startWindowIcon`。
- **应用 bundleName**：`PlaybackService.startBackgroundTask()` 中 WantAgent 的 `bundleName` 为占位值 `com.nasmusic.harmony`，需替换为实际应用的 bundleName。
- **DevEco 编译验证**：未做。API 小版本间 `mediaLibrary.getMediaLibrary` / `getFileAssets` 的同步与 Promise 形式、`cryptoFramework.createMd('MD5')` 的支持情况、AVPlayer 状态枚举名称，都可能需要按实际 SDK 微调。

## 五、构建与运行说明

1. **签名**：见第一节第 3 步；`build-profile.json5` 的 `signingConfig: default` 依赖 DevEco 生成的签名配置。
2. **权限**：
   - `ohos.permission.INTERNET`：Subsonic / 飞牛网络访问（system_grant）。
   - `ohos.permission.READ_MEDIA`：本地音频扫描，API 12 为 user_grant，`EntryAbility` 中已调用 `requestPermissionsFromUser`，拒绝时本地列表为空但不崩溃。
   - `ohos.permission.KEEP_BACKGROUND_RUNNING`：后台音频持续任务（system_grant），需真机验证弹窗与保活。
3. **SDK**：工程级 `compatibleSdkVersion` 为 `5.0.0(12)`，`runtimeOS` 为 `HarmonyOS`；如需更高版本，在 Project Structure 中调整并同步小版本格式。
4. **调试建议**：先只跑“本地 + 歌曲 Tab”验证扫描与播放；再在“音源”页添加 Subsonic 地址做连接测试；飞牛接入先用局域网 IP:5666，FN ID 需先在浏览器完成设备校验。
5. **代码风格**：严格 ArkTS（不使用 `any`）、`async/await`、`try/catch` 转中文提示、关键文件顶部有职责注释；响应式使用 `@State` / `@Prop` / `@Link` / `@Observed` / `@Watch`。

## 六、与 Android 端目录的对应关系

| HarmonyOS | Android | 说明 |
| --- | --- | --- |
| `model/Song.ets` | `core/Models.kt` | 统一歌曲模型与 SourceType |
| `model/NasSource.ets` | `core/Models.kt` NasSource | 音源配置 |
| `services/MusicLibrary.ets` | `data/MusicRepository.kt` + `core/Preferences.kt` | 媒体扫描与设置持久化 |
| `services/OnlineMusicApi.ets` | Android 内核组 Subsonic 客户端 | 新音源，协议对齐 |
| `services/FnosApi.ets` | `data/FnosApi.kt` | 飞牛网关，签名与解析逻辑对齐 |
| `services/PlaybackService.ets` | `playback/PlaybackService.kt` | AVPlayer 替代 Media3 |
| `pages/Index.ets` | `ui/` 各 Screen | 导航与列表 |
