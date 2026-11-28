// 根构建脚本：插件仅在 :app 模块应用，这里保持干净。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
