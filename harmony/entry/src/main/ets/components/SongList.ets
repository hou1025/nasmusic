// 歌曲列表组件：LazyForEach 数据源 + 点击回调。
import { Song, sourceLabel, displayArtist } from '../model/Song';
import { formatTime } from './PlayerBar';

export class SongDataSource implements IDataSource {
  private songs: Song[] = [];
  private listeners: DataChangeListener[] = [];

  update(songs: Song[]): void {
    this.songs = songs.slice();
    this.notifyDataReload();
  }

  totalCount(): number {
    return this.songs.length;
  }

  getData(index: number): Song {
    return this.songs[index];
  }

  registerDataChangeListener(listener: DataChangeListener): void {
    this.listeners.push(listener);
  }

  unregisterDataChangeListener(listener: DataChangeListener): void {
    const index: number = this.listeners.indexOf(listener);
    if (index >= 0) {
      this.listeners.splice(index, 1);
    }
  }

  private notifyDataReload(): void {
    for (const listener of this.listeners) {
      listener.onDataReloaded();
    }
  }
}

@Component
export struct SongList {
  @Watch('onSongsChanged')
  @Prop songs: Song[] = [];
  @Link selectedId: string;
  onSelect: (song: Song, index: number) => void = () => {};
  private dataSource: SongDataSource = new SongDataSource();

  aboutToAppear(): void {
    this.onSongsChanged();
  }

  onSongsChanged(): void {
    this.dataSource.update(this.songs);
  }

  build() {
    List() {
      LazyForEach(this.dataSource, (song: Song, index: number) => {
        ListItem() {
          Row() {
            Column() {
              Text(song.title)
                .fontSize(15)
                .fontColor(song.id === this.selectedId ? '#FF0A59F7' : '#FF222222')
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(`${displayArtist(song)} · ${sourceLabel(song.sourceType)}`)
                .fontSize(12)
                .fontColor('#99000000')
                .margin({ top: 3 })
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            .layoutWeight(1)
            .alignItems(HorizontalAlign.Start)

            Text(formatTime(song.durationMs))
              .fontSize(11)
              .fontColor('#99000000')
              .margin({ left: 8 })
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
          .onClick(() => this.onSelect(song, index))
        }
      }, (song: Song) => song.id)
    }
    .width('100%')
    .layoutWeight(1)
    .divider({ strokeWidth: 1, color: '#FFF2F2F2' })
  }
}
