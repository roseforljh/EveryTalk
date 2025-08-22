// app/build.gradle.kts

// 用于解决 org.jetbrains:annotations 版本冲突 (如果需要)
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
    exclude(group = "com.sun.activation", module = "javax.activation")
}


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Kotlin Serialization 插件，版本与项目 Kotlin 版本一致
    id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin.get()
    // KSP 插件，版本与项目 Kotlin 版本 (2.0.0) 匹配
    id("com.google.devtools.ksp") version "2.0.0-1.0.21"
}

android {
    namespace = "com.example.everytalk"
    compileSdk = 35 // 建议与 targetSdk 和 Compose BOM 推荐的 SDK 版本对齐

    defaultConfig {
        applicationId = "com.example.everytalk"
        minSdk = 27
        //noinspection OldTargetApi
        targetSdk = 35 // 通常与 compileSdk 一致
        versionCode = 5947
        versionName = "1.3.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            this.isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isProfileable = false // debug 构建也可以设为 profileable，方便测试
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
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
    packaging{
        resources{
            excludes += "/META-INF/{AL2.0,LGPL2.1}" // 排除 AL2.0 和 LGPL2.1 许可证文件
            pickFirsts += "META-INF/LICENSE-LGPL-3.txt"
            pickFirsts += "META-INF/DEPENDENCIES"
            pickFirsts+="META-INF/LICENSE-LGPL-2.1.txt"
            pickFirsts+="META-INF/LICENSE-W3C-TEST"
        }
    }


}


    dependencies {
        // Compose BOM
        implementation(platform("androidx.compose:compose-bom:2024.12.01")) // 使用更稳定的版本
        androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))

        // Compose UI - 让BOM管理版本
        implementation(libs.androidx.ui) // 或者 "androidx.compose.ui:ui"
        implementation(libs.androidx.ui.tooling.preview) // 或者 "androidx.compose.ui:ui-tooling-preview"
        implementation(libs.androidx.material3) // 恢复 BOM 管理

        implementation("androidx.compose.material:material") // 保留基础 Material 依赖
        // 使用 compose-markdown 库来渲染 Markdown
        implementation("com.github.jeziellago:compose-markdown:0.5.7")
        implementation("androidx.compose.material3:material3-window-size-class") // 添加 window size class
        implementation("androidx.compose.material:material-icons-core") // 这些通常也由BOM管理或有自己的稳定版本线
        implementation("androidx.compose.material:material-icons-extended")

        debugImplementation("androidx.compose.ui:ui-tooling") // BOM 会管理


        implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0") // 或使用 libs.androidx.lifecycle.viewmodel.compose
        implementation("androidx.activity:activity-compose:1.10.1")         // 或使用 libs.androidx.activity.compose

        // Core Android & Lifecycle (从你的 libs.versions.toml 获取，通常这些不由Compose BOM管理)
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.lifecycle.runtime.ktx)
        implementation("androidx.lifecycle:lifecycle-process:2.9.0") // 版本对齐 & 添加此行以使用 ProcessLifecycleOwner

        // Kotlinx Serialization
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

        // Coroutines
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

        // Ktor
        implementation("io.ktor:ktor-client-core:2.3.11")
        implementation("io.ktor:ktor-client-android:2.3.11")
        implementation("io.ktor:ktor-client-content-negotiation:2.3.11")
        implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
        implementation("io.ktor:ktor-client-logging:2.3.11")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

        // Testing
        testImplementation(libs.junit)
        androidTestImplementation(libs.androidx.junit)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.androidx.ui.test.junit4) // BOM 会管理版本
        debugImplementation(libs.androidx.ui.test.manifest)    // BOM 会管理版本

        implementation("androidx.navigation:navigation-compose:2.7.7")

        implementation(libs.androidx.profileinstaller)
        implementation ("org.slf4j:slf4j-nop:2.0.12")

        // commonmark 依赖已由 compose-markdown 库自动包含，移除重复依赖
        // implementation("org.commonmark:commonmark:0.24.0")
        // implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
        // implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
        // implementation("org.commonmark:commonmark-ext-autolink:0.24.0")

        implementation("org.jsoup:jsoup:1.17.2")

        implementation("io.coil-kt.coil3:coil-compose:3.2.0")
        implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

        implementation("com.google.code.gson:gson:2.10.1") // 添加 Gson 依赖
        
        // 数学公式渲染库 - 使用WebView + KaTeX
    // 数学公式渲染支持
     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    }

