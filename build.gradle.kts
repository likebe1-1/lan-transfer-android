plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 并行开发时用 -PappBuildDir=build-xxx 给每个 agent 隔离构建目录，避免多构建并发写坏 app/build
allprojects {
    if (name == "app") {
        val overrideDir = providers.gradleProperty("appBuildDir").orNull
        if (!overrideDir.isNullOrBlank()) {
            layout.buildDirectory.set(layout.projectDirectory.dir(overrideDir))
        }
    }
}
