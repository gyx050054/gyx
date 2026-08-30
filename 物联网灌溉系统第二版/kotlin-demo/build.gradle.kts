// =============================================================================
// 📄 build.gradle.kts（项目根目录）
// 作用：项目最顶层的构建脚本 — 声明所有子模块共享的插件和版本号
//       这里只声明插件"有哪些"，不"启用"它们
// =============================================================================

// plugins：声明项目需要哪些 Gradle 插件
plugins {
    // Android 应用插件 — 负责把 Kotlin/Java 编译成 APK
    id("com.android.application") version "8.5.2" apply false

    // Kotlin Android 插件 — 让 Android 项目支持 Kotlin 语言
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false

    // Compose 编译器插件 — Kotlin 2.0+ 的新方式，替代旧版 composeOptions
    // 负责把 @Composable 函数编译成高效的 Android 代码
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
// ⚠️ apply = false 表示"先声明，但不启用"
// 具体在 app/build.gradle.kts 里再 apply = true
