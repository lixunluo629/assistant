import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名的密钥信息放在 android/keystore.properties（已 gitignore，不进版本库）。
// 该文件不存在时，release 包回退用 debug 签名，方便本地出包自测。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { stream -> this.load(stream) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

// 平台识别规则表由 core/ 统一维护：Python 核心库与 Android 端读同一份文件，
// 改规则不用改代码，也不会出现两端判断不一致。
val syncPlatformRules by tasks.registering(Copy::class) {
    from(rootProject.file("../core/platform_rules.json"))
    into(layout.buildDirectory.dir("generated/platform-rules"))
}

android {
    namespace = "com.szpdc.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.szpdc.assistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 20
        versionName = "1.7.7"

        // 只打包真机常用的 ABI，去掉模拟器用的 x86/x86_64，APK 体积从 26MB 降到 12MB 左右
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/platform-rules"))

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") {
    dependsOn(syncPlatformRules)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")

    val camerax = "1.3.1"
    implementation("androidx.camera:camera-core:" + camerax)
    implementation("androidx.camera:camera-camera2:" + camerax)
    implementation("androidx.camera:camera-lifecycle:" + camerax)
    implementation("androidx.camera:camera-view:" + camerax)

    // bundled 版模型：离线可用，不需要从网络下载模型文件
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
