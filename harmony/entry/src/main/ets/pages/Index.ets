// 主页面：发现 / 歌曲 / 音源 / 我的 四个 Tab，底部常驻播放控制条。
import { router } from '@ohos.router';
import { promptAction } from '@ohos.promptAction';
import { Song, SourceType, emptySong, sourceLabel } from '../model/Song';
import { NasSource, sourceSubtitle } from '../model/NasSource';
import { musicLibrary } from '../services/MusicLibrary';
import { playbackService } from '../services/PlaybackService';
import { SongList } from '../components/SongList';
import { PlayerBar } from '../components/PlayerBar';

@Entry
@Component
struct Index {
  @State private currentTab: number = 1;
  @State private songs: Song[] = [];
  @State private sources: NasSource[] = [];
  @State private selectedSongId: string = '';
  @State private currentSong: Song = emptySong();
  @State private isPlaying: boolean = false;
  @State private positionMs: number = 0;
  @State private durationMs: number = 0;
  @State private loading: boolean = false;
  private syncTimer: number = -1;

  aboutToAppear(): void {
    this.loadData();
    // 播放状态来自 AVPlayer 回调，定时同步到 UI（真机可用 AVSession/状态订阅替换）。
    this.syncTimer = setInterval((): void => {
      this.isPlaying = playbackService.state.isPlaying;
      this.positionMs = playbackService.state.positionMs;
      this.durationMs = playbackService.state.durationMs;
    }, 300);
  }

  aboutToDisappear(): void {
    if (this.syncTimer >= 0) {
      clearInterval(this.syncTimer);
    }
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message, duration: 2500 });
  }

  private async loadData(): Promise<void> {
    this.loading = true;
    try {
      this.sources = await musicLibrary.listSources();
      this.songs = await musicLibrary.scanLocalSongs();
    } catch (error) {
      const e: Error = error as Error;
      this.toast(`曲库加载失败：${e.message}`);
    } finally {
      this.loading = false;
    }
  }

  private async playSong(song: Song): Promise<void> {
    this.selectedSongId = song.id;
    this.currentSong = song;
    const index: number = this.songs.findIndex((item: Song) => item.id === song.id);
    try {
      await playbackService.setQueue(this.songs, index >= 0 ? index : 0);
      this.isPlaying = true;
    } catch (error) {
      const e: Error = error as Error;
      this.toast(e.message);
    }
  }

  private async togglePlay(): Promise<void> {
    if (this.selectedSongId.length === 0 && this.songs.length > 0) {
      await this.playSong(this.songs[0]);
      return;
    }
    try {
      await playbackService.togglePlayPause();
      this.isPlaying = playbackService.state.isPlaying;
    } catch (error) {
      const e: Error = error as Error;
      this.toast(e.message);
    }
  }

  private async removeSource(source: NasSource): Promise<void> {
    try {
      await musicLibrary.deleteSource(source.id);
      this.sources = await musicLibrary.listSources();
      this.toast(`已移除「${source.name}」`);
    } catch (error) {
      const e: Error = error as Error;
      this.toast(`移除失败：${e.message}`);
    }
  }

  @Builder
  discoverView() {
    Scroll() {
      Column() {
        Text('NasMusic · 鸿蒙端').fontSize(22).fontWeight(FontWeight.Bold).width('100%')
        Text('与 Android 端共用统一歌曲模型与音源协议。')
          .fontSize(13).fontColor('#FF666666').width('100%').margin({ top: 6, bottom: 16 })

        ForEach(
          [SourceType.LOCAL, SourceType.SMB, SourceType.WEBDAV, SourceType.FNOS, SourceType.ONLINE],
          (type: SourceType) => {
            Row() {
              Text(sourceLabel(type)).fontSize(14).layoutWeight(1)
              Text(this.sources.filter((s: NasSource) => s.type === type).length.toString())
                .fontSize(14).fontColor('#FF0A59F7')
            }
            .width('100%')
            .padding(12)
            .backgroundColor('#FFF7F8FA')
            .borderRadius(8)
            .margin({ bottom: 8 })
          },
          (type: SourceType) => type
        )

        Text('说明：当前工程是可导入 DevEco Studio 的骨架。本地扫描、音源持久化、Subsonic 客户端与飞牛签名已有实现；SMB/WebDAV 扫描、飞牛签名流播放需在真机接续。')
          .fontSize(12).fontColor('#FF888888').width('100%').margin({ top: 12 })

        Button('去管理音源')
          .width('100%')
          .margin({ top: 16 })
          .onClick(() => { this.currentTab = 2; })
      }
      .width('100%')
      .padding(16)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  libraryView() {
    Column() {
      Row() {
        Text(`本机歌曲 ${this.songs.length}`)
          .fontSize(14).fontWeight(FontWeight.Medium)
        Text(this.loading ? '扫描中…' : '已就绪')
          .fontSize(11).fontColor('#FF999999').margin({ left: 8 })
        Blank()
        Text('重新扫描').fontSize(12).fontColor('#FF0A59F7')
          .onClick(() => { this.loadData(); })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 10, bottom: 6 })

      SongList({
        songs: this.songs,
        selectedId: $selectedSongId,
        onSelect: (song: Song): void => { this.playSong(song); }
      })
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  sourcesView() {
    Column() {
      Row() {
        Text('音源管理').fontSize(16).fontWeight(FontWeight.Medium)
        Blank()
        Text('+ 添加').fontSize(13).fontColor('#FF0A59F7')
          .onClick(() => {
            router.pushUrl({ url: 'pages/SourceSettings' })
              .catch((error: Error) => { this.toast(`打开页面失败：${error.message}`); });
          })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 12, bottom: 8 })

      if (this.sources.length === 0) {
        Text('尚未配置音源。点击右上角添加，或直接使用本地音乐。')
          .fontSize(12).fontColor('#FF999999').width('100%').padding(16)
      } else {
        List() {
          ForEach(this.sources, (source: NasSource) => {
            ListItem() {
              Row() {
                Column() {
                  Text(source.name).fontSize(14).fontColor('#FF222222')
                  Text(`${sourceLabel(source.type)} · ${sourceSubtitle(source)}`)
                    .fontSize(11).fontColor('#FF999999').margin({ top: 3 })
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)
                Text('移除').fontSize(12).fontColor('#FFFF4D4F')
                  .onClick(() => { this.removeSource(source); })
              }
              .width('100%')
              .padding({ left: 12, right: 12, top: 10, bottom: 10 })
            }
          }, (source: NasSource) => source.id)
        }
        .width('100%')
        .layoutWeight(1)
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  profileView() {
    Scroll() {
      Column() {
        Text('关于').fontSize(18).fontWeight(FontWeight.Bold).width('100%')
        Text('NasMusic HarmonyOS NEXT 工程骨架，API 12+ / ArkTS。')
          .fontSize(13).width('100%').margin({ top: 10 })
        Text('未在本机编译：本机无 DevEco SDK，需使用 DevEco Studio 完成编译、签名与真机验证。')
          .fontSize(12).fontColor('#FF888888').width('100%').margin({ top: 12 })
        Text('待办：SMB/WebDAV 扫描、Subsonic 搜索接入列表、飞牛签名流本地代理、AVSession 锁屏控制、密钥迁移 HUKS。')
          .fontSize(12).fontColor('#FF888888').width('100%').margin({ top: 8 })
      }
      .width('100%')
      .padding(16)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
  }

  build() {
    Column() {
      Tabs({ barPosition: BarPosition.End, index: this.currentTab }) {
        TabContent() { this.discoverView() }.tabBar('发现')
        TabContent() { this.libraryView() }.tabBar('歌曲')
        TabContent() { this.sourcesView() }.tabBar('音源')
        TabContent() { this.profileView() }.tabBar('我的')
      }
      .onChange((index: number) => { this.currentTab = index; })
      .layoutWeight(1)
      .barWidth('100%')

      PlayerBar({
        song: this.currentSong,
        isPlaying: $isPlaying,
        positionMs: this.positionMs,
        durationMs: this.durationMs,
        onTogglePlay: (): void => { this.togglePlay(); },
        onNext: (): void => { playbackService.next(); },
        onPrev: (): void => { playbackService.prev(); },
        onSeek: (value: number): void => { playbackService.seek(value); }
      })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFFFF')
  }
}
