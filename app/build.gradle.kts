import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 签名从 local.properties 读（该文件被 .gitignore 忽略）：CI 解出 secrets 里的固定钥后写入这四个键，
// 本机没有该文件时不建签名配置 —— 那时 release 产物是 app-release-unsigned.apk（装不上，CI 据此断言）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signStoreFile = localProps.getProperty("storeFile")
val hasSigning = !signStoreFile.isNullOrBlank()

@Suppress("UnstableApiUsage")
android {
    namespace = "io.github.vstory.hook.notifyfilter"
    // 1.2.1：libxposed service 102 要求 compileSdk ≥ 37（仅编译期，targetSdk 保持 36）
    // AGP 9 新 DSL：自 API 37 起平台包名带 minor，minorApiLevel=0 ⇒ AGP 去找 platforms/android-37.0
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    // 与 CI 装的 build-tools 对齐：不钉住时 AGP 会挑自己默认的版本，runner 上不一定有
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.vstory.hook.notifyfilter"
        // dev 分支 UI 改造（Material 3 Expressive / Material You）：minSdk 提升至 33
        // —— Android 12+ 动态取色全量可用，且无需为低版本维护取色降级路径
        minSdk = 33
        targetSdk = 36
        versionCode = 72
        versionName = "2.1.2"
        // 1.3.2（P3-7③）：只保留 arm64-v8a——剔除其余架构（armeabi-v7a/x86/x86_64）
        // 的原生库，精简 APK 体积；目标设备为真机 ARM64（模块端同样仅注入 arm64 设备）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 未配签名（无 local.properties）时不建该配置，纯构建照常可跑
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signStoreFile!!)
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
                // v1（JAR 签名）只对 API < 24 有意义，本项目 minSdk 33 ⇒ 关；v4 会多产 .idsig，不分发
                // ⚠️ v2 与 v3 **两个块都会写进产物**。别用 `apksigner verify --verbose` 的布尔值判断
                //    v2 在不在 —— minSdk ≥ 28 时 apksig 视 v2 为冗余、跳过其验证与上报（报 v2:false），
                //    那是上报语义而非块缺失。要判存在性：加 --min-sdk-version 24，或直接读
                //    APK Signing Block 的块 ID（v2=0x7109871a / v3=0xf05368c0）。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            // debug 与正式版共用同一把固定钥：CI 的 Verify 步断言产物指纹等于固定钥，
            // 且两变体同签名才能互相覆盖安装（卸载会丢 LSPosed 作用域状态）
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig {
        // 应用内更新源（单通道）：本仓库 main 上的 latest.json，正式版发版时由 build-release.yml
        // 回写（versionCode/versionName/notes/sha256）。⚠️ 回写与本处读取必须同仓库同分支，
        // 否则客户端永远看不到新版本。
        // 下载 URL 按版本号拼（与工作流的 tag / 资产名一一对应）：
        //   {base}/v{versionName 去空格}.{versionCode}/NotiCleaner.{同}.{code}.release.apk
        buildConfigField("String", "UPDATE_LATEST",
            "\"https://raw.githubusercontent.com/Vstory/NotificationCleaner/main/latest.json\"")
        buildConfigField("String", "UPDATE_APK_BASE",
            "\"https://github.com/Vstory/NotificationCleaner/releases/download\"")
    }
    sourceSets {
        // 1.3.2（P3-7②）：model.bin 已移至 src/main/resources/model/（单通道打包）——
        // APP 端与模块端统一走 classLoader.getResourceAsStream("model/model.bin")，
        // 不再经 assets srcDir 双份打包（原 assets/resources 各 ~0.5MB 冗余）
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

// AGP 9 内置 Kotlin：编译器选项从 android.kotlinOptions 迁到顶层 kotlin.compilerOptions，
// 旧的 android.kotlinOptions{} 已不再存在（Unresolved reference）。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // ⚠️ BOM 必须 ≥ 2026.09.00：miuix 要求 CMP foundation 1.12（转发到 androidx compose 1.12.x），
    //    BOM 的版本约束优先级高于传递依赖 ⇒ 钉在 2026.06.01（compose 1.11.4）会把 miuix 的
    //    1.12 依赖压回 1.11 而编译失败。该 BOM 下 material3 仍是 1.4.0（未变），故升级不影响既有屏。
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // miuix（Mishka 同源设计语言）。坐标**必须带 `-android` 后缀**：那是 KMP 库发布给
    // AndroidX Compose 工程的变体，带源码的 AAR；不带后缀的主件会拉进 CMP 运行时并与之冲突。
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.4")

    // dev 分支 UI 改造（1.4.0 Dev 4，方案 C）：Kyant0 Backdrop——液态玻璃效果
    // （backdrop 采样 + AGSL 折射着色器 + RenderEffect 模糊，minSdk 33 全量可用）。
    // 注意：2.x 依赖 CMP 1.12（androidx compose 1.12 要求 AGP 9.1+），1.0.6 基于
    // androidx compose 1.10 与当前 AGP 8.13 / compose 1.11 兼容
    implementation("io.github.kyant0:backdrop:1.0.6")
    // backdrop 的平滑圆角形状库（Capsule/RoundedRectangle + Continuous 连续曲率）：
    // pom 里声明了传递依赖，但 Gradle 按 .module 元数据解析 KMP 库时 Android 变体不带它，
    // 必须显式引入——lens 折射着色器的形状白名单只认这个库的 RoundedRectangularShape
    implementation("io.github.kyant0:shapes:1.2.0")

    implementation("androidx.room:room-runtime:2.8.2")
    implementation("androidx.room:room-ktx:2.8.2")
    ksp("androidx.room:room-compiler:2.8.2")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Shizuku（用户主动启用时才请求授权）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // LSPosed Modern API（libxposed API 102）：模块端仅编译期，运行时由 LSPosed 提供（不打入 APK）
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:annotation:1.0.0")
    // APP 侧框架服务（1.2.1）：模块激活检测 + delta 远程文件通道
    implementation("io.github.libxposed:service:102.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
