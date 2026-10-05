// 只用官方源。上游带的阿里云镜像已移除：Gradle 解析插件标记时按仓库锁定模块，镜像取不到就不回退官方源，
// 只报 "could not resolve plugin artifact"，CI 曾因此挂（构件在 Central 是齐的，别再加回来）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "NotificationCleaner"
include(":app")
