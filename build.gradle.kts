// AGP 9 内置 Kotlin 编译：不得再声明 org.jetbrains.kotlin.android，
// 与内置实现冲突会直接编译失败。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}
