plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.screensub.core.asr"
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

    // sherpa-onnx：语音识别 + Silero VAD
    api(libs.sherpa.onnx)
}
