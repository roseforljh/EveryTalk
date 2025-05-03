import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" // 使用你的 Kotlin 版本
    id("com.google.devtools.ksp") version "2.1.20-2.0.1"
}

android {
    namespace = "com.example.app1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.app1"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2") // 使用最新稳定版
    implementation("com.google.dagger:hilt-android:2.50")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.compose.material3:material3-window-size-class:1.3.2")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.4.0-alpha13")
    implementation("androidx.compose.material:material") // <--- 确保有这一行 (M2 核心库)
    implementation("androidx.compose.material:material-icons-core") // <--- M2 图标核心 (通常需要)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") // 检查最新版本
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0") // Use the latest version
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0") // Optional: for logging network requests
    implementation("com.squareup.okhttp3:okhttp-sse:4.11.0")
    implementation("com.google.code.gson:gson:2.10.1") // 你可以使用最新的稳定版本，2.10.1 是一个常用版本
    implementation ("com.squareup.retrofit2:retrofit:2.9.0") // 使用你项目实际的版本
    // Retrofit Converter for Gson (解决 Unresolved reference 'converter'/'GsonConverterFactory')
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0") // 版本应与上面的 retrofit core 匹配
    implementation("io.ktor:ktor-client-core:3.0.0")
    implementation("io.ktor:ktor-client-cio:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
    implementation("io.ktor:ktor-client-logging:3.0.0")
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("io.ktor:ktor-client-logging:3.0.0")
    implementation("io.ktor:ktor-client-core:3.0.0")
    // Android 引擎
    implementation("io.ktor:ktor-client-android:3.0.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout:1.6.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0") // Use the latest version
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0") // Often needed
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.33.0") // <<< 添加这一行
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation ("androidx.compose.ui:ui:1.6.0")
    implementation ("androidx.compose.material:material-icons-extended:1.6.0")
    implementation ("androidx.compose.animation:animation:1.5.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}