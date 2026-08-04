// =============================================================================
// 📄 settings.gradle.kts
// 作用：Gradle 项目设置文件 — 告诉 Gradle 项目叫什么名字、有哪些模块、
//       去哪里下载依赖
// =============================================================================

// pluginManagement：配置插件的下载来源
pluginManagement {
    repositories {
        google {
            content {
                // 只允许 Google 相关的内容使用 Google 仓库
                // 正则：com.android.* / com.google.* / androidx.*
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()           // Maven 中央仓库（最大的 Java/Kotlin 包仓库）
        gradlePluginPortal()     // Gradle 插件门户（存放官方插件）
    }
}

// dependencyResolutionManagement：管理项目里所有依赖的解析策略
dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS：禁止各个模块单独声明仓库，统一在这里管理
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()                // Google Maven 仓库（AndroidX、Compose 等）
        mavenCentral()          // Maven 中央仓库
        maven(url = "https://jitpack.io")   // JitPack 仓库（MPAndroidChart 等）
    }
}

// rootProject.name：项目的根名称，编译出来的产物会用到
rootProject.name = "KotlinDemo"

// include：声明项目包含哪些模块
// ":app" 表示 app 子模块（对应 app/ 文件夹）
include(":app")
