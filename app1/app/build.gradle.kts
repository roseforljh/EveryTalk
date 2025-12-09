// app/build.gradle.kts
import java.util.Properties
import java.io.FileInputStream

// Function to safely load properties from a file
fun loadProperties(project: Project): Properties {
    val properties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        properties.load(FileInputStream(localPropertiesFile))
    }
    return properties
}

val localProperties = loadProperties(project)

// Sanitize property strings before injecting into BuildConfig fields
fun sanitizeForBuildConfig(value: String?): String {
    if (value == null) return ""
    val trimmed = value.trim()
    var unquoted = if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }

    // 兼容处理：如果 URL 中包含反斜杠转义（如 https\://），将其还原为正常 URL
    // 这通常发生在从 local.properties 复制值到 GitHub Secrets 时未去转义的情况
    if (unquoted.contains("http") && unquoted.contains("\\:")) {
        unquoted = unquoted.replace("\\:", ":")
    }
    
    // Escape backslashes and quotes for Java string literal in BuildConfig.java
    return unquoted.replace("\\", "\\\\").replace("\"", "\\\"")
}

// 获取配置值：优先从 Gradle 属性读取（CI -P参数），其次从环境变量读取，最后从 local.properties 读取（本地开发）
fun getConfigValue(key: String, defaultValue: String = ""): String {
    // 1. 尝试从 Gradle Project Properties 读取 (CI -Pkey=value)
    if (project.hasProperty(key)) {
        val propValue = project.property(key)?.toString()?.trim()
        if (!propValue.isNullOrBlank()) {
            return sanitizeForBuildConfig(propValue)
        }
    }

    // 2. 尝试从系统环境变量读取
    val envValue = System.getenv(key)?.trim()
    if (!envValue.isNullOrBlank()) {
        return sanitizeForBuildConfig(envValue)
    }

    // 3. 回退到 local.properties
    return sanitizeForBuildConfig(localProperties.getProperty(key, defaultValue))
}

// 用于解决 org.jetbrains:annotations 版本冲突 (如果需要)
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
    exclude(group = "com.sun.activation", module = "javax.activation")
}


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Kotlin Serialization 插件
    id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin.get()
    // 使用 KSP2（Gradle 插件在顶层声明版本，这里只启用插件即可）
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.android.everytalk"
    compileSdk = 36

    lint {
        // 禁用 Release 构建的 Lint 检查，以绕过 Kotlin 2.1.0 与 Android Lint 的兼容性问题
        // 当 Android Gradle Plugin 更新并修复此问题后，可以移除此配置
        checkReleaseBuilds = false
    }
    // 建议与 targetSdk 和 Compose BOM 推荐的 SDK 版本对齐

    defaultConfig {
        applicationId = "com.android.everytalk"
        minSdk = 27
        //noinspection OldTargetApi
        targetSdk = 36 // 通常与 compileSdk 一致
        versionCode = 6000
        // 优先从环境变量获取版本号(CI环境)，否则使用默认值
        val baseVersionName = "1.7.5"
        val envVersionName = System.getenv("VERSION_NAME")
        versionName = if (!envVersionName.isNullOrBlank()) envVersionName else baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    // Signing configs for release build; values come from local.properties
    signingConfigs {
        create("release") {
            // 优先使用 ANDROID_KEYSTORE_PATH，其次才是 local.properties 的 storeFile
            val props = localProperties
            val envStoreFile = System.getenv("ANDROID_KEYSTORE_PATH")?.trim().orEmpty()
            val propsStoreFile = props.getProperty("storeFile")?.trim().orEmpty()
            val defaultPath = "../everytalk-release.jks"

            fun resolveCandidate(path: String?): File? {
                if (path.isNullOrBlank()) return null
                val candidate = file(path)
                return if (candidate.exists()) candidate else null
            }

            val resolvedStore = resolveCandidate(envStoreFile)
                ?: resolveCandidate(propsStoreFile)
                ?: resolveCandidate(defaultPath)

            if (resolvedStore != null) {
                storeFile = resolvedStore
                println("[signing] using keystore at ${resolvedStore.path}")
            } else {
                println("[signing] keystore not found via env/local/default path，release 签名会失败")
            }
            storePassword = props.getProperty("storePassword")
                ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
                ?: ""
            keyAlias = props.getProperty("keyAlias")
                ?: System.getenv("ANDROID_KEY_ALIAS")
                ?: ""
            keyPassword = props.getProperty("keyPassword")
                ?: System.getenv("ANDROID_KEY_PASSWORD")
                ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            
            // ⚠️ 后端代理配置已废弃，保留空字符串仅为兼容旧代码
            // 所有功能已迁移到直连模式，不再需要后端代理
            buildConfigField("String", "BACKEND_URLS", "\"\"")
            buildConfigField("String", "VOICE_BACKEND_URL", "\"\"")
            buildConfigField("boolean", "CONCURRENT_REQUEST_ENABLED", "false")

            // 注入直连模式 API 配置 (Release)
            // 优先从环境变量读取（CI 环境），其次从 local.properties 读取（本地开发）
            buildConfigField("String", "GOOGLE_API_KEY", "\"${getConfigValue("GOOGLE_API_KEY")}\"")
            buildConfigField("String", "GOOGLE_API_BASE_URL", "\"${getConfigValue("GOOGLE_API_BASE_URL")}\"")
            buildConfigField("String", "GOOGLE_CSE_ID", "\"${getConfigValue("GOOGLE_CSE_ID")}\"")
            buildConfigField("String", "DEFAULT_OPENAI_API_BASE_URL", "\"${getConfigValue("DEFAULT_OPENAI_API_BASE_URL")}\"")
            buildConfigField("String", "SILICONFLOW_API_KEY", "\"${getConfigValue("SILICONFLOW_API_KEY")}\"")
            buildConfigField("String", "SILICONFLOW_IMAGE_API_URL", "\"${getConfigValue("SILICONFLOW_IMAGE_API_URL")}\"")
            buildConfigField("String", "SILICONFLOW_DEFAULT_IMAGE_MODEL", "\"${getConfigValue("SILICONFLOW_DEFAULT_IMAGE_MODEL")}\"")
            buildConfigField("String", "DEFAULT_TEXT_API_KEY", "\"${getConfigValue("DEFAULT_TEXT_API_KEY")}\"")
            buildConfigField("String", "DEFAULT_TEXT_API_URL", "\"${getConfigValue("DEFAULT_TEXT_API_URL")}\"")
            buildConfigField("String", "DEFAULT_TEXT_MODELS", "\"${getConfigValue("DEFAULT_TEXT_MODELS")}\"")
            buildConfigField("String", "VITE_API_URLS", "\"${getConfigValue("VITE_API_URLS")}\"")
            buildConfigField("String", "QWEN_EDIT_API_URLS", "\"${getConfigValue("QWEN_EDIT_API_URLS")}\"")
            buildConfigField("String", "QWEN_EDIT_API_SECRET", "\"${getConfigValue("QWEN_EDIT_API_SECRET")}\"")
            buildConfigField("String", "SEEDREAM_API_URL", "\"${getConfigValue("SEEDREAM_API_URL")}\"")
            buildConfigField("String", "GOOGLE_SEARCH_API_KEY", "\"${getConfigValue("GOOGLE_SEARCH_API_KEY")}\"")
            buildConfigField("String", "ZHIPU_API_KEY", "\"${getConfigValue("ZHIPU_API_KEY")}\"")
        }
        debug {
            isProfileable = false // debug 构建也可以设为 profileable,方便测试
            
            // ⚠️ 后端代理配置已废弃，保留空字符串仅为兼容旧代码
            // 所有功能已迁移到直连模式，不再需要后端代理
            buildConfigField("String", "BACKEND_URLS", "\"\"")
            buildConfigField("String", "VOICE_BACKEND_URL", "\"\"")
            buildConfigField("boolean", "CONCURRENT_REQUEST_ENABLED", "false")

            // 注入直连模式 API 配置 (Debug)
            // 优先从环境变量读取（CI 环境），其次从 local.properties 读取（本地开发）
            buildConfigField("String", "GOOGLE_API_KEY", "\"${getConfigValue("GOOGLE_API_KEY")}\"")
            buildConfigField("String", "GOOGLE_API_BASE_URL", "\"${getConfigValue("GOOGLE_API_BASE_URL")}\"")
            buildConfigField("String", "GOOGLE_CSE_ID", "\"${getConfigValue("GOOGLE_CSE_ID")}\"")
            buildConfigField("String", "DEFAULT_OPENAI_API_BASE_URL", "\"${getConfigValue("DEFAULT_OPENAI_API_BASE_URL")}\"")
            buildConfigField("String", "SILICONFLOW_API_KEY", "\"${getConfigValue("SILICONFLOW_API_KEY")}\"")
            buildConfigField("String", "SILICONFLOW_IMAGE_API_URL", "\"${getConfigValue("SILICONFLOW_IMAGE_API_URL")}\"")
            buildConfigField("String", "SILICONFLOW_DEFAULT_IMAGE_MODEL", "\"${getConfigValue("SILICONFLOW_DEFAULT_IMAGE_MODEL")}\"")
            buildConfigField("String", "DEFAULT_TEXT_API_KEY", "\"${getConfigValue("DEFAULT_TEXT_API_KEY")}\"")
            buildConfigField("String", "DEFAULT_TEXT_API_URL", "\"${getConfigValue("DEFAULT_TEXT_API_URL")}\"")
            buildConfigField("String", "DEFAULT_TEXT_MODELS", "\"${getConfigValue("DEFAULT_TEXT_MODELS")}\"")
            buildConfigField("String", "VITE_API_URLS", "\"${getConfigValue("VITE_API_URLS")}\"")
            buildConfigField("String", "QWEN_EDIT_API_URLS", "\"${getConfigValue("QWEN_EDIT_API_URLS")}\"")
            buildConfigField("String", "QWEN_EDIT_API_SECRET", "\"${getConfigValue("QWEN_EDIT_API_SECRET")}\"")
            buildConfigField("String", "SEEDREAM_API_URL", "\"${getConfigValue("SEEDREAM_API_URL")}\"")
            buildConfigField("String", "GOOGLE_SEARCH_API_KEY", "\"${getConfigValue("GOOGLE_SEARCH_API_KEY")}\"")
            buildConfigField("String", "ZHIPU_API_KEY", "\"${getConfigValue("ZHIPU_API_KEY")}\"")

            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        create("release-profileable") {
            initWith(buildTypes.getByName("release"))
            isProfileable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    buildToolsVersion = "36.0.0"
    ndkVersion = "25.2.9519653"


    applicationVariants.all {
        if (buildType.name == "debug") {
            outputs.all {
                // Override version code for debug builds to avoid update prompts
                if (this is com.android.build.gradle.api.ApkVariantOutput) {
                    this.versionCodeOverride = 9999
                    this.versionNameOverride = "9999"
                }
            }
        }
    }
}


    dependencies {
        // ===== Compose Core =====
        implementation(platform("androidx.compose:compose-bom:2024.12.01"))
        androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))

        implementation(libs.androidx.ui)
        implementation(libs.androidx.ui.tooling.preview)
        implementation(libs.androidx.material3)

        implementation("androidx.compose.material:material") // 保留基础 Material 依赖
        
        implementation("androidx.compose.material3:material3-window-size-class")
        implementation("androidx.compose.material:material-icons-core")
        implementation("androidx.compose.material:material-icons-extended")

        debugImplementation("androidx.compose.ui:ui-tooling")

        // ===== Lifecycle & ViewModel =====
        implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
        implementation("androidx.activity:activity-compose:1.10.1")

        // ===== Core Android & Lifecycle =====
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.lifecycle.runtime.ktx)
        implementation("androidx.lifecycle:lifecycle-process:2.9.0")

        // ===== Kotlin Serialization =====
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

        // ===== Coroutines =====
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

        // ===== Ktor Client (网络请求) =====
        implementation("io.ktor:ktor-client-core:3.3.2")
        implementation("io.ktor:ktor-client-okhttp:3.3.2")
        implementation("io.ktor:ktor-client-content-negotiation:3.3.2")
        implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.2")
        implementation("io.ktor:ktor-client-logging:3.3.2")
        implementation("io.ktor:ktor-client-websockets:3.3.2")  // WebSocket 支持，用于阿里云实时语音识别

        // SLF4J - Ktor logging 的间接依赖,必须保留
        implementation("org.slf4j:slf4j-nop:2.0.17")

        // ===== Testing =====
        testImplementation(libs.junit)
        androidTestImplementation(libs.androidx.junit)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.androidx.ui.test.junit4)
        debugImplementation(libs.androidx.ui.test.manifest)

        // ===== Navigation =====
        implementation("androidx.navigation:navigation-compose:2.9.6")
        
        // ===== AppCompat & Material =====
        implementation("androidx.appcompat:appcompat:1.7.1")
        implementation("com.google.android.material:material:1.13.0")

        // ===== Profile Installer =====
        implementation(libs.androidx.profileinstaller)

        // ===== HTML 解析 - JSoup =====
        implementation("org.jsoup:jsoup:1.21.2")

        // ===== 图片加载 - Coil =====
        implementation("io.coil-kt.coil3:coil-compose:3.2.0")
        implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
        implementation("io.coil-kt.coil3:coil-video:3.2.0")

        // ===== 网络 - OkHttp =====
        implementation("com.squareup.okhttp3:okhttp:5.3.0")

        // ===== Markdown 渲染 - Markwon =====
        // 核心 + 表格
        implementation("io.noties.markwon:core:4.6.2")
        implementation("io.noties.markwon:ext-tables:4.6.2")
        // 数学公式支持 - Latex 扩展（使用 JLatexMath 离线渲染）
        implementation("io.noties.markwon:ext-latex:4.6.2")
        implementation("io.noties.markwon:inline-parser:4.6.2")
        // 图片支持 - 用于渲染 Markdown 中的图片
        implementation("io.noties.markwon:image:4.6.2")
        // 直接声明底层解码库，避免额外模块解析失败
        implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.29")
        implementation("com.caverock:androidsvg:1.4")

        // ===== PDF 处理 =====
        implementation("com.tom-roush:pdfbox-android:2.0.27.0")
        // ===== Room Database =====
        implementation(libs.room.runtime)
        implementation(libs.room.ktx)
        ksp(libs.room.compiler)
    }
