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
    // Define Compose BOM (Check for the latest compatible version)
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.05.00"))

    // Core Android & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx) // or specific version "2.7.0"
    implementation(libs.androidx.activity.compose)     // or specific version "1.9.0"
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0") // Okay to specify if not in BOM or need newer

    // Compose UI (Versions managed by BOM)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3") // M3 - version from BOM
    implementation("androidx.compose.material:material-icons-core") // M2 Icons Core
    implementation("androidx.compose.material:material-icons-extended") // M2 Extended Icons (or use M3 artifacts if preferred)
    // implementation("androidx.compose.material:material") // M2 Material - Keep ONLY if using M2 components AND M3
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation") // Animation - version from BOM

    // Navigation
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0") // Hilt Navigation

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50") // Keep your Hilt version
    // ksp("com.google.dagger:hilt-compiler:2.50") // Don't forget the KSP/Annotation Processor for Hilt

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") // Use one entry, latest stable

    // Coroutines (Align versions)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Ktor (Consider using stable 2.3.x unless you specifically need 3.0.0 features)
    implementation("io.ktor:ktor-client-core:2.3.11")
    implementation("io.ktor:ktor-client-android:2.3.11") // Use Android engine
    implementation("io.ktor:ktor-client-content-negotiation:2.3.11")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
    implementation("io.ktor:ktor-client-logging:2.3.11")


    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4") // From BOM
    debugImplementation("androidx.compose.ui:ui-tooling") // From BOM
    debugImplementation("androidx.compose.ui:ui-test-manifest") // From BOM

    implementation ("androidx.compose.foundation:foundation:1.5.4")
}