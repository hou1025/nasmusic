// 音源配置模型，与 Android 端 core/Models.kt 的 NasSource 对齐。
// 密码字段按需求命名 passwordEncrypted（Android 端为 passwordEnc），存密文而非明文。
import { SourceType, sourceLabel } from './Song';

export interface NasSource {
  id: string;
  name: string;
  type: SourceType;
  baseUrl: string;
  host: string;
  port: number;
  share: string;
  rootPath: string;
  username: string;
  /** AES 密文（Base64），解密由 services/CredentialCipher 负责。 */
  passwordEncrypted: string;
  anonymous: boolean;
  lastScanAt: number;
}

export function emptyNasSource(): NasSource {
  return {
    id: '', name: '', type: SourceType.LOCAL, baseUrl: '', host: '', port: 0,
    share: '', rootPath: '', username: '', passwordEncrypted: '', anonymous: false,
    lastScanAt: 0
  };
}

/** 列表副标题：优先显示 host:port，其次 baseUrl。 */
export function sourceSubtitle(source: NasSource): string {
  if (source.host.length > 0) {
    return source.port > 0 ? `${source.host}:${source.port}` : source.host;
  }
  return source.baseUrl.length > 0 ? source.baseUrl : sourceLabel(source.type);
}
