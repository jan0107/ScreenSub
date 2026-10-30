plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.screensub.core.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    api(libs.kotlinx.coroutines.android)
}
