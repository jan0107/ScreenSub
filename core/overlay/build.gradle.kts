plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.screensub.core.overlay"
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
    api(project(":core:common"))
    // 只为了拿 SubtitleCue 数据结构；pipeline 不反向依赖 overlay，无环
    api(project(":core:pipeline"))
}
