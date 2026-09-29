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
    }
}

rootProject.name = "retriever-android"

// 传输层本体：依赖只有 kotlin-stdlib（无 AndroidX、无 coroutines、无 okhttp；ADR 0003 决定 13）。
include(":retriever")
// Timber 适配器：`RetrieverTree`（依赖 :retriever + timber 5.0.1）。
include(":retriever-timber")
// 示例 app：按钮 + intent extra 跑真机验收场景（与 iOS 示例 ScenarioRunner 逐字对应）。
include(":example")
