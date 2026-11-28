// 播放服务：基于 @ohos.multimedia.media 的 AVPlayer 封装 + 持续后台任务骨架。
// 说明：HarmonyOS 没有 Android 式前台服务通知，等效方案是 AUDIO 持续任务 + AVSession 锁屏控制；
// AVSession 对接与真机后台保活验证留作 TODO。
import { media } from '@ohos.multimedia.media';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { backgroundTaskManager } from '@ohos.resourceschedule.backgroundTaskManager';
import { wantAgent } from '@ohos.wantAgent';
import { Song, emptySong, toPlaybackUri } from '../model/Song';

const DOMAIN: number = 0x4E4D;
const TAG: string = 'PlaybackService';

/** @Observed 状态对象：UI 通过 @ObjectLink/@Link 订阅进度与播放状态。 */
@Observed
export class PlaybackState {
  song: Song = emptySong();
  isPlaying: boolean = false;
  positionMs: number = 0;
  durationMs: number = 0;
}

export class PlaybackService {
  readonly state: PlaybackState = new PlaybackState();
  private player: media.AVPlayer | undefined = undefined;
  private queue: Song[] = [];
  private currentIndex: number = -1;
  private context: common.Context | undefined = undefined;
  private backgroundStarted: boolean = false;

  attachContext(context: common.Context): void {
    this.context = context;
  }

  private requireContext(): common.Context {
    if (this.context === undefined) {
      throw new Error('PlaybackService 尚未绑定 Context');
    }
    return this.context;
  }

  private async ensurePlayer(): Promise<media.AVPlayer> {
    if (this.player !== undefined) {
      return this.player;
    }
    const player: media.AVPlayer = await media.createAVPlayer();
    player.on('stateChange', (state: media.AVPlayerState) => {
      if (state === media.AVPlayerState.INITIALIZED) {
        player.prepare();
      } else if (state === media.AVPlayerState.PREPARED) {
        player.play()
          .then(() => {
            this.state.isPlaying = true;
          })
          .catch((error: BusinessError) => {
            this.state.isPlaying = false;
            hilog.error(DOMAIN, TAG, 'play failed: %{public}s', error.message);
          });
      } else if (state === media.AVPlayerState.COMPLETED) {
        this.state.isPlaying = false;
        this.next();
      } else if (state === media.AVPlayerState.ERROR) {
        this.state.isPlaying = false;
      }
    });
    player.on('timeUpdate', (time: number) => {
      this.state.positionMs = time;
    });
    player.on('durationUpdate', (duration: number) => {
      this.state.durationMs = duration;
    });
    player.on('error', (error: BusinessError) => {
      this.state.isPlaying = false;
      hilog.error(DOMAIN, TAG, 'AVPlayer error: %{public}d %{public}s', error.code, error.message);
    });
    this.player = player;
    return player;
  }

  /** 替换播放队列并从指定位置开始播放。 */
  async setQueue(songs: Song[], startIndex: number = 0): Promise<void> {
    this.queue = songs.slice();
    if (songs.length === 0) {
      return;
    }
    const index: number = startIndex >= 0 && startIndex < songs.length ? startIndex : 0;
    this.currentIndex = index;
    await this.play(songs[index]);
  }

  /** 播放指定歌曲（重置播放器 -> 设置 URL -> 状态机自动 prepare/play）。 */
  async play(song: Song): Promise<void> {
    try {
      const player: media.AVPlayer = await this.ensurePlayer();
      await player.reset();
      this.state.song = song;
      this.state.positionMs = 0;
      this.state.durationMs = 0;
      player.url = toPlaybackUri(song);
      await this.startBackgroundTask();
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      this.state.isPlaying = false;
      hilog.error(DOMAIN, TAG, 'play failed: %{public}s', e.message);
      throw new Error(`无法播放「${song.title}」：${e.message}`);
    }
  }

  async togglePlayPause(): Promise<void> {
    try {
      const player: media.AVPlayer = await this.ensurePlayer();
      if (this.state.isPlaying) {
        await player.pause();
        this.state.isPlaying = false;
      } else {
        await player.play();
        this.state.isPlaying = true;
      }
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      hilog.error(DOMAIN, TAG, 'togglePlayPause failed: %{public}s', e.message);
      throw new Error(`播放控制失败：${e.message}`);
    }
  }

  async next(): Promise<void> {
    if (this.queue.length === 0) {
      return;
    }
    this.currentIndex = (this.currentIndex + 1) % this.queue.length;
    await this.play(this.queue[this.currentIndex]);
  }

  async prev(): Promise<void> {
    if (this.queue.length === 0) {
      return;
    }
    this.currentIndex = (this.currentIndex - 1 + this.queue.length) % this.queue.length;
    await this.play(this.queue[this.currentIndex]);
  }

  async seek(positionMs: number): Promise<void> {
    try {
      const player: media.AVPlayer = await this.ensurePlayer();
      const target: number = Math.max(0, Math.min(positionMs, this.state.durationMs));
      await player.seek(target);
      this.state.positionMs = target;
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      hilog.error(DOMAIN, TAG, 'seek failed: %{public}s', e.message);
    }
  }

  /** AUDIO 持续任务：使退后台后仍可播放。真机需验证弹窗与保活策略。 */
  private async startBackgroundTask(): Promise<void> {
    if (this.backgroundStarted) {
      return;
    }
    try {
      const info: wantAgent.WantAgentInfo = {
        wants: [
          {
            bundleName: 'com.nasmusic.harmony', // TODO: 替换为应用实际 bundleName
            abilityName: 'EntryAbility'
          }
        ],
        operationType: wantAgent.OperationType.START_ABILITY,
        requestCode: 0
      };
      const agent: wantAgent.WantAgent = await wantAgent.getWantAgent(info);
      await backgroundTaskManager.startBackgroundRunning(
        this.requireContext(), backgroundTaskManager.BackgroundMode.AUDIO, agent
      );
      this.backgroundStarted = true;
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      hilog.warn(DOMAIN, TAG, 'start background task failed: %{public}s', e.message);
    }
  }

  private async stopBackgroundTask(): Promise<void> {
    if (!this.backgroundStarted) {
      return;
    }
    try {
      await backgroundTaskManager.stopBackgroundRunning(this.requireContext());
      this.backgroundStarted = false;
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      hilog.warn(DOMAIN, TAG, 'stop background task failed: %{public}s', e.message);
    }
  }

  release(): void {
    this.stopBackgroundTask().catch(() => {
      // 退出阶段忽略后台任务释放失败。
    });
    const player: media.AVPlayer | undefined = this.player;
    if (player !== undefined) {
      player.release()
        .catch((error: BusinessError) => {
          hilog.warn(DOMAIN, TAG, 'release failed: %{public}s', error.message);
        });
      this.player = undefined;
    }
    this.state.isPlaying = false;
  }
}

/** 全局单例，由 EntryAbility.onCreate 注入 Context。 */
export const playbackService: PlaybackService = new PlaybackService();
