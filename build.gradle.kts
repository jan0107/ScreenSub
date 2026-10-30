// AGP 9 起内置 Kotlin（android.builtInKotlin 默认 true），
// 所以这里不再声明 org.jetbrains.kotlin.android 插件。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
