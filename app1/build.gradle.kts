plugins {
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false

    // Android Gradle Plugin
    alias(libs.plugins.android.application) apply false
    id("com.android.library") version "9.4.1" apply false
}
