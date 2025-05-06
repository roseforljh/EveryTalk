// app/build.gradle.kts

// 用于解决 org.jetbrains:annotations 版本冲突 (如果需要)
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Kotlin Serialization 插件，版本与项目 Kotlin 版本一致
    id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin.get()
    // KSP 插件，版本与项目 Kotlin 版本 (2.0.0) 匹配
    id("com.google.devtools.ksp") version "2.0.0-1.0.21"
    // 如果你使用 Hilt，请取消注释并确保版本正确
    // id("com.google.dagger.hilt.android") version "2.50" // 示例 Hilt 插件版本
}

android {
    namespace = "com.example.app1" // 确认这是你的包名
    compileSdk = 35 // 建议与 targetSdk 和 Compose BOM 推荐的 SDK 版本对齐

    defaultConfig {
        applicationId = "com.example.app1" // 确认这是你的 applicationId
        minSdk = 27
        //noinspection OldTargetApi
        targetSdk = 35 // 通常与 compileSdk 一致
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // 生产环境通常建议开启
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    }
    packagingOptions { // packaging 重命名为 packagingOptions
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

val markwonVersion = "4.6.2"
val prism4jVersion = "2.0.0"

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.05.00")) // 使用你指定的版本
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.05.00"))

    // Compose UI
    implementation(libs.androidx.ui) // 假设 libs.versions.toml 中有这些别名，否则直接写字符串
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation") // 这个 BOM 应该已经包含了，可以考虑移除显式声明
    implementation("androidx.compose.animation:animation")

    // Core Android & Lifecycle (从你的 libs.versions.toml 获取)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Hilt (如果使用，请取消注释并确保 ksp 依赖也配置了)
    // implementation("com.google.dagger:hilt-android:2.50")
    // ksp("com.google.dagger:hilt-compiler:2.50")
    // implementation("androidx.hilt:hilt-navigation-compose:1.2.0")


    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3") // 建议考虑升级到 1.8.x
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Ktor
    implementation("io.ktor:ktor-client-core:2.3.11")
    implementation("io.ktor:ktor-client-android:2.3.11")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.11")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
    implementation("io.ktor:ktor-client-logging:2.3.11")







    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4) // BOM 会管理版本
    debugImplementation(libs.androidx.ui.tooling)          // BOM 会管理版本
    debugImplementation(libs.androidx.ui.test.manifest)    // BOM 会管理版本

    // 重复的 foundation 依赖，BOM 会管理，可以移除
    // implementation ("androidx.compose.foundation:foundation:1.5.4")
}