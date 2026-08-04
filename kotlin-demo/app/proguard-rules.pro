# =============================================================================
# 📄 proguard-rules.pro
# 作用：ProGuard / R8 混淆规则 — release 构建时用于保护代码、减小体积
#       如果某些代码被混淆后出问题，需要在这里加 keep 规则
# =============================================================================

# 保持所有 @Composable 注解的函数不被混淆
# 因为 Compose 编译器插件会生成额外代码，混淆会导致运行时崩溃
-keep class * extends androidx.compose.runtime.Composable { *; }

# 忽略 Compose 相关的警告（有些内部类不需要暴露）
-dontwarn androidx.compose.**
