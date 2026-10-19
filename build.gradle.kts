// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 根项目构建目录同样外置，Browser 仓库内不出现 build/
layout.buildDirectory.set(layout.projectDirectory.dir("../Browser_Agent/build/root"))