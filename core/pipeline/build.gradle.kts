plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.screensub.core.pipeline"
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
    api(project(":core:audio"))
    api(project(":core:asr"))
    api(project(":core:mt"))
}
