// 底部播放控制条：展示曲目、播放/暂停、上下曲与进度。
import { Song, emptySong, displayArtist } from '../model/Song';

export function formatTime(ms: number): string {
  const total: number = Math.max(0, Math.floor(ms / 1000));
  const minutes: number = Math.floor(total / 60);
  const seconds: number = total % 60;
  return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
}

@Component
export struct PlayerBar {
  @Prop song: Song = emptySong();
  @Link isPlaying: boolean;
  @Prop positionMs: number = 0;
  @Prop durationMs: number = 0;
  onTogglePlay: () => void = () => {};
  onNext: () => void = () => {};
  onPrev: () => void = () => {};
  onSeek: (value: number) => void = () => {};

  build() {
    Column() {
      Row() {
        Column() {
          Text(this.song.title)
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(displayArtist(this.song))
            .fontSize(12)
            .fontColor('#99000000')
            .margin({ top: 2 })
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)

        Row({ space: 12 }) {
          Text('上一首')
            .fontSize(12)
            .fontColor('#FF4A4A4A')
            .onClick(() => this.onPrev())
          Text(this.isPlaying ? '暂停' : '播放')
            .fontSize(12)
            .fontColor('#FF0A59F7')
            .onClick(() => this.onTogglePlay())
          Text('下一首')
            .fontSize(12)
            .fontColor('#FF4A4A4A')
            .onClick(() => this.onNext())
        }
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8 })

      Row() {
        Text(formatTime(this.positionMs)).fontSize(10).fontColor('#99000000')
        Slider({
          value: this.positionMs,
          min: 0,
          max: Math.max(this.durationMs, 1),
          step: 1,
          style: SliderStyle.OutSet
        })
          .layoutWeight(1)
          .margin({ left: 8, right: 8 })
          .onChange((value: number, mode: SliderChangeMode) => {
            if (mode === SliderChangeMode.End) {
              this.onSeek(value);
            }
          })
        Text(formatTime(this.durationMs)).fontSize(10).fontColor('#99000000')
      }
      .width('100%')
      .padding({ left: 12, right: 12, bottom: 8 })
    }
    .width('100%')
    .backgroundColor('#FFFFFFFF')
    .borderTop({ width: 1, color: '#FFE5E5E5' })
  }
}
