import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// Release 签名信息来自 local.properties（不入库；keystore 丢失 = 永久无法更新，务必异地备份）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.looka.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.looka.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.7.2"
    }

    signingConfigs {
        create("release") {
            val storeName = localProps.getProperty("LOOKA_STORE_FILE") ?: ""
            if (storeName.isNotBlank()) {
                storeFile = file(storeName)
                storePassword = localProps.getProperty("LOOKA_STORE_PASSWORD")
                keyAlias = localProps.getProperty("LOOKA_KEY_ALIAS")
                keyPassword = localProps.getProperty("LOOKA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        // 调试包换独立包名，与正式包并存 —— 否则 debug(Android Debug 证书) 覆盖
        // release(Looka 证书) 会报「签名不一致」，只能卸载重装、丢本地数据。
        // FileProvider authority 用的是 ${applicationId}，会自动跟着变，无需另改。
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // AI 回复解析是纯逻辑且出过线上事故（裸 JSON 泄露给用户），用 JVM 单测锁回归。
    // org.json 在单测里是会抛异常的桩，必须换成真实现。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
