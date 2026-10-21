plugins {
    // Kotlin compilation itself is built into AGP 9+ (no org.jetbrains.kotlin.android plugin needed).
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
}
