// 飞牛音乐（fnOS）私有网关客户端骨架。地址和 authx 签名逻辑参考 Android 端 data/FnosApi.kt。
// 已实现：地址解析、签名、探测、登录、曲目分页、封面字节、流长度探测。
// TODO 边界：带签名的音频流无法直接交给 AVPlayer（需 authx/Cookie 头），真机播放需要本地 HTTP 代理或自定义数据源。
import { http } from '@ohos.net.http';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@ohos.util';
import { BusinessError } from '@kit.BasicServicesKit';

const API_PREFIX: string = '/music/api/v1';
// 与 Android 端保持一致，修改会导致服务端验签失败。
const SIGN_SALT: string = 'NDzZTVxnRKP8Z0jXg1VAMonaG8akvh';
const CONNECT_SUFFIXES: string[] = ['5ddd.com', 'fnos.net', 'fnofly.com'];

/** Android 端的 CLIENT_KEY 由 obfuscated int 数组 XOR 185 得到，这里保留同样推导方式。 */
function decodeClientKey(): string {
  const encoded: number[] = [
    143, 253, 140, 143, 137, 139, 253, 141, 148, 248, 138, 141, 139,
    148, 141, 142, 128, 128, 148, 248, 137, 255, 137, 148, 251, 251,
    142, 128, 140, 252, 142, 136, 143, 142, 253, 137
  ];
  let result: string = '';
  for (const value of encoded) {
    result += String.fromCharCode(value ^ 185);
  }
  return result;
}

const CLIENT_KEY: string = decodeClientKey();

export interface FnosEndpoint {
  baseUrl: string;
  host: string;
  isConnect: boolean;
  isFnId: boolean;
  label: string;
}

export interface FnosTrack {
  guid: string;
  title: string;
  artist: string;
  album: string;
  coverId: string;
  durationMs: number;
  sizeBytes: number;
  mimeType: string;
  fileName: string;
}

export interface TrackPage {
  list: FnosTrack[];
  total: number;
}

export interface FnosConnectionResult {
  ok: boolean;
  message: string;
}

interface FnosAlbumRaw {
  name?: string;
  coverId?: string;
}

interface FnosArtistRaw {
  name?: string;
  title?: string;
}

interface FnosTrackRaw {
  guid?: string;
  trackGuid?: string;
  title?: string;
  name?: string;
  artist?: string;
  artists?: Object[];
  album?: FnosAlbumRaw | string;
  coverId?: string;
  duration?: number | string;
  durationMs?: number | string;
  length?: number | string;
  time?: number | string;
  size?: number | string;
  fileSize?: number | string;
  byteSize?: number | string;
  fileName?: string;
  path?: string;
}

interface FnosData {
  list?: FnosTrackRaw[];
  total?: number;
  totalCount?: number;
  userToken?: string;
  token?: string;
}

interface FnosEnvelope {
  code?: number;
  msg?: string;
  data?: FnosData;
}

interface CheckedResponse {
  response: http.HttpResponse;
  request: http.HttpRequest;
}

/** token 仅保存在内存，与 Android FnosSession 语义一致。 */
class FnosSessionStore {
  private tokens: Map<string, string> = new Map<string, string>();

  get(sourceId: string): string | undefined {
    return this.tokens.get(sourceId);
  }

  put(sourceId: string, token: string): void {
    this.tokens.set(sourceId, token);
  }

  remove(sourceId: string): void {
    this.tokens.delete(sourceId);
  }
}

export const FnosSession: FnosSessionStore = new FnosSessionStore();

export class FnosAuthException extends Error {
  constructor() {
    super('飞牛音乐登录已过期，请重新登录');
    this.name = 'FnosAuthException';
  }
}

function extractHost(url: string): string {
  const rest: string = url.startsWith('https://')
    ? url.substring(8)
    : (url.startsWith('http://') ? url.substring(7) : url);
  const withoutUser: string = rest.includes('@') ? rest.substring(rest.lastIndexOf('@') + 1) : rest;
  let host: string = withoutUser.split('/')[0].split(':')[0];
  if (host.startsWith('[') && host.endsWith(']')) {
    host = host.substring(1, host.length - 1);
  }
  return host;
}

function isConnectUrl(url: string): boolean {
  const host: string = extractHost(url).toLowerCase();
  for (const suffix of CONNECT_SUFFIXES) {
    if (host === suffix || host.endsWith(`.${suffix}`)) {
      return true;
    }
  }
  return false;
}

/** 统一地址解析：完整 URL / 裸 FN ID / 域名 / IP(:端口)。 */
export function resolveFnosEndpoint(raw: string): FnosEndpoint {
  const text: string = raw.trim().replace(/\/+$/, '');
  if (text.length === 0) {
    throw new Error('请输入飞牛 NAS 地址或 FN ID');
  }
  if (text.startsWith('http://') || text.startsWith('https://')) {
    const url: string = text.endsWith('/music') ? text.slice(0, -6).replace(/\/+$/, '') : text;
    const host: string = extractHost(url);
    if (host.length === 0) {
      throw new Error('服务器地址格式不正确');
    }
    const connect: boolean = isConnectUrl(url);
    return {
      baseUrl: url, host: host, isConnect: connect, isFnId: false,
      label: connect ? 'FN Connect 中继' : '自定义地址'
    };
  }
  const hasPort: boolean = /^.*:[0-9]{1,5}$/.test(text);
  const isIp: boolean = /^[0-9.]+$/.test(text) || /^\[[0-9a-fA-F:.]+\](:[0-9]{1,5})?$/.test(text);
  if (isIp || hasPort) {
    const base: string = `http://${text}${hasPort ? '' : ':5666'}`;
    const host: string = text.startsWith('[') ? text.substring(1, text.indexOf(']')) : text.split(':')[0];
    return { baseUrl: base, host: host, isConnect: false, isFnId: false, label: '局域网地址' };
  }
  if (!text.includes('.') && /^[A-Za-z0-9][A-Za-z0-9-]{1,31}$/.test(text)) {
    const host: string = `${text}.5ddd.com`;
    return {
      baseUrl: `https://${host}`, host: host, isConnect: true, isFnId: true,
      label: `FN ID · ${text}`
    };
  }
  const lower: string = text.toLowerCase();
  let connect: boolean = false;
  for (const suffix of CONNECT_SUFFIXES) {
    if (lower === suffix || lower.endsWith(`.${suffix}`)) {
      connect = true;
    }
  }
  return {
    baseUrl: `https://${text}`, host: text, isConnect: connect, isFnId: false,
    label: connect ? 'FN Connect 中继' : '自定义域名'
  };
}

function bytesToHex(data: Uint8Array): string {
  let result: string = '';
  for (const byte of data) {
    result += (byte & 0xff).toString(16).padStart(2, '0');
  }
  return result;
}

async function hashHex(algorithm: string, text: string): Promise<string> {
  const md: cryptoFramework.Md = cryptoFramework.createMd(algorithm);
  const encoder: util.TextEncoder = new util.TextEncoder();
  await md.update({ data: encoder.encode(text) });
  const digest: cryptoFramework.DataBlob = await md.digest();
  return bytesToHex(digest.data);
}

function md5Hex(text: string): Promise<string> {
  return hashHex('MD5', text);
}

function sha256Hex(text: string): Promise<string> {
  return hashHex('SHA256', text);
}

function decodeQuery(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch (error) {
    return value;
  }
}

/** 服务端按 Go url.Values.Encode 语义校验：解码后的 query 按 key 排序。 */
function canonicalQuery(raw: string): string {
  if (raw.length === 0) {
    return '';
  }
  const map: Map<string, string> = new Map<string, string>();
  for (const pair of raw.split('&')) {
    if (pair.length === 0) {
      continue;
    }
    const index: number = pair.indexOf('=');
    const key: string = index < 0 ? pair : pair.substring(0, index);
    const value: string = index < 0 ? '' : pair.substring(index + 1);
    map.set(decodeQuery(key), decodeQuery(value));
  }
  const keys: string[] = Array.from(map.keys()).sort();
  const parts: string[] = [];
  for (const key of keys) {
    parts.push(`${key}=${map.get(key) ?? ''}`);
  }
  return parts.join('&');
}

/** authx 头：nonce / timestamp / payloadHash / CLIENT_KEY 参与 MD5 签名。 */
async function signHeader(method: string, path: string, query: string, body: string): Promise<string> {
  const payloadHash: string = method.toUpperCase() === 'GET'
    ? await md5Hex(canonicalQuery(query))
    : await md5Hex(body);
  const nonce: string = Math.floor(100000 + Math.random() * 900000).toString();
  const timestamp: string = Date.now().toString();
  const sign: string = await md5Hex(
    `${SIGN_SALT}_${path}_${nonce}_${timestamp}_${payloadHash}_${CLIENT_KEY}`
  );
  return `nonce=${nonce}&timestamp=${timestamp}&sign=${sign}`;
}

/** 复刻 Android UUID.nameUUIDFromBytes 的 v3 UUID，保证 deviceId 跨端一致。 */
async function stableUuid(text: string): Promise<string> {
  const hex: string = await md5Hex(text);
  const bytes: number[] = [];
  for (let i: number = 0; i < 32; i += 2) {
    bytes.push(parseInt(hex.substring(i, i + 2), 16));
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const segment = (start: number, end: number): string => {
    const slice: string[] = [];
    for (let i: number = start; i < end; i++) {
      slice.push(bytes[i].toString(16).padStart(2, '0'));
    }
    return slice.join('');
  };
  return `${segment(0, 4)}-${segment(4, 6)}-${segment(6, 8)}-${segment(8, 10)}-${segment(10, 16)}`;
}

export class FnosApi {
  readonly endpoint: FnosEndpoint;
  private readonly username: string;
  private readonly password: string;
  private readonly sourceId: string;
  private readonly relay: boolean;
  private deviceId: string = '';

  constructor(baseUrl: string, username: string, password: string, sourceId: string) {
    this.endpoint = resolveFnosEndpoint(baseUrl);
    this.username = username;
    this.password = password;
    this.sourceId = sourceId;
    this.relay = this.endpoint.isConnect;
  }

  private async getDeviceId(): Promise<string> {
    if (this.deviceId.length === 0) {
      this.deviceId = await stableUuid(`${this.endpoint.baseUrl}|${this.username}`);
    }
    return this.deviceId;
  }

  private cookieHeader(token: string): string {
    const parts: string[] = [];
    if (this.relay) {
      parts.push('mode=relay');
    }
    if (token.length > 0) {
      parts.push(`music-token=${token}`);
    }
    return parts.join('; ');
  }

  private buildOptions(
    authx: string,
    versionHeader: boolean,
    cookie: string,
    range: string,
    body: string
  ): http.HttpRequestOptions {
    const headers: Record<string, string> = { 'authx': authx };
    if (versionHeader) {
      headers['X-Music-API'] = 'v1';
    }
    if (cookie.length > 0) {
      headers['Cookie'] = cookie;
    }
    if (range.length > 0) {
      headers['Range'] = range;
    }
    if (body.length > 0) {
      headers['Content-Type'] = 'application/json; charset=utf-8';
    }
    const options: http.HttpRequestOptions = {
      method: body.length > 0 ? http.RequestMethod.POST : http.RequestMethod.GET,
      header: headers,
      expectDataType: http.HttpDataType.STRING,
      connectTimeout: 15000,
      readTimeout: 45000
    };
    if (body.length > 0) {
      options.extraData = body;
    }
    return options;
  }

  /**
   * 受控执行：禁止跨主机跳转。FN Connect 可能 302 到内网地址，
   * 跟随会泄露 Cookie 中的 music-token；同主机跳转最多跟随 3 次。
   */
  private async executeChecked(url: string, options: http.HttpRequestOptions): Promise<CheckedResponse> {
    let currentUrl: string = url;
    for (let attempt: number = 0; attempt < 4; attempt++) {
      const request: http.HttpRequest = http.createHttp();
      const response: http.HttpResponse = await request.request(currentUrl, options);
      const code: number = response.responseCode;
      if (code >= 300 && code < 400) {
        const location: string = response.header['Location'] ?? response.header['location'] ?? '';
        request.destroy();
        if (location.length === 0) {
          throw new Error('服务器跳转地址为空');
        }
        const absolute: string = location.startsWith('http')
          ? location : `${this.endpoint.baseUrl}${location}`;
        if (extractHost(absolute).toLowerCase() !== this.endpoint.host.toLowerCase()) {
          throw new Error('服务器跳转到其他主机，已停止以保护登录凭证。请确认已开启 FN Connect 中继。');
        }
        currentUrl = absolute;
        continue;
      }
      return { response: response, request: request };
    }
    throw new Error('服务器重定向次数过多');
  }

  private isChallengePage(text: string): boolean {
    const head: string = text.substring(0, 600).toLowerCase();
    return head.includes('<!doctype html') || head.includes('<html') || head.includes('<head');
  }

  private challengeError(): Error {
    return new Error(this.relay
      ? 'FN Connect 返回了网页校验页。请先在浏览器用同一 FN ID 登录完成设备校验，或关闭外网访问校验码后重试。'
      : '服务器返回了网页而非接口数据，请确认地址是飞牛 NAS 的 5666 端口');
  }

  private parseEnvelope(text: string): FnosEnvelope {
    if (this.isChallengePage(text)) {
      throw this.challengeError();
    }
    try {
      return JSON.parse(text) as FnosEnvelope;
    } catch (error) {
      throw new Error(`飞牛音乐返回了无法解析的内容：${text.substring(0, 80)}`);
    }
  }

  /** 连通性探测：不要求登录态。 */
  async probe(): Promise<FnosConnectionResult> {
    const path: string = `${API_PREFIX}/initialization/state`;
    try {
      const authx: string = await signHeader('GET', path, '', '');
      const checked: CheckedResponse = await this.executeChecked(
        `${this.endpoint.baseUrl}${path}`, this.buildOptions(authx, true, this.cookieHeader(''), '', '')
      );
      try {
        const code: number = checked.response.responseCode;
        if (code === 401 || code === 403) {
          return { ok: true, message: '飞牛音乐服务可达（需要登录）' };
        }
        if (code !== 200) {
          return { ok: false, message: `服务器不可达：HTTP ${code}` };
        }
        const text: string = checked.response.result as string;
        if (this.isChallengePage(text)) {
          throw this.challengeError();
        }
        return {
          ok: true,
          message: this.relay ? 'FN Connect 中继可达' : '飞牛音乐服务可达'
        };
      } finally {
        checked.request.destroy();
      }
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      const message: string = e.message.length > 0 ? e.message : '未知网络错误';
      return { ok: false, message: `连接失败：${message}` };
    }
  }

  /** 密码登录，成功后 token 写入内存会话。 */
  async login(): Promise<string> {
    if (this.username.trim().length === 0) {
      throw new Error('飞牛音乐账号为空，请重新登录');
    }
    const path: string = `${API_PREFIX}/user/password-login`;
    const body: string = JSON.stringify({
      username: this.username.trim(),
      password: await sha256Hex(this.password),
      deviceId: await this.getDeviceId()
    });
    // 登录握手不能带版本头。
    const authx: string = await signHeader('POST', path, '', body);
    const checked: CheckedResponse = await this.executeChecked(
      `${this.endpoint.baseUrl}${path}`, this.buildOptions(authx, false, '', '', body)
    );
    try {
      const code: number = checked.response.responseCode;
      if (code !== 200) {
        throw new Error(`登录失败：HTTP ${code}`);
      }
      const text: string = checked.response.result as string;
      const env: FnosEnvelope = this.parseEnvelope(text);
      if (env.code !== undefined && env.code !== 0 && env.code !== 200) {
        const msg: string = env.msg !== undefined && env.msg.length > 0
          ? env.msg : `账号或密码错误（code=${env.code}）`;
        throw new Error(msg);
      }
      const data: FnosData | undefined = env.data;
      const token: string = data?.userToken ?? data?.token ?? '';
      if (token.length === 0) {
        throw new Error('服务器没有返回登录凭证');
      }
      FnosSession.put(this.sourceId, token);
      return token;
    } finally {
      checked.request.destroy();
    }
  }

  private async ensureToken(): Promise<string> {
    const cached: string | undefined = FnosSession.get(this.sourceId);
    if (cached !== undefined && cached.length > 0) {
      return cached;
    }
    return await this.login();
  }

  private async callJson(subPath: string, query: string, token: string): Promise<FnosEnvelope> {
    const path: string = API_PREFIX + subPath;
    const authx: string = await signHeader('GET', path, query, '');
    const url: string = `${this.endpoint.baseUrl}${path}?${query}`;
    const checked: CheckedResponse = await this.executeChecked(
      url, this.buildOptions(authx, true, this.cookieHeader(token), '', '')
    );
    try {
      const code: number = checked.response.responseCode;
      if (code === 401 || code === 403) {
        throw new FnosAuthException();
      }
      if (code !== 200) {
        throw new Error(`请求失败：HTTP ${code}`);
      }
      const text: string = checked.response.result as string;
      const env: FnosEnvelope = this.parseEnvelope(text);
      if (env.code !== undefined && env.code !== 0 && env.code !== 200) {
        if (env.code === 120001 || env.code === 401 || env.code === 403) {
          throw new FnosAuthException();
        }
        const msg: string = env.msg !== undefined && env.msg.length > 0
          ? env.msg : `曲库请求失败（${subPath}，code=${env.code}）`;
        throw new Error(msg);
      }
      return env;
    } finally {
      checked.request.destroy();
    }
  }

  /** 曲目分页：data.list + data.total，字段名兼容飞牛 Web 端多个版本。 */
  async tracks(page: number, size: number): Promise<TrackPage> {
    const fetch = async (token: string): Promise<TrackPage> => {
      const env: FnosEnvelope = await this.callJson('/track/list', `page=${page}&size=${size}`, token);
      const rawList: FnosTrackRaw[] = env.data?.list ?? [];
      const total: number = env.data?.total ?? env.data?.totalCount ?? -1;
      const list: FnosTrack[] = [];
      for (const item of rawList) {
        const guid: string = item.guid ?? item.trackGuid ?? '';
        if (guid.length === 0) {
          continue;
        }
        const album: FnosAlbumRaw | string | undefined = item.album;
        const albumObj: FnosAlbumRaw | undefined = typeof album === 'string' ? undefined : album;
        const durationRaw: number = this.pickNumber(
          item.duration, item.durationMs, item.length, item.time
        );
        const durationMs: number = durationRaw <= 0 ? 0
          : (durationRaw < 10000 ? durationRaw * 1000 : durationRaw);
        const fileName: string = item.fileName ?? item.path ?? '';
        list.push({
          guid: guid,
          title: (item.title ?? item.name ?? '').length > 0
            ? (item.title ?? item.name ?? '') : '未知曲目',
          artist: this.artistNames(item.artists).length > 0
            ? this.artistNames(item.artists) : (item.artist ?? '未知歌手'),
          album: albumObj?.name ?? (typeof album === 'string' ? album : '') ?? '',
          coverId: item.coverId ?? albumObj?.coverId ?? '',
          durationMs: durationMs,
          sizeBytes: this.pickNumber(item.size, item.fileSize, item.byteSize, undefined),
          mimeType: this.mimeForName(fileName),
          fileName: fileName
        });
      }
      return { list: list, total: total };
    };

    const token: string = await this.ensureToken();
    try {
      return await fetch(token);
    } catch (error) {
      if (!(error instanceof FnosAuthException)) {
        throw error;
      }
      FnosSession.remove(this.sourceId);
      return await fetch(await this.login());
    }
  }

  private pickNumber(
    a: number | string | undefined,
    b: number | string | undefined,
    c: number | string | undefined,
    d: number | string | undefined
  ): number {
    for (const value of [a, b, c, d]) {
      if (value === undefined) {
        continue;
      }
      const parsed: number = typeof value === 'number' ? value : Number(value.trim());
      if (!Number.isNaN(parsed) && parsed > 0) {
        return parsed;
      }
    }
    return 0;
  }

  private artistNames(artists: Object[] | undefined): string {
    if (artists === undefined) {
      return '';
    }
    const names: string[] = [];
    for (const item of artists) {
      if (typeof item === 'string') {
        names.push(item);
        continue;
      }
      const artist: FnosArtistRaw = item as FnosArtistRaw;
      const name: string = artist.name ?? artist.title ?? '';
      if (name.length > 0) {
        names.push(name);
      }
    }
    return names.join(' / ');
  }

  private mimeForName(name: string): string {
    const dot: number = name.lastIndexOf('.');
    const ext: string = dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
    switch (ext) {
      case 'mp3': return 'audio/mpeg';
      case 'flac': return 'audio/flac';
      case 'wav': return 'audio/wav';
      case 'aac': return 'audio/aac';
      case 'ogg':
      case 'opus': return 'audio/ogg';
      case 'm4a':
      case 'm4b':
      case 'mp4': return 'audio/mp4';
      case 'ape': return 'audio/ape';
      case 'wma': return 'audio/x-ms-wma';
      default: return '';
    }
  }

  /** 封面为二进制响应，需带签名；调用方应写入应用沙箱后再交给 Image 组件。 */
  async coverBytes(coverId: string, size: number = 300): Promise<Uint8Array | undefined> {
    const token: string = await this.ensureToken();
    const query: string = `coverId=${encodeURIComponent(coverId)}&size=${size}`;
    const path: string = `${API_PREFIX}/static/cover`;
    const authx: string = await signHeader('GET', path, query, '');
    const options: http.HttpRequestOptions = this.buildOptions(
      authx, true, this.cookieHeader(token), '', ''
    );
    options.expectDataType = http.HttpDataType.ARRAY_BUFFER;
    const checked: CheckedResponse = await this.executeChecked(
      `${this.endpoint.baseUrl}${path}?${query}`, options
    );
    try {
      if (checked.response.responseCode !== 200) {
        return undefined;
      }
      const data: ArrayBuffer = checked.response.result as ArrayBuffer;
      return new Uint8Array(data);
    } finally {
      checked.request.destroy();
    }
  }

  /**
   * 音频流地址：注意该 URL 缺少 authx 头和 music-token Cookie，
   * 不能用 AVPlayer 直接播放。真机方案：本地代理把签名头注入后再转发。
   */
  streamUrl(guid: string): string {
    return `${this.endpoint.baseUrl}${API_PREFIX}/track/stream?guid=${encodeURIComponent(guid)}`;
  }

  /** 供本地代理/自定义数据源使用的请求头。 */
  async streamHeaders(guid: string, token: string): Promise<Record<string, string>> {
    const query: string = `guid=${encodeURIComponent(guid)}`;
    const path: string = `${API_PREFIX}/track/stream`;
    const authx: string = await signHeader('GET', path, query, '');
    return {
      'authx': authx,
      'X-Music-API': 'v1',
      'Cookie': this.cookieHeader(token)
    };
  }

  /** Range 0-0 探测音频总长度，进度条可提前拿到时长。 */
  async streamLength(guid: string): Promise<number> {
    const token: string = await this.ensureToken();
    const query: string = `guid=${encodeURIComponent(guid)}`;
    const path: string = `${API_PREFIX}/track/stream`;
    const authx: string = await signHeader('GET', path, query, '');
    const checked: CheckedResponse = await this.executeChecked(
      `${this.endpoint.baseUrl}${path}?${query}`,
      this.buildOptions(authx, true, this.cookieHeader(token), 'bytes=0-0', '')
    );
    try {
      const code: number = checked.response.responseCode;
      if (code !== 206 && code !== 200) {
        throw new Error(`音频流请求失败：HTTP ${code}`);
      }
      const range: string = checked.response.header['Content-Range'] ?? '';
      const slash: number = range.lastIndexOf('/');
      if (slash >= 0) {
        const length: number = Number(range.substring(slash + 1));
        if (!Number.isNaN(length)) {
          return length;
        }
      }
      const contentLength: string = checked.response.header['Content-Length'] ?? '0';
      return Number(contentLength) || 0;
    } finally {
      checked.request.destroy();
    }
  }
}
