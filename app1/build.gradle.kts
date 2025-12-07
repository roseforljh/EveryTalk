plugins {
    // 1) Kotlin 与 KSP 版本完全一致
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false

    // 2) Android Gradle Plugin
    alias(libs.plugins.android.application) apply false
    id("com.android.library") version "8.7.3" apply false
}