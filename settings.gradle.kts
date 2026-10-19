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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral {
            content {
                // 保持主源仅服务常规依赖；Mozilla 组专用官方源
                excludeGroupByRegex("org\\.mozilla.*")
            }
        }
        // Mozilla 官方发布源：仅用于 GeckoView 相关组，不解析其它依赖
        maven("https://maven.mozilla.org/maven2") {
            content {
                includeGroupByRegex("org\\.mozilla.*")
            }
        }
    }
}

rootProject.name = "Browser for Wear OS"
include(":app")
 