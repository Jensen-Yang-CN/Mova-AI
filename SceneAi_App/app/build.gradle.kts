import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 本机公开服务地址保存在已忽略的 local.properties，普通更新构建也能沿用。
// 命令行参数／环境变量仍可覆盖；模型 Key 不属于 Android 构建配置。
val localBuildProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.mova.sceneai"
    // 与服务器上验证过的 GGUF 转换工具链分开锁定 Android native 构建版本。
    ndkVersion = "27.0.12077973"
    buildToolsVersion = "36.0.0"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.mova.sceneai"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 同时保留 arm64 真机和 x86_64 模拟器；不因新增 native 库让现有演示无法安装。
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DGGML_NATIVE=OFF")
            }
        }

        // 评审安装包可在构建时注入公网 Mova 后端地址；绝不把百炼 Key 写进 APK。
        // 未提供地址时保持空白，让用户明确知道云端尚未配置，而非误连开发电脑。
        val serviceUrl = providers.gradleProperty("movaServiceBaseUrl")
            .orElse(providers.environmentVariable("MOVA_SERVICE_BASE_URL"))
            .orElse(localBuildProperties.getProperty("mova.serviceBaseUrl", ""))
            .get().trim()
        require(serviceUrl.isEmpty() || serviceUrl.startsWith("https://")) {
            "movaServiceBaseUrl 必须是公网 HTTPS 地址"
        }
        val escapedServiceUrl = serviceUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DEFAULT_BASE_URL", "\"$escapedServiceUrl\"")
    }

    /**
     * 显式指定 debug 签名。
     *
     * AGP 默认会在 ~/.android/ 下自动生成调试密钥，但在受限环境（沙箱、
     * 无家目录写权限的 CI）里这一步会失败。这里改为使用仓库内置的一次性
     * 调试密钥（密钥库口令就是 Android 约定的 android，不具备任何保密性，
     * 只用于本地安装调试，release 请另行配置）。
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // TODO 上线前开启混淆，并为 kotlinx.serialization / Retrofit 补 keep 规则
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // ---------- Compose（版本由 BOM 统一） ----------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // ---------- 本地设置存储 ----------
    implementation(libs.androidx.datastore.preferences)

    // ---------- 网络 ----------
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // ---------- 图片加载 ----------
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
