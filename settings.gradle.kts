pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx 通过 JitPack 分发（官方推荐方式之一）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ScreenSub"

include(":app")
include(":core:common")
include(":core:audio")
include(":core:asr")
include(":core:mt")
include(":core:pipeline")
include(":core:overlay")
include(":core:data")
