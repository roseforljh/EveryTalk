
pluginManagement {
    repositories {
        // 优先使用阿里云镜像
        maven("https://maven.aliyun.com/repository/public")
        // 保留官方源作为备用，并维持 google() 的内容过滤规则
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
    // 推荐设置，强制所有依赖声明在 settings.gradle.kts 中
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {

        // 优先使用阿里云镜像
        maven("https://maven.aliyun.com/repository/public")
        // 保留官方源作为备用
        google()

        mavenCentral()
        // 注意：JCenter 已弃用，如果旧项目中有 jcenter()，建议移除或替换
        // jcenter()
    }
}

rootProject.name = "app1"
include(":app")