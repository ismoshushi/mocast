import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 发版用的签名信息放在仓库根目录的 keystore.properties（不入库）。
// 没有这个文件时，release 退回 debug 签名，保证本地也能 assembleRelease。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.mocast"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mocast"
        minSdk = 26
        targetSdk = 35
        versionCode = 406
        versionName = "0.4.6"
        ndk {
            // 只留 arm64-v8a：Sherpa-ONNX AAR 的 .so 体积最大，x86_64/armv7a 会让 APK 胖几十 MB。
            // 影响：x86_64 模拟器装不上（启动即崩，缺 .so）；开发调试用 arm64 模拟器镜像或真机。
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // YouTube 视频流走 DASH/HLS：media3 的 dash/hls 是独立 artifact，
    // 不加会在 setMediaItems 时 ClassNotFoundException（HlsMediaSource$Factory）。
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WorkManager 2.9.0（0.4.0 补项：自动刷新周期任务）。
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Room 2.6.1（0.4.0 数据层）：runtime + ktx（withTransaction）+ KSP 注解处理器。
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // NewPipe Extractor v0.27.1（0.4.0 YouTube 视频，JitPack，见 settings.gradle.kts）。
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")

    // Sherpa-ONNX 1.13.8（0.4.0 本机 ASR）：不在 Maven Central，没有可用坐标。
    // 集成第一步，手动下载 AAR 到 app/libs/ 并核对附件名与 SHA-256：
    //   curl -SL -o app/libs/sherpa-onnx-1.13.8.aar \
    //     https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
    // AAR 不入库（.gitignore 已忽略 app/libs/*.aar）。没放 AAR 就编不过。
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // 只用来解 tar.bz2 模型包，约 1MB。
    implementation("org.apache.commons:commons-compress:1.26.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
