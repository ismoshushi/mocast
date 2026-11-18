plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Room 注解处理器：KSP，版本号前半段必须与 Kotlin 版本一致（2.0.21）。
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
}
