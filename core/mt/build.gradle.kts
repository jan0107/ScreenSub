plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.screensub.core.mt"
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

    // 离线翻译（依赖 GMS，模型按语言对动态下载）
    implementation(libs.mlkit.translate)

    // 在线翻译引擎
    implementation(libs.okhttp)
}
