// 应用入口 Ability：注入 Context、申请媒体读取权限、加载首页。
import { AbilityConstant, UIAbility, Want, abilityAccessCtrl } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { musicLibrary } from '../services/MusicLibrary';
import { playbackService } from '../services/PlaybackService';

const DOMAIN: number = 0x4E4D;
const TAG: string = 'EntryAbility';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onCreate');
    // 系统服务（preferences / mediaLibrary / AVPlayer / 后台任务）都需要 Context。
    musicLibrary.attachContext(this.context);
    playbackService.attachContext(this.context);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(DOMAIN, TAG, 'onWindowStageCreate');
    windowStage.loadContent('pages/Index', (err: BusinessError) => {
      if (err.code !== 0) {
        hilog.error(DOMAIN, TAG, 'loadContent failed: %{public}d %{public}s', err.code, err.message);
      }
    });
    this.requestMediaPermission();
  }

  onForeground(): void {
    hilog.info(DOMAIN, TAG, 'onForeground');
  }

  onBackground(): void {
    // 后台播放由 PlaybackService 的持续任务管理，Ability 退后台不做销毁。
    hilog.info(DOMAIN, TAG, 'onBackground');
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, TAG, 'onWindowStageDestroy');
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'onDestroy');
    playbackService.release();
  }

  /** API 12 中 READ_MEDIA 为 user_grant，需在运行时向用户申请。 */
  private requestMediaPermission(): void {
    try {
      const manager = abilityAccessCtrl.createAtManager();
      manager.requestPermissionsFromUser(this.context, ['ohos.permission.READ_MEDIA'])
        .then((result) => {
          hilog.info(DOMAIN, TAG, 'media permission result: %{public}s', JSON.stringify(result.authResults));
        })
        .catch((error: BusinessError) => {
          hilog.error(DOMAIN, TAG, 'request media permission failed: %{public}s', error.message);
        });
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      hilog.error(DOMAIN, TAG, 'request media permission exception: %{public}s', e.message);
    }
  }
}
