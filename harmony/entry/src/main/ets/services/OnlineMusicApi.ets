// Subsonic 兼容在线音源客户端：ping / search3 / stream / coverArt。
// 协议与 Android 内核组一致：GET /rest/{view}.view，参数 u/p/v/c/f=json。
// 错误码映射为中文提示，便于 UI 直接展示。
import { http } from '@ohos.net.http';
import { BusinessError } from '@kit.BasicServicesKit';
import { Song, SourceType } from '../model/Song';

interface SubsonicError {
  code: number;
  message: string;
}

interface SubsonicSong {
  id: string;
  title: string;
  artist?: string;
  album?: string;
  duration?: number;
  path?: string;
  coverArt?: string;
  parent?: string;
  size?: number;
  contentType?: string;
}

interface SearchResult3 {
  song?: SubsonicSong[];
}

interface SubsonicBody {
  status: string;
  version?: string;
  error?: SubsonicError;
  searchResult3?: SearchResult3;
}

interface SubsonicEnvelope {
  'subsonic-response': SubsonicBody;
}

export interface ConnectionResult {
  ok: boolean;
  message: string;
}

export class OnlineMusicApi {
  private readonly baseUrl: string;
  private readonly username: string;
  private readonly password: string;
  private readonly clientName: string;
  private readonly apiVersion: string;

  constructor(
    baseUrl: string,
    username: string,
    password: string,
    clientName: string = 'NasMusic',
    apiVersion: string = '1.16.1'
  ) {
    this.baseUrl = baseUrl.trim().endsWith('/') ? baseUrl.trim().slice(0, -1) : baseUrl.trim();
    this.username = username;
    this.password = password;
    this.clientName = clientName;
    this.apiVersion = apiVersion;
  }

  /** 构造带鉴权参数的请求 URL。 */
  private endpoint(view: string, extra: string[]): string {
    const params: string[] = [
      `u=${encodeURIComponent(this.username)}`,
      `p=${encodeURIComponent(this.password)}`,
      `v=${encodeURIComponent(this.apiVersion)}`,
      `c=${encodeURIComponent(this.clientName)}`,
      'f=json'
    ];
    for (const item of extra) {
      params.push(item);
    }
    return `${this.baseUrl}/rest/${view}.view?${params.join('&')}`;
  }

  private async getJson(view: string, extra: string[]): Promise<SubsonicBody> {
    const request: http.HttpRequest = http.createHttp();
    try {
      const response: http.HttpResponse = await request.request(this.endpoint(view, extra), {
        method: http.RequestMethod.GET,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 15000,
        readTimeout: 45000
      });
      if (response.responseCode !== 200) {
        throw new Error(`服务器返回 HTTP ${response.responseCode}`);
      }
      const text: string = response.result as string;
      const envelope: SubsonicEnvelope = JSON.parse(text) as SubsonicEnvelope;
      const body: SubsonicBody | undefined = envelope['subsonic-response'];
      if (body === undefined) {
        throw new Error('响应缺少 subsonic-response 节点');
      }
      return body;
    } finally {
      request.destroy();
    }
  }

  /** 只读探测，布尔结果。 */
  async ping(): Promise<boolean> {
    const body: SubsonicBody = await this.getJson('ping', []);
    return body.status === 'ok';
  }

  /** 供设置页调用的连通性测试，始终返回可读中文。 */
  async testConnection(): Promise<ConnectionResult> {
    try {
      const body: SubsonicBody = await this.getJson('ping', []);
      if (body.status === 'ok') {
        const version: string = body.version !== undefined ? body.version : '未知版本';
        return { ok: true, message: `连接成功：Subsonic ${version}` };
      }
      return { ok: false, message: this.describeError(body.error) };
    } catch (error) {
      return { ok: false, message: this.describeException(error) };
    }
  }

  /** 搜索曲目并映射为统一 Song 模型；uri 为可直接播放的 stream 直链（与 Android 端一致）。 */
  async search3(query: string, songCount: number = 500): Promise<Song[]> {
    const extra: string[] = [
      `query=${encodeURIComponent(query)}`,
      `songCount=${songCount}`,
      'artistCount=0',
      'albumCount=0'
    ];
    const body: SubsonicBody = await this.getJson('search3', extra);
    if (body.status !== 'ok') {
      throw new Error(this.describeError(body.error));
    }
    const raw: SubsonicSong[] = body.searchResult3?.song ?? [];
    const songs: Song[] = [];
    for (const item of raw) {
      if (item.id === undefined || item.id.length === 0) {
        continue;
      }
      const coverId: string = (item.coverArt ?? item.parent ?? '').length > 0
        ? (item.coverArt ?? item.parent ?? '') : '';
      songs.push({
        id: item.id,
        title: item.title,
        uri: this.streamUrl(item.id),
        sourceType: SourceType.ONLINE,
        artist: item.artist ?? '',
        album: item.album ?? '',
        durationMs: (item.duration ?? 0) * 1000,
        sourceId: '',
        folder: '',
        artworkUri: coverId.length > 0 ? this.coverUrl(coverId) : '',
        lyricsUri: '',
        sizeBytes: item.size ?? 0,
        lastModified: 0,
        mimeType: item.contentType ?? '',
        isFavorite: false
      });
    }
    return songs;
  }

  /** 音频流地址（Subsonic 用同一套鉴权参数，可直接交给 AVPlayer）。 */
  streamUrl(id: string): string {
    return this.endpoint('stream', [`id=${encodeURIComponent(id)}`]);
  }

  coverUrl(coverArtId: string, size: number = 300): string {
    return this.endpoint('getCoverArt', [
      `id=${encodeURIComponent(coverArtId)}`,
      `size=${size}`
    ]);
  }

  /** Subsonic 标准错误码 -> 中文提示。 */
  private describeError(error: SubsonicError | undefined): string {
    if (error === undefined) {
      return '服务器返回失败状态，但未说明原因';
    }
    switch (error.code) {
      case 10:
        return '客户端版本过旧，请在设置中升级 API 版本';
      case 20:
        return '用户名或密码错误';
      case 30:
        return '客户端标识不被服务器允许';
      case 40:
        return '请求的资源不存在';
      case 50:
        return '当前账号没有该操作的权限';
      case 60:
        return '服务器试用授权已过期';
      case 70:
        return '服务器版本过低，不支持该接口';
      case 80:
        return '访客账号已被服务器禁用';
      default:
        return error.message.length > 0 ? error.message : `请求失败（code=${error.code}）`;
    }
  }

  private describeException(error: Object): string {
    if (error instanceof Error) {
      const message: string = error.message;
      if (message.includes('HTTP 401') || message.includes('HTTP 403')) {
        return '用户名或密码错误，或账号无访问权限';
      }
      if (message.includes('HTTP 404')) {
        return '找不到服务地址，请检查 URL 是否指向 /rest 所在的服务根路径';
      }
      if (message.includes('timeout') || message.includes('Timeout')) {
        return '连接超时，请检查网络与服务器是否在线';
      }
      return message;
    }
    const e: BusinessError = error as BusinessError;
    return e.message.length > 0 ? e.message : '未知网络错误';
  }
}
