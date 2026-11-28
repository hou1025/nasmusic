// 统一歌曲模型与音源枚举，字段与 Android 端 core/Models.kt 对齐。
// Android 的 nullable 字段（artworkUri/lyricsUri）在此用空串表示“无”，避免跨端 null 语义差异。

/** 与 Android SourceType 一致；ONLINE 为 Android 内核组正在接入的 Subsonic 兼容在线音源。 */
export enum SourceType {
  LOCAL = 'LOCAL',
  SMB = 'SMB',
  WEBDAV = 'WEBDAV',
  FNOS = 'FNOS',
  ONLINE = 'ONLINE'
}

export interface Song {
  id: string;
  title: string;
  /** 本地为 file:// 或 mediaLibrary uri；网络音源为服务端返回的相对路径或完整 URL。 */
  uri: string;
  sourceType: SourceType;
  artist: string;
  album: string;
  durationMs: number;
  sourceId: string;
  folder: string;
  artworkUri: string;
  lyricsUri: string;
  sizeBytes: number;
  lastModified: number;
  mimeType: string;
  isFavorite: boolean;
}

/** 构造占位歌曲，供播放条在未选歌时渲染。 */
export function emptySong(): Song {
  return {
    id: '', title: '未选择歌曲', uri: '', sourceType: SourceType.LOCAL,
    artist: '', album: '', durationMs: 0, sourceId: '', folder: '',
    artworkUri: '', lyricsUri: '', sizeBytes: 0, lastModified: 0,
    mimeType: '', isFavorite: false
  };
}

export function isNas(song: Song): boolean {
  return song.sourceType !== SourceType.LOCAL;
}

/** 与 Android Song.sourceLabel 一致，用于列表副标题与锁屏信息。 */
export function sourceLabel(type: SourceType): string {
  switch (type) {
    case SourceType.LOCAL:
      return '本地';
    case SourceType.SMB:
      return 'NAS · SMB';
    case SourceType.WEBDAV:
      return 'NAS · WebDAV';
    case SourceType.FNOS:
      return '飞牛音乐';
    case SourceType.ONLINE:
      return '在线曲库';
    default:
      return '未知来源';
  }
}

/** 展示用歌手：非本地音源附加来源标识，与 Android toMediaItem() 的策略保持一致。 */
export function displayArtist(song: Song): string {
  const base: string = song.artist.length > 0 ? song.artist : '未知歌手';
  return isNas(song) ? `${base} · ${sourceLabel(song.sourceType)}` : base;
}

/**
 * 转换为 AVPlayer 可用的播放地址。
 * - 本地 / SMB / WebDAV：uri 已是可直接访问的地址，原样返回。
 * - ONLINE：相对路径与 baseUrl 拼接（Android 端同契约）。
 * - FNOS：uri 应为 FnosApi.streamUrl() 生成的带签名地址；因需 authx 头，
 *   真机播放需要本地代理或自定义数据源，见 FnosApi.ets 的 TODO 边界。
 */
export function toPlaybackUri(song: Song, onlineBaseUrl: string = ''): string {
  if (song.sourceType === SourceType.ONLINE && onlineBaseUrl.length > 0 && !song.uri.startsWith('http')) {
    const base: string = onlineBaseUrl.endsWith('/') ? onlineBaseUrl.slice(0, -1) : onlineBaseUrl;
    const path: string = song.uri.startsWith('/') ? song.uri : `/${song.uri}`;
    return `${base}${path}`;
  }
  return song.uri;
}
