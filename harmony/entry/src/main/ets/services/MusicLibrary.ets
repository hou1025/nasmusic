// 曲库服务：本地音频扫描 + 音源配置持久化 + 凭据加解密。
// 真机适配提示：mediaLibrary 的取数接口与 cryptoFramework 的密钥管理在不同机型/SDK 小版本上有差异，
// 本文件给出可编译的调用骨架，正式发布前需在真机验证并把密钥迁到 HUKS 安全存储。
import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { mediaLibrary } from '@ohos.multimedia.mediaLibrary';
import { util } from '@ohos.util';
import { buffer } from '@ohos.buffer';
import { Song, SourceType, emptySong } from '../model/Song';
import { NasSource } from '../model/NasSource';

const DOMAIN: number = 0x4E4D;
const TAG: string = 'MusicLibrary';
const SOURCES_KEY: string = 'nas_sources_v1';

/** 凭据加解密的封装接口，便于后续替换为 HUKS 实现而不改动调用方。 */
export interface CredentialCipher {
  encrypt(plain: string): Promise<string>;
  decrypt(payload: string): Promise<string>;
}

/** AES-256/CBC/PKCS7 封装：返回 "base64(iv):base64(ciphertext)"。 */
export class AesCredentialCipher implements CredentialCipher {
  private key: cryptoFramework.SymKey | undefined = undefined;

  private async ensureKey(): Promise<cryptoFramework.SymKey> {
    if (this.key !== undefined) {
      return this.key;
    }
    // TODO(真机适配)：当前每次启动生成新密钥，旧密文将无法解密。
    // 正式版本应使用 @ohos.security.huks 生成并持久化密钥别名，由 TEE 保护。
    const generator: cryptoFramework.SymKeyGenerator = cryptoFramework.createSymKeyGenerator('AES256');
    this.key = await generator.generateKey();
    return this.key;
  }

  async encrypt(plain: string): Promise<string> {
    const key: cryptoFramework.SymKey = await this.ensureKey();
    const cipher: cryptoFramework.Cipher = cryptoFramework.createCipher('AES256|CBC|PKCS7');
    const iv: cryptoFramework.DataBlob = cipher.genIv();
    await cipher.init(cryptoFramework.CryptoMode.ENCRYPT_MODE, key, iv);
    const out: cryptoFramework.DataBlob = await cipher.doFinal(toDataBlob(plain));
    return `${toBase64(iv.data)}:${toBase64(out.data)}`;
  }

  async decrypt(payload: string): Promise<string> {
    const parts: string[] = payload.split(':');
    if (parts.length !== 2) {
      throw new Error('凭据格式无效，请重新输入密码');
    }
    const key: cryptoFramework.SymKey = await this.ensureKey();
    const cipher: cryptoFramework.Cipher = cryptoFramework.createCipher('AES256|CBC|PKCS7');
    const iv: cryptoFramework.DataBlob = { data: fromBase64(parts[0]) };
    await cipher.init(cryptoFramework.CryptoMode.DECRYPT_MODE, key, iv);
    const out: cryptoFramework.DataBlob = await cipher.doFinal({ data: fromBase64(parts[1]) });
    return fromUtf8(out.data);
  }
}

function toDataBlob(text: string): cryptoFramework.DataBlob {
  const encoder: util.TextEncoder = new util.TextEncoder();
  return { data: encoder.encode(text) };
}

function fromUtf8(data: Uint8Array): string {
  const decoder: util.TextDecoder = new util.TextDecoder();
  return decoder.decodeToString(data);
}

function toBase64(data: Uint8Array): string {
  return buffer.from(data).toString('base64');
}

function fromBase64(text: string): Uint8Array {
  const buf: buffer.Buffer = buffer.from(text, 'base64');
  const bytes: Uint8Array = new Uint8Array(buf.length);
  for (let i: number = 0; i < buf.length; i++) {
    bytes[i] = buf[i];
  }
  return bytes;
}

export class MusicLibrary {
  private context: common.Context | undefined = undefined;
  private store: preferences.Preferences | undefined = undefined;
  private cipher: CredentialCipher = new AesCredentialCipher();

  attachContext(context: common.Context): void {
    this.context = context;
  }

  private requireContext(): common.Context {
    if (this.context === undefined) {
      throw new Error('MusicLibrary 尚未绑定 Context');
    }
    return this.context;
  }

  private async getStore(): Promise<preferences.Preferences> {
    if (this.store !== undefined) {
      return this.store;
    }
    this.store = await preferences.getPreferences(this.requireContext(), 'nasmusic_library');
    return this.store;
  }

  /** 使用 mediaLibrary 查询本机音频；失败时返回空数组并写日志，不向 UI 抛异常。 */
  async scanLocalSongs(): Promise<Song[]> {
    const songs: Song[] = [];
    try {
      const lib: mediaLibrary.MediaLibrary = await mediaLibrary.getMediaLibrary(this.requireContext());
      const options: mediaLibrary.MediaFetchOptions = {
        selections: 'media_type = ?',
        selectionArgs: [mediaLibrary.MediaType.AUDIO.toString()],
        order: 'date_added DESC'
      };
      const result: mediaLibrary.FetchResult<mediaLibrary.FileAsset> = await lib.getFileAssets(options);
      const count: number = result.getCount();
      for (let i: number = 0; i < count; i++) {
        const asset: mediaLibrary.FileAsset = result.getObjectByPosition(i);
        songs.push(this.assetToSong(asset));
      }
      result.close();
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      hilog.error(DOMAIN, TAG, 'scanLocalSongs failed: %{public}d %{public}s', e.code, e.message);
    }
    return songs;
  }

  private assetToSong(asset: mediaLibrary.FileAsset): Song {
    const song: Song = emptySong();
    song.id = asset.id.toString();
    song.title = asset.title.length > 0 ? asset.title : asset.displayName;
    song.uri = asset.uri;
    song.sourceType = SourceType.LOCAL;
    song.artist = asset.artist;
    song.album = asset.album;
    // mediaLibrary.duration 单位为秒，统一模型使用毫秒。
    song.durationMs = asset.duration * 1000;
    song.sizeBytes = asset.size;
    song.lastModified = asset.dateModified * 1000;
    song.mimeType = asset.mimeType;
    song.folder = '';
    return song;
  }

  /** 读取全部音源配置。无配置时返回空数组，不预置虚假数据。 */
  async listSources(): Promise<NasSource[]> {
    try {
      const store: preferences.Preferences = await this.getStore();
      const text: string = await store.get(SOURCES_KEY, '[]') as string;
      return JSON.parse(text) as NasSource[];
    } catch (error) {
      const e: BusinessError = error as BusinessError;
      hilog.error(DOMAIN, TAG, 'listSources failed: %{public}s', e.message);
      return [];
    }
  }

  /** 新增或更新音源；未带 id 时自动生成。密码需先经 encryptPassword 处理。 */
  async saveSource(source: NasSource): Promise<void> {
    const target: NasSource = { ...source };
    if (target.id.length === 0) {
      target.id = util.generateRandomUUID();
    }
    const sources: NasSource[] = await this.listSources();
    const index: number = sources.findIndex((item: NasSource) => item.id === target.id);
    if (index >= 0) {
      sources[index] = target;
    } else {
      sources.push(target);
    }
    await this.persistSources(sources);
  }

  async deleteSource(id: string): Promise<void> {
    const sources: NasSource[] = await this.listSources();
    const kept: NasSource[] = sources.filter((item: NasSource) => item.id !== id);
    await this.persistSources(kept);
  }

  private async persistSources(sources: NasSource[]): Promise<void> {
    const store: preferences.Preferences = await this.getStore();
    await store.put(SOURCES_KEY, JSON.stringify(sources));
    await store.flush();
  }

  encryptPassword(plain: string): Promise<string> {
    return this.cipher.encrypt(plain);
  }

  decryptPassword(payload: string): Promise<string> {
    return this.cipher.decrypt(payload);
  }
}

/** 全局单例，由 EntryAbility.onCreate 注入 Context。 */
export const musicLibrary: MusicLibrary = new MusicLibrary();
