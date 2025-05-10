pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()        // 用于 AndroidX 和 Google 库
        mavenCentral()  // 用于大多数 Java/Kotlin 库
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://jitpack.io") } // 如果你确实需要 JitPack 上的库
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    }
}

rootProject.name = "EveyTalkAi" // 或者你的项目根名称
include(":app")
include(":benchmark")

// 添加下面这行来明确指定 :benchmark 模块的物理路径
project(":benchmark").projectDir = File(rootDir, "app/benchmark") // <--- 修改这里
// rootDir 指向 settings.gradle.kts 所在的目录
// 然后我们指定相对路径 "app/benchmark"