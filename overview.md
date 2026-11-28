# 本次交付说明（2026-11-26）

## 一、Android v2.3.9（修复 + 重新打包）

修掉测试审查里两处"必须修完才能当基线"的缺陷，并顺手清掉三处小问题：

1. **收藏状态不刷新**（高）
   - 原因：写库后播放页拿的仍是 `state.currentSong` 快照，心形图标要等切歌才变。
   - 修复：`MusicRepository.toggleFavorite(id)` 改为**以数据库当前值原子取反**（避免旧对象导致连点无效）；新增 `PlaybackController.reloadCurrentSong()`，收藏后立即重读当前曲目与队列。
2. **删除音源会打断无关播放**（中高）
   - 原因：`removeBySourceId` 无条件 `setMediaItems(keep, 0, ...)`，把正在播的歌拽回队首并重新 prepare。
   - 修复：改为从后往前按连续区间 `removeMediaItems`，保持当前曲目与进度；该音源不在队列里时完全不动播放器。
3. 清缓存时保留正在播放那首的缓存文件，并重建其余队列 URI（否则跳到下一首会报错）。
4. 睡眠定时到点但当时已暂停时，不再谎报"已暂停播放"。

验证：全量重建 `39/39` 任务、`BUILD SUCCESSFUL`；`aapt` 核验 `versionCode=46 / versionName=2.3.9`；业务 dex `classes4.dex` 哈希 `47e8d233 → d569bd28`，确认修复真的进包。

## 二、Windows 桌面版 2.3.9（UI 重写 + 对齐图标名称）

- **界面按 2.3.9 横屏双栏重写**：左侧黑胶播放卡片（旋转/落针/三行歌词/进度/播控/音量），右侧顶栏（发现 / 本地音乐 / 网络音源 / 我的 + 设置 + 搜索），内容区为横幅 + 快捷入口四宫格 + 我的歌单 + 最近播放；设置/音效/搜索/歌单详情在右栏内切换，左侧播放卡片常驻。
- **图标 1:1 还原**：`tools/MakeIcon.java` 直接使用 Android `ic_launcher.xml` 的 pathData 与配色，生成 `icon.png` / `icon.ico`。
- **名称对齐**：窗口标题与打包应用名均为 **音乐**（与 Android `app_name` 一致）。
- **新增能力**：播放记忆恢复（`playback.json`）、睡眠定时器、飞牛/Subsonic 曲库**分页拉全库**（原只有 200 / 100 首）、本地同名 `.lrc` 歌词。
- 修复基线编译错误：`Json.ignoreUnknownElements`（应为 `ignoreUnknownKeys`）、JavaFX `durationProperty`（应为 `totalDurationProperty`）、`Window(width/height)` 参数。
- 打包：`jpackage` 生成自带 JRE 的应用镜像（`音乐.exe`，143MB 目录），压缩为 `音乐-Windows-2.3.9.zip`（103MB）；已实测启动正常。

## 三、文档

- `功能对照与补齐计划.md`：Android vs Windows 功能矩阵、差距分级、补齐顺序。
- `安卓兼容性方案.md`：老系统/老车机兼容边界、三个降级方案与成本、低端机加固清单。

## 四、待确认

1. 车机的 Android 版本 + RAM（决定是否降 `minSdk`）；
2. Windows 版下一批优先补哪几项（建议：随机/循环 → 队列面板 → 封面 → 设置项）。
