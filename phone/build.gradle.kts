plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace  = "com.anpaiss.notifybridge.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anpaiss.notifybridge.phone"
        minSdk        = 31   // BLUETOOTH_ADVERTISE / CONNECT runtime permissions
        targetSdk     = 34
        versionCode   = 1
        versionName   = "0.1"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}
