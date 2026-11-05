import java.util.Properties

/**
 * release 簽章：從專案根目錄的 keystore.properties（不進 repo）或環境變數讀取。
 * 兩者都沒有時 release 照樣能編，只是不簽章（app-release-unsigned.apk）。
 * keystore.properties 格式見 keystore.properties.example。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
val releaseStoreFile = signingValue("storeFile", "NEKOYAKU_KEYSTORE")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nekoyaku.ss"
    compileSdk = 35

    defaultConfig {
        // 獨立 applicationId
        applicationId = "com.nekoyaku.ss"
        minSdk = 26
        targetSdk = 34 // 35 在部分 e-ink ROM 上會讓 WebView 渲染子進程「process is bad」起不來
        versionCode = 19
        versionName = "0.2.4"
        vectorDrawables { useSupportLibrary = true }

        // WebView CDP 除錯（chrome://inspect）。公開版一律 false——開著等於讓 adb 端
        // 可完全操控登入中的網站 session。
        buildConfigField("boolean", "WEBVIEW_CDP_DEBUG", "false")
        // 關於頁的原始碼連結（fork 的人改成自己的 repo）
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/cinminori/NekoYakuSS\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "NEKOYAKU_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "NEKOYAKU_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "NEKOYAKU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 部分 e-ink ROM 上 debuggable app 的 WebView isolated 渲染進程會「process is bad」起不來，
            // 關掉 debuggable 讓 WebView 多進程正常運作。
            isDebuggable = false
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}
