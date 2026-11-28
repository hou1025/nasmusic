# NasMusic Windows 桌面端（Compose Multiplatform Desktop）

这是 NasMusic 的 **Windows 桌面播放器工程骨架**，技术栈为 Kotlin + Compose Multiplatform Desktop，与 Android 端（Kotlin/Compose）保持一致，便于未来抽取共享领域模型。

> **重要**：本工程是**独立 Gradle 工程**，刻意没有挂到项目根的 `settings.gradle.kts`，修改本目录不会影响 Android 端构建。
>
> **未在本机编译验证**：本机缺少可用的 `java`/`gradle` 命令行环境，且 Compose Desktop / JavaFX 依赖可能不在缓存中。首次构建需联网下载依赖，请按下面的步骤在装有 JDK 17 的机器上验证。

## 一、运行环境

- **JDK 17+**（`build.gradle.kts` 使用 `jvmToolchain(17)`，缺失时 Gradle 会尝试自动下载）
- Gradle 8.9（推荐用 wrapper；也可直接用本机 Gradle 8.x）
- 首次构建需要联网拉取 Compose Desktop 1.6.11、kotlinx 与 JavaFX 依赖

## 二、如何运行

本目录只配置了 `gradle-wrapper.properties`，**未放置 `gradle-wrapper.jar`**（避免二进制文件入库）。两种运行方式：

### 方式 A：本机 Gradle（推荐）

```bash
cd windows
gradle run
```

若本机 Gradle 版本不是 8.9，也可以正常构建（Gradle 8.x 兼容）。

### 方式 B：补上 wrapper jar 后用 wrapper

从任一 Gradle 8.9 安装目录复制 `bin/gradle` 与 `lib/plugins/gradle-wrapper-*.jar`（或执行 `gradle wrapper --gradle-version 8.9` 生成完整 wrapper），然后：

```bash
cd windows
.\gradlew run
```

### 方式 C：IntelliJ IDEA / Android Studio

1. `File → Open` 选择 **`windows/`** 目录（作为独立工程打开，不要打开项目根）。
2. 等待 Gradle 同步完成。
3. 运行配置选择 `com.nasmusic.desktop.MainKt` 的 `main` 函数，右键 → `Run 'MainKt'`。

构建原生安装包（可选）：

```bash
gradle packageMsi   # Windows MSI；也可 packageExe / packageDmg / packageDeb
```

## 三、工程结构

```
windows/
├── settings.gradle.kts                     独立工程声明与仓库配置
├── build.gradle.kts                        依赖、版本、Compose Desktop application 配置
├── gradle.properties                       Gradle JVM 参数
├── gradle/wrapper/gradle-wrapper.properties Gradle 8.9（无 wrapper jar，见上文）
└── src/main/kotlin/com/nasmusic/desktop/
    ├── Main.kt                             入口：application { Window { App() } }
    ├── model/
    │   └── Models.kt                       Song / SourceType / NasSource / Playlist / ScanProgress
    ├── data/
    │   ├── MusicLibrary.kt                 本地递归扫描 + 远程聚合 + JSON 持久化
    │   ├── OnlineMusicApi.kt               Subsonic 兼容客户端 + 凭据加密封装接口
    │   ├── FnosClient.kt                   飞牛音乐网关（登录 / authx 签名 / track/list / stream）
    │   └── WebDavClient.kt                 WebDAV PROPFIND 列目录
    ├── playback/
    │   └── PlayerController.kt             JavaFX Media 封装 + StateFlow 状态
    └── ui/
        ├── App.kt                          导航骨架 + 发现/歌曲/我的/设置 + 列表与收藏
        ├── SourceSettings.kt               音源管理页与添加表单（含测试连接）
        └── NowPlaying.kt                   底部播放条（封面占位/进度/控制）
```

数据落盘目录：`%USERPROFILE%\.nasmusic-desktop\library.json`（收藏、歌单、音源、本地根目录）。

## 四、技术选型理由

| 选型 | 理由 |
| --- | --- |
| Compose Multiplatform Desktop 1.6.11（稳定版） | 与 Android 端同为 Kotlin + Compose，控件与状态管理经验可直接复用，未来可共享 module |
| Kotlin 1.9.24 + Gradle 8.9 | 与 Compose 1.6.11 兼容的稳定组合 |
| JDK 自带 HttpClient / java.nio.file | 网络与文件扫描零第三方依赖，减少体积与冲突 |
| kotlinx.serialization | 与 Kotlin 技术栈一致，JSON 持久化类型安全 |
| JavaFX Media | 桌面端现成的音频解码与播放能力；**格式支持有限，见限制** |

## 五、与 Android 端共享模型 / 协议的迁移路径

当前 `model/Models.kt` 已与 Android 端 `app/.../core/Models.kt` **字段对齐**（名称、类型、默认值一致），差异只有：

1. 桌面端 `SourceType` 多了 `ONLINE`（安卓内核组正在实现的 Subsonic 在线音源）。
2. 桌面端去掉了 `android.net.Uri` / `androidx.media3` 依赖，`uri` 用普通字符串。
3. 桌面端模型加了 `@Serializable` 以便 JSON 落盘。

建议的收敛步骤：

1. 新建 `shared/` Kotlin Multiplatform 模块，把 `Song`、`Playlist`、`NasSource`、`ScanProgress` 放入 `commonMain`。
2. 协议共用：把 `FnosClient` 中的 `SIGN_SALT` / `CLIENT_KEY` / `signHeader` / `canonicalQuery` 与 Android 端 `FnosApi.kt` 合并为共享实现（两端目前常量与算法已一致）。
3. `SourceType` 用通用枚举，在线音源类型由共享模块统一定义（两端共用 ONLINE）。
4. 桌面端 `build.gradle.kts` 增加 `implementation(project(":shared"))`，删除本地 `model/Models.kt`，导入共享模型。
5. 持久化层可进一步统一：Android 端 Room ↔ 桌面端 JSON 的字段映射表已经一一对应，替换存储实现即可。

## 六、已完成 / 待办

### 已完成（骨架中可运行的代码）

- 独立可构建的 Gradle 工程与 Compose Desktop 入口
- 与 Android 对齐的领域模型 + ONLINE 扩展
- 本地音乐递归扫描（mp3/flac/m4a/ogg/wav），ID3v1 基础标签 + 文件名回退，进度 StateFlow
- 收藏、歌单、音源配置 JSON 持久化
- Subsonic 客户端：`testConnection` / `search` / `streamUrl` / `coverUrl`，enc-token 认证
- WebDAV 客户端：PROPFIND 列目录、音频过滤、连通性测试
- 飞牛音乐客户端：地址解析、authx 签名、登录、track/list、stream/cover URL 构造、跨主机跳转防护
- JavaFX Media 播放器：play/pause/next/prev/seek/volume 与进度、错误状态 Flow
- Compose Material3 主界面：五页导航、歌曲列表/搜索/收藏、音源管理、播放条

### 待办（明确未做）

- **SMB 音源扫描**：模型与表单已支持，实际扫描需要引入 `smbj` 等库；当前 `testSource`/`refreshSource` 对 SMB 返回可读提示，不会崩溃。
- **飞牛音乐的鉴权播放**：`streamUrl`/`coverUrl` 已构造，但网关需要 authx + Cookie；JavaFX Media 无法设置请求头，需要本地带认证代理（Android 端用 ExoPlayer 自定义 DataSource 解决）。封面当前显示占位符。
- **WebDAV 鉴权播放**：同上，URL 不含 Basic 头，需服务端允许匿名读取或加本地代理。
- **完整标签解析**：flac/m4a/ogg 的元数据目前只取文件名；可迁移 Android 端 `TagReader.kt` 或接入共享库。
- **歌词、封面下载与缓存、均衡器、快捷键、系统托盘、断点续播**。
- **打包分发**：`nativeDistributions` 只配了应用名，签名/图标/安装包模板未定制。

## 七、已知限制

- **未在本机编译验证**，首次构建必须联网拉依赖；若 IDEA 提示找不到 `org.jetbrains.compose` 插件，请确认 `settings.gradle.kts` 的 `pluginManagement.repositories` 包含 `gradlePluginPortal()` 与 `google()`。
- **JavaFX Media 格式支持有限**：mp3/wav/aac/m4a 通常可用；**flac 支持取决于系统与 JavaFX 解码器，很多环境无法播放**，Ultra High Quality / DSD 不支持。若需全格式，后续可换 VLCJ（需本机安装 VLC）。
- JavaFX 在非模块化 classpath 下运行会打印 "Unsupported JavaFX configuration" 警告，通常不影响播放；打包时要确认 JavaFX 原生库被正确包含。
- Base64 凭据封装**不是加密**，只是避免明文落盘；生产环境请实现 `CredentialCipher` 接口，接入 Windows Credential Manager / DPAPI。
- 本地扫描使用 `Files.walk`，目录很大时首次扫描会较慢；UI 已显示进度但扫描发生在后台 IO 线程。
- Subsonic 在线音源刷新使用 `search3` 的通配查询 `*`，不同服务端实现可能返回空结果，后续可改用 `getAlbumList2` + `getAlbum`。
- 飞牛接口是私有未公开协议，字段做了多候选兼容；服务端升级后可能需要同步调整签名盐值或端点。

## 八、文件所有权声明

本目录之外未做任何修改：`app/`、`settings.gradle.kts`、`README.md`、`build.gradle.kts` 等项目根文件均保持原样。
