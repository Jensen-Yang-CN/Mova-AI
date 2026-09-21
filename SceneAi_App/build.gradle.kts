// Mova-AI · 顶层构建脚本
// 所有插件在此统一声明版本（apply false），由 :app 模块应用，保证多模块版本一致。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
