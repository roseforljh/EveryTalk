plugins {
    alias(libs.plugins.ksp) apply false

    // Android Gradle Plugin
    alias(libs.plugins.android.application) apply false
    id("com.android.library") version "9.2.1" apply false
}
