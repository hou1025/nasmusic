// 添加/编辑音源页：类型选择、连接测试、凭据加密保存。
import { router } from '@ohos.router';
import { promptAction } from '@ohos.promptAction';
import { SourceType } from '../model/Song';
import { NasSource, emptyNasSource } from '../model/NasSource';
import { musicLibrary } from '../services/MusicLibrary';
import { OnlineMusicApi, ConnectionResult } from '../services/OnlineMusicApi';
import { FnosApi, FnosConnectionResult } from '../services/FnosApi';

const TYPE_LABELS: string[] = ['本地', 'SMB', 'WebDAV', '飞牛音乐(FNOS)', '在线(Subsonic)'];

@Entry
@Component
struct SourceSettings {
  @State name: string = '';
  @State typeIndex: number = 0;
  @State host: string = '';
  @State port: string = '';
  @State baseUrl: string = '';
  @State share: string = '';
  @State rootPath: string = '';
  @State username: string = '';
  @State password: string = '';
  @State anonymous: boolean = false;
  @State testing: boolean = false;

  private typeOfIndex(): SourceType {
    switch (this.typeIndex) {
      case 1: return SourceType.SMB;
      case 2: return SourceType.WEBDAV;
      case 3: return SourceType.FNOS;
      case 4: return SourceType.ONLINE;
      default: return SourceType.LOCAL;
    }
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message, duration: 2500 });
  }

  /** 只有网络型音源具备真实探测；SMB/WebDAV 的扫描探测仍属待办。 */
  private async testConnection(): Promise<void> {
    this.testing = true;
    try {
      const type: SourceType = this.typeOfIndex();
      if (type === SourceType.ONLINE) {
        const api: OnlineMusicApi = new OnlineMusicApi(this.baseUrl, this.username, this.password);
        const result: ConnectionResult = await api.testConnection();
        this.toast(result.message);
        return;
      }
      if (type === SourceType.FNOS) {
        const api: FnosApi = new FnosApi(this.baseUrl, this.username, this.password, 'connection-test');
        const result: FnosConnectionResult = await api.probe();
        this.toast(result.message);
        return;
      }
      if (type === SourceType.LOCAL) {
        this.toast('本地音源无需网络测试，保存后会自动扫描媒体库');
        return;
      }
      this.toast('SMB/WebDAV 的连通性将在扫描阶段验证（骨架待实现）');
    } catch (error) {
      const e: Error = error as Error;
      this.toast(`测试失败：${e.message}`);
    } finally {
      this.testing = false;
    }
  }

  private async save(): Promise<void> {
    if (this.name.trim().length === 0) {
      this.toast('请输入音源名称');
      return;
    }
    try {
      const source: NasSource = emptyNasSource();
      source.name = this.name.trim();
      source.type = this.typeOfIndex();
      source.host = this.host.trim();
      source.port = Number(this.port) || 0;
      source.baseUrl = this.baseUrl.trim();
      source.share = this.share.trim();
      source.rootPath = this.rootPath.trim();
      source.username = this.username.trim();
      source.anonymous = this.anonymous;
      if (this.password.length > 0) {
        source.passwordEncrypted = await musicLibrary.encryptPassword(this.password);
      }
      await musicLibrary.saveSource(source);
      this.toast('音源已保存');
      router.back();
    } catch (error) {
      const e: Error = error as Error;
      this.toast(`保存失败：${e.message}`);
    }
  }

  @Builder
  private field(label: string, value: string, onChange: (value: string) => void, placeholder: string = '') {
    Column() {
      Text(label).fontSize(12).fontColor('#FF666666').width('100%')
      TextInput({ placeholder: placeholder, text: value })
        .width('100%')
        .height(40)
        .onChange(onChange)
    }
    .width('100%')
    .margin({ bottom: 10 })
    .alignItems(HorizontalAlign.Start)
  }

  build() {
    Scroll() {
      Column() {
        Text('添加音源').fontSize(20).fontWeight(FontWeight.Bold).width('100%')
        Text('凭据会经 AES 加密后写入首选项；真机发布前请按 README 将密钥迁移到 HUKS。')
          .fontSize(11).fontColor('#FF999999').width('100%').margin({ top: 4, bottom: 12 })

        this.field('名称', this.name, (value: string) => { this.name = value; }, '例如：家里的 NAS')
        Text('类型').fontSize(12).fontColor('#FF666666').width('100%')
        Select(TYPE_LABELS.map((label: string) => ({ value: label })))
          .selected(this.typeIndex)
          .onSelect((index: number) => { this.typeIndex = index; })
          .width('100%')
          .margin({ bottom: 10 })

        if (this.typeIndex === 1 || this.typeIndex === 2) {
          this.field('主机地址', this.host, (value: string) => { this.host = value; }, '192.168.1.10')
          this.field('端口', this.port, (value: string) => { this.port = value; }, '445 / 80')
          this.field('共享名', this.share, (value: string) => { this.share = value; }, 'music')
        }
        if (this.typeIndex === 3 || this.typeIndex === 4) {
          this.field('服务地址', this.baseUrl, (value: string) => { this.baseUrl = value; }, 'http://192.168.1.10:5666')
        }
        this.field('根目录 / 起始路径（可选）', this.rootPath, (value: string) => { this.rootPath = value; }, '/music')

        if (this.typeIndex !== 0) {
          Row() {
            Text('匿名访问').fontSize(13)
            Toggle({ type: ToggleType.Checkbox, isOn: this.anonymous })
              .onChange((isOn: boolean) => { this.anonymous = isOn; })
          }
          .width('100%')
          .justifyContent(FlexAlign.SpaceBetween)
          .margin({ bottom: 10 })

          if (!this.anonymous) {
            this.field('用户名', this.username, (value: string) => { this.username = value; })
            this.field('密码', this.password, (value: string) => { this.password = value; })
          }
        }

        Row({ space: 12 }) {
          Button('测试连接')
            .layoutWeight(1)
            .enabled(!this.testing)
            .onClick(() => { this.testConnection(); })
          Button('保存')
            .layoutWeight(1)
            .onClick(() => { this.save(); })
        }
        .width('100%')
        .margin({ top: 8 })
      }
      .width('100%')
      .padding(16)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
  }
}
