// =============================================================================
// 📄 app/build.gradle.kts
// 作用：App 模块的构建脚本 — 这是整个项目最核心的配置文件
//       定义了 App 的目标版本、依赖了哪些库、怎么打包
// =============================================================================

// -------- 插件（启用阶段）--------
plugins {
    id("com.android.application")              // Android 应用插件
    id("org.jetbrains.kotlin.android")         // Kotlin for Android
    id("org.jetbrains.kotlin.plugin.compose")   // Compose 编译器插件
}

// -------- Android 配置块 --------
android {
    // namespace：App 的唯一标识（类似 Java 包名），也是 R 资源的根路径
    namespace = "com.demo.kotlindemo"

    // compileSdk：编译时使用的 Android API 版本
    // 35 = Android 15，数字越大能用的新 API 越多
    compileSdk = 35

    // -------- 默认配置（所有构建变体共用）--------
    defaultConfig {
        // applicationId：安装到手机时的包名，Google Play 用它区分 App
        applicationId = "com.demo.kotlindemo"

        // minSdk：最低支持的 Android 版本
        // 26 = Android 8.0（覆盖了 95%+ 的设备）
        minSdk = 26

        // targetSdk：目标 API 版本，表示在这个版本上做过充分测试
        targetSdk = 35

        // versionCode：内部版本号（整数），每次上架必须递增
        versionCode = 1

        // versionName：展示给用户看的版本名
        versionName = "1.0.0"
    }

    // -------- 构建类型 --------
    buildTypes {
        release {
            // isMinifyEnabled = true：开启代码混淆 + 压缩
            // 会删掉未使用的代码，混淆类名/方法名，减小 APK 体积
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), // Android 默认混淆规则
                "proguard-rules.pro"                                     // 项目自定义混淆规则
            )
        }
        // debug 模式默认开启，不需要配置
    }

    // -------- Java / Kotlin 编译选项 --------
    compileOptions {
        // 源码兼容 Java 17（可以使用 Java 17 语法）
        sourceCompatibility = JavaVersion.VERSION_17
        // 编译目标也是 Java 17（生成的字节码版本）
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        // Kotlin 编译目标 JVM 版本，必须和 Java 版本一致
        jvmTarget = "17"
    }

    // -------- 构建特性 --------
    buildFeatures {
        // 启用 Jetpack Compose（声明式 UI 框架）
        compose = true
        // 注意：Kotlin 2.0+ 不需要再配 composeOptions + kotlinCompilerExtensionVersion
        // 因为 kotlin.plugin.compose 插件已经接管了编译器
    }
}

// -------- 依赖声明 --------
dependencies {
    // ═══════════════ Compose 核心 ═══════════════

    // Compose BOM（Bill of Materials）—
    // 只要依赖这一个，所有 Compose 库的版本就自动对齐，再也不用逐个写版本号了
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    // Compose UI 基础库（核心运行时、绘制、布局等）
    implementation("androidx.compose.ui:ui")
    // Compose UI 图形增强（Canvas、Shader 等高级绘制）
    implementation("androidx.compose.ui:ui-graphics")
    // Compose UI 预览支持（让 @Preview 注解生效）
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material3 设计系统（Material Design 第 3 代组件库）
    implementation("androidx.compose.material3:material3")
    // Material 图标扩展包（除了基础图标外更多的 Material Icons）
    implementation("androidx.compose.material:material-icons-extended")

    // ═══════════════ 集成库 ═══════════════

    // Activity + Compose 集成 —
    // 让 Activity 能用 setContent { } 加载 Compose UI
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation Compose — Compose 版本的页面导航框架
    // 不需要 XML，用函数调用就能跳转页面
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Lifecycle 运行时 + Compose 集成 —
    // 让 Compose 能感知生命周期状态（如自动取消协程）
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // ViewModel + Compose 集成 —
    // 用 viewModel() 函数在 Composable 里直接获取 ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ═══════════════ 协程 ═══════════════

    // Kotlin 协程 Android 支持 —
    // 提供主线程调度器 Dispatchers.Main，用于异步任务
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ═══════════════ 网络层（对接 ThingsBoard / 微服务端） ═══════════════

    // Retrofit2 — REST 网络请求框架
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    // Retrofit Gson 转换器（JSON 自动解析）
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    // OkHttp 日志拦截器（调试用）
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // 曲线图库（历史温度/湿度曲线页）
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // ═══════════════ 调试 ═══════════════

    // debugImplementation：只在 debug 构建时生效
    // UI 调试工具（布局边界检查、组件树查看）
    debugImplementation("androidx.compose.ui:ui-tooling")
    // 测试清单（调试模式下创建测试 Activity）
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
// 💡 implementation vs debugImplementation：
//    implementation → 所有构建模式都包含
//    debugImplementation → 仅 debug 版本包含（release 自动移除，省体积）
