// app/build.gradle.kts
import java.util.Properties
import java.io.FileInputStream
import java.io.File
import java.security.MessageDigest

// Function to safely load properties from a file
fun loadProperties(project: Project): Properties {
    val properties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        FileInputStream(localPropertiesFile).use { input ->
            properties.load(input)
        }
    }
    return properties
}

val localProperties = loadProperties(project)

val stableBuildDir = File(
    System.getProperty("user.home"),
    ".everytalk-gradle-build/${rootProject.name}/${project.name}"
)
layout.buildDirectory.set(stableBuildDir)

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
    alias(libs.plugins.kotlin.compose)
    // Kotlin Serialization 插件
    id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin.get()
    // 使用 KSP2（Gradle 插件在顶层声明版本，这里只启用插件即可）
    alias(libs.plugins.ksp)
    // Hilt 暂不启用，项目统一使用 Koin
    // alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.android.everytalk"
    compileSdk = 37

    lint {
        checkReleaseBuilds = true
    }
    // 建议与 targetSdk 和 Compose BOM 推荐的 SDK 版本对齐

    defaultConfig {
        applicationId = "com.android.everytalk"
        minSdk = 27
        //noinspection OldTargetApi
        targetSdk = 37 // 通常与 compileSdk 一致
        versionCode = 6000
        // 优先从环境变量获取版本号(CI环境)，否则使用默认值
        val baseVersionName = "1.20.0"
        val envVersionName = System.getenv("VERSION_NAME")
        versionName = if (!envVersionName.isNullOrBlank()) envVersionName else baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // 保留手机 ARM 架构，并提供 ChromeOS 所需的 x86_64 ABI。
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
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
            
            // 注入直连模式 API 配置 (Release)
            // 优先从环境变量读取（CI 环境），其次从 local.properties 读取（本地开发）
            buildConfigField("String", "GOOGLE_API_KEY", "\"${getConfigValue("GOOGLE_API_KEY")}\"")
            buildConfigField("String", "GOOGLE_API_BASE_URL", "\"${getConfigValue("GOOGLE_API_BASE_URL")}\"")
            buildConfigField("String", "GOOGLE_CSE_ID", "\"${getConfigValue("GOOGLE_CSE_ID")}\"")
            buildConfigField("String", "DEFAULT_OPENAI_API_BASE_URL", "\"${getConfigValue("DEFAULT_OPENAI_API_BASE_URL")}\"")
            buildConfigField("String", "SILICONFLOW_API_KEY", "\"${getConfigValue("SILICONFLOW_API_KEY")}\"")
            buildConfigField("String", "SILICONFLOW_IMAGE_API_URL", "\"${getConfigValue("SILICONFLOW_IMAGE_API_URL")}\"")
            buildConfigField("String", "SILICONFLOW_DEFAULT_IMAGE_MODEL", "\"${getConfigValue("SILICONFLOW_DEFAULT_IMAGE_MODEL")}\"")
            buildConfigField("String", "VITE_API_URLS", "\"${getConfigValue("VITE_API_URLS")}\"")
            buildConfigField("String", "QWEN_EDIT_API_URLS", "\"${getConfigValue("QWEN_EDIT_API_URLS")}\"")
            buildConfigField("String", "QWEN_EDIT_API_SECRET", "\"${getConfigValue("QWEN_EDIT_API_SECRET")}\"")
            buildConfigField("String", "SEEDREAM_API_URL", "\"${getConfigValue("SEEDREAM_API_URL")}\"")
            buildConfigField("String", "GOOGLE_SEARCH_API_KEY", "\"${getConfigValue("GOOGLE_SEARCH_API_KEY")}\"")
            buildConfigField("String", "ZHIPU_API_KEY", "\"${getConfigValue("ZHIPU_API_KEY")}\"")
            buildConfigField("String", "JINA_READER_BASE_URL", "\"${getConfigValue("JINA_READER_BASE_URL")}\"")
            buildConfigField("String", "JINA_SEARCH_BASE_URL", "\"${getConfigValue("JINA_SEARCH_BASE_URL")}\"")
        }
        debug {
            isProfileable = false // debug 构建也可以设为 profileable,方便测试
            
            // 注入直连模式 API 配置 (Debug)
            // 优先从环境变量读取（CI 环境），其次从 local.properties 读取（本地开发）
            buildConfigField("String", "GOOGLE_API_KEY", "\"${getConfigValue("GOOGLE_API_KEY")}\"")
            buildConfigField("String", "GOOGLE_API_BASE_URL", "\"${getConfigValue("GOOGLE_API_BASE_URL")}\"")
            buildConfigField("String", "GOOGLE_CSE_ID", "\"${getConfigValue("GOOGLE_CSE_ID")}\"")
            buildConfigField("String", "DEFAULT_OPENAI_API_BASE_URL", "\"${getConfigValue("DEFAULT_OPENAI_API_BASE_URL")}\"")
            buildConfigField("String", "SILICONFLOW_API_KEY", "\"${getConfigValue("SILICONFLOW_API_KEY")}\"")
            buildConfigField("String", "SILICONFLOW_IMAGE_API_URL", "\"${getConfigValue("SILICONFLOW_IMAGE_API_URL")}\"")
            buildConfigField("String", "SILICONFLOW_DEFAULT_IMAGE_MODEL", "\"${getConfigValue("SILICONFLOW_DEFAULT_IMAGE_MODEL")}\"")
            buildConfigField("String", "VITE_API_URLS", "\"${getConfigValue("VITE_API_URLS")}\"")
            buildConfigField("String", "QWEN_EDIT_API_URLS", "\"${getConfigValue("QWEN_EDIT_API_URLS")}\"")
            buildConfigField("String", "QWEN_EDIT_API_SECRET", "\"${getConfigValue("QWEN_EDIT_API_SECRET")}\"")
            buildConfigField("String", "SEEDREAM_API_URL", "\"${getConfigValue("SEEDREAM_API_URL")}\"")
            buildConfigField("String", "GOOGLE_SEARCH_API_KEY", "\"${getConfigValue("GOOGLE_SEARCH_API_KEY")}\"")
            buildConfigField("String", "ZHIPU_API_KEY", "\"${getConfigValue("ZHIPU_API_KEY")}\"")
            buildConfigField("String", "JINA_READER_BASE_URL", "\"${getConfigValue("JINA_READER_BASE_URL")}\"")
            buildConfigField("String", "JINA_SEARCH_BASE_URL", "\"${getConfigValue("JINA_SEARCH_BASE_URL")}\"")

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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    ndkVersion = "29.0.14206865"
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                if (System.getenv("CI") != "true") {
                    test.systemProperty(
                        "robolectric.dependency.repo.url",
                        "https://maven.aliyun.com/repository/public",
                    )
                }
            }
        }
    }

}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(9999)
            output.versionName.set("9999")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}


    dependencies {
        // ===== Compose Core =====
        implementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(platform(libs.androidx.compose.bom))

        implementation(libs.androidx.ui)
        implementation(libs.androidx.ui.tooling.preview)
        implementation(libs.androidx.material3)

        implementation(libs.androidx.compose.material) // 保留基础 Material 依赖
        
        implementation(libs.androidx.compose.material3.window)
        implementation(libs.androidx.compose.material.icons.core)
        implementation(libs.androidx.compose.material.icons.extended)

        debugImplementation(libs.androidx.ui.tooling)

        // ===== Lifecycle & ViewModel =====
        implementation(libs.androidx.lifecycle.viewmodel.compose)
        implementation(libs.androidx.activity.compose)

        // ===== Core Android & Lifecycle =====
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.lifecycle.runtime.ktx)
        implementation(libs.androidx.lifecycle.process)

        // ===== Kotlin Serialization =====
        implementation(libs.kotlinx.serialization.json)

        // ===== Coroutines =====
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.coroutines.android)

        // ===== Ktor Client (网络请求) =====
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.okhttp)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
        implementation(libs.ktor.client.logging)
        implementation(libs.ktor.client.websockets)  // WebSocket 支持，用于阿里云实时语音识别

        // SLF4J - Ktor logging 的间接依赖,必须保留
        implementation(libs.slf4j.nop)

        // ===== MCP (Model Context Protocol) SDK =====
        implementation(libs.mcp.kotlin.sdk)

        // ===== Testing =====
        testImplementation(libs.junit)
        testImplementation(libs.kotlinx.coroutines.test)
        testImplementation(libs.ktor.client.mock)
        testImplementation(libs.mockk)
        testImplementation(libs.turbine)
        testImplementation(libs.robolectric)
        testImplementation(libs.room.testing)
        testImplementation(libs.androidx.ui.test.junit4)
        androidTestImplementation(libs.androidx.junit)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.androidx.ui.test.junit4)
        debugImplementation(libs.androidx.ui.test.manifest)

        // ===== Navigation =====
        implementation(libs.navigation.compose)
        
        // ===== AppCompat & Material =====
        implementation(libs.appcompat)
        implementation(libs.google.material)

        // ===== Profile Installer =====
        implementation(libs.androidx.profileinstaller)


        // ===== 图片加载 - Coil =====
        implementation(libs.coil.compose)
        implementation(libs.coil.network.okhttp)
        implementation(libs.coil.video)
        implementation(libs.coil.svg)

        // ===== 本地 MathJax WebView 运行时 =====
        implementation(libs.androidx.webkit)

        // ===== 网络 - OkHttp =====
        implementation(libs.okhttp)

        // ===== Markdown AST Parser =====
        implementation(libs.intellij.markdown)

        // ===== Markdown 渲染 =====
        implementation(libs.mikepenz.markdown.core)
        implementation(libs.mikepenz.markdown.m3)
        implementation(libs.mikepenz.markdown.coil3)

        // ===== PDF 处理 =====
        implementation(libs.pdfbox.android)
        // ===== Room Database =====
        implementation(libs.room.runtime)
        implementation(libs.room.ktx)
        ksp(libs.room.compiler)

        // ===== Hilt Dependency Injection =====
        // 暂不启用，项目统一使用 Koin
        // implementation(libs.hilt.android)
        // ksp(libs.hilt.compiler)
        // implementation(libs.hilt.navigation.compose)
        
        // ===== Koin Dependency Injection =====
        implementation(libs.koin.android)
        implementation(libs.koin.androidx.compose)
        testImplementation(libs.koin.test)
    }

val verifyMathJaxAssets = tasks.register("verifyMathJaxAssets") {
    group = "verification"
    description = "校验固定版本的 MathJax 本地资产是否完整且未被篡改"

    val assetsDirectory = layout.projectDirectory.dir("src/main/assets/mathjax")
    val dynamicFontDirectory = assetsDirectory.dir("font/svg/dynamic")
    val requiredFiles = listOf("index.html", "tex-svg.js", "LICENSE", "VERSION.json")
        .map(assetsDirectory::file)
    inputs.files(requiredFiles)
    inputs.dir(dynamicFontDirectory)

    doLast {
        requiredFiles.forEach { asset ->
            val file = asset.asFile
            check(file.isFile && file.length() > 0L) {
                "缺少 MathJax 资产或文件为空：${file.absolutePath}"
            }
        }

        val expectedHashes = mapOf(
            "index.html" to "a6b136d600bbe1c660433df17a3e41afadecb01b41f386e523cf0468fde2af40",
            "tex-svg.js" to "23c036deccc0f2374834a47e4032e452419f3ac027bf17e17c104e2746b19f4c",
            "LICENSE" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
        )
        expectedHashes.forEach { (name, expectedHash) ->
            val file = assetsDirectory.file(name).asFile
            val normalizedBytes = file.readText(Charsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .toByteArray(Charsets.UTF_8)
            val actualHash = MessageDigest.getInstance("SHA-256")
                .digest(normalizedBytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            check(actualHash == expectedHash) {
                "MathJax 资产校验失败：$name，期望 $expectedHash，实际 $actualHash"
            }
        }

        val dynamicFontFiles = dynamicFontDirectory.asFile
            .listFiles { file -> file.isFile && file.extension == "js" }
            .orEmpty()
            .sortedBy(File::getName)
        check(dynamicFontFiles.size == 40) {
            "MathJax NewCM SVG 动态字体文件数量错误：期望 40，实际 ${dynamicFontFiles.size}"
        }
        val dynamicFontManifest = buildString {
            dynamicFontFiles.forEach { file ->
                val fileHash = MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                append(file.name).append('=').append(fileHash).append('\n')
            }
        }
        val dynamicFontManifestHash = MessageDigest.getInstance("SHA-256")
            .digest(dynamicFontManifest.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        check(dynamicFontManifestHash == "a4100bbac386b90c364ac74fb9d923706eb22b3c929c4325dcdf46216051275f") {
            "MathJax NewCM SVG 动态字体清单校验失败：$dynamicFontManifestHash"
        }

        val versionManifest = assetsDirectory.file("VERSION.json").asFile
            .readText(Charsets.UTF_8)
        check(versionManifest.contains("\"version\": \"4.1.3\"")) {
            "MathJax VERSION.json 未锁定到 4.1.3"
        }
        check(versionManifest.contains("@mathjax/mathjax-newcm-font")) {
            "MathJax VERSION.json 缺少 NewCM 字体包来源"
        }
        check(versionManifest.contains("12b8c2ab9827b146e579bb0f01e101faefc4c8081fa47d915c3e142957a82bf7")) {
            "MathJax VERSION.json 缺少固定渲染配置哈希"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyMathJaxAssets)
}
