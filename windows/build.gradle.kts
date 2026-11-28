// 技术栈与 Android 端一致：Kotlin + Compose（Desktop 目标）。
// 版本选择：Compose Multiplatform 1.6.11（稳定版）+ Kotlin 1.9.24 + Gradle 8.9。
plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "com.nasmusic"
version = "0.1.0-desktop"

repositories {
    google()
    mavenCentral()
}

// JavaFX Media 按平台选择原生分类器（用于音频解码，见 README 的限制说明）。
val osName = System.getProperty("os.name").lowercase()
val javafxPlatform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") || osName.contains("darwin") -> "mac"
    else -> "linux"
}
val javafxVersion = "21.0.2"

dependencies {
    // Compose Desktop 运行时（自带 Skia 渲染，不依赖 JavaFX 做 UI）
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // 领域 Flow / JSON 持久化（JSON 用 kotlinx.serialization，随 Android 端技术栈）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // JavaFX Media：mp3/wav 可用，flac 支持有限（README 已注明）
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-media:$javafxVersion:$javafxPlatform")
}

kotlin {
    // 使用 JDK 17 工具链；首次构建若无 JDK 17，Gradle 会尝试自动下载。
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "com.nasmusic.desktop.MainKt"
        nativeDistributions {
            // 应用名与 Android 端 strings.xml 的 app_name 一致（"音乐"）
            packageName = "音乐"
            packageVersion = "2.3.10"
            // 只出 AppImage 目录：msi/exe 需要下载 WiX（本机离线，暂不启用）
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage)
            windows {
                // 由 tools/MakeIcon.java 按 Android 矢量图标生成
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "音乐"
                // 固定 UUID，后续升级安装不会被当成新应用
                upgradeUuid = "7f3c1a52-9e64-4cd8-9a11-2b6c5d84e930"
            }
        }
    }
}
