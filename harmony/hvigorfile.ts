// 工程级 Hvigor 构建脚本。当前仅保留插件挂载点，未注入自定义构建逻辑。
import { hvigor, HvigorNode, HvigorPlugin } from '@ohos/hvigor';

const nasMusicHarmonyPlugin: HvigorPlugin = {
  pluginId: 'nasMusicHarmonyPlugin',
  apply(_node: HvigorNode): void {
    // 预留：后续可在此注入版本号、渠道号等构建期常量。
  }
};

export default {
  system: hvigor,
  plugins: [nasMusicHarmonyPlugin]
};
