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

// 用于解决 org.jetbrains:annotations 版本冲突 (如果需要)
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
    exclude(group = "com.sun.activation", module = "javax.activation")
}


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Kotlin Serialization 插件,版本与项目 Kotlin 版本一致
    id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin.get()
    // KSP 插件,版本与项目 Kotlin 版本 (2.0.0) 匹配
    id("com.google.devtools.ksp") version "2.0.0-1.0.21"
}

android {
    namespace = "com.example.everytalk"
    compileSdk = 36
    // 建议与 targetSdk 和 Compose BOM 推荐的 SDK 版本对齐

    defaultConfig {
        applicationId = "com.example.everytalk"
        minSdk = 27
        //noinspection OldTargetApi
        targetSdk = 35 // 通常与 compileSdk 一致
        versionCode = 5949
        versionName = "1.5.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            postprocessing {
                isRemoveUnusedCode = true
                isObfuscate = true
                isOptimizeCode = true
                proguardFiles.clear()
                proguardFile("proguard-rules.pro")
            }
            signingConfig = signingConfigs.getByName("debug")
            // Inject backend configuration for Release
            buildConfigField("String", "BACKEND_URLS", "\"${localProperties.getProperty("BACKEND_URLS_RELEASE", "")}\"")
            buildConfigField("boolean", "CONCURRENT_REQUEST_ENABLED", "false")
        }
        debug {
            isProfileable = false // debug 构建也可以设为 profileable,方便测试
            // Inject backend configuration for Debug
            buildConfigField("String", "BACKEND_URLS", "\"${localProperties.getProperty("BACKEND_URLS_DEBUG", "")}\"")
            buildConfigField("boolean", "CONCURRENT_REQUEST_ENABLED", "false")
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
    kotlinOptions {
        jvmTarget = "17"
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


}


    dependencies {
        // ===== Compose Core =====
        implementation(platform("androidx.compose:compose-bom:2024.12.01"))
        androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))

        implementation(libs.androidx.ui)
        implementation(libs.androidx.ui.tooling.preview)
        implementation(libs.androidx.material3)

        implementation("androidx.compose.material:material") // 保留基础 Material 依赖
        
        // Markdown 渲染库（JitPack）
        implementation("com.github.jeziellago:compose-markdown:0.5.7")
        
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
        implementation("io.ktor:ktor-client-core:2.3.11")
        implementation("io.ktor:ktor-client-okhttp:2.3.11")
        implementation("io.ktor:ktor-client-content-negotiation:2.3.11")
        implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
        implementation("io.ktor:ktor-client-logging:2.3.11")

        // SLF4J - Ktor logging 的间接依赖,必须保留
        implementation("org.slf4j:slf4j-nop:2.0.12")

        // ===== Testing =====
        testImplementation(libs.junit)
        androidTestImplementation(libs.androidx.junit)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.androidx.ui.test.junit4)
        debugImplementation(libs.androidx.ui.test.manifest)

        // ===== Navigation =====
        implementation("androidx.navigation:navigation-compose:2.7.7")
        
        // ===== AppCompat & Material =====
        implementation("androidx.appcompat:appcompat:1.7.0")
        implementation("com.google.android.material:material:1.12.0")

        // ===== Profile Installer =====
        implementation(libs.androidx.profileinstaller)

        // ===== HTML 解析 - JSoup =====
        implementation("org.jsoup:jsoup:1.17.2")

        // ===== 图片加载 - Coil =====
        implementation("io.coil-kt.coil3:coil-compose:3.2.0")
        implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
        implementation("io.coil-kt.coil3:coil-video:3.2.0")

        // ===== 网络 - OkHttp =====
        implementation("com.squareup.okhttp3:okhttp:4.12.0")

    }
