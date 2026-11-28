// 独立 Gradle 工程：刻意不挂到项目根的 settings.gradle.kts。
// 这样 Windows 端可单独构建，Android 端改动不会破坏桌面端配置。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NasMusicDesktop"
