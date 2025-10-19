# ===== 基础配置 =====
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.Unsafe

# 保留行号信息,便于调试 crash
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保持注解
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ===== Kotlin Serialization (精简版) =====
# 只保留序列化必需的部分
-keepattributes InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# 只保留序列化器,不保留整个包
-keep,includedescriptorclasses class com.example.everytalk.data.DataClass.**$$serializer { *; }
-keep,includedescriptorclasses class com.example.everytalk.models.**$$serializer { *; }

-keepclassmembers class com.example.everytalk.data.DataClass.** {
    *** Companion;
}
-keepclassmembers class com.example.everytalk.models.** {
    *** Companion;
}

# 保留序列化注解字段
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    @kotlinx.serialization.SerialName <fields>;
}

# ===== 项目数据类 (仅保留序列化相关) =====
# 只保留字段名,允许混淆方法
-keepclassmembers class com.example.everytalk.data.DataClass.** {
    <fields>;
}
-keepclassmembers class com.example.everytalk.models.** {
    <fields>;
}

# 自定义序列化器 - 必须完整保留
-keep class com.example.everytalk.data.network.AnySerializer { *; }
-keep class com.example.everytalk.data.network.ApiMessageSerializer { *; }
-keep class com.example.everytalk.util.*Serializer { *; }

# ===== Ktor Client (精简版) =====
# 不完全保留,只保留必要的接口和类
-keep,allowobfuscation class io.ktor.client.HttpClient
-keep class io.ktor.client.engine.okhttp.OkHttpEngine { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpConfig { *; }

# 保留 Ktor 插件接口
-keep interface io.ktor.client.plugins.** { *; }
-keep class io.ktor.client.plugins.contentnegotiation.** { *; }
-keep class io.ktor.serialization.kotlinx.json.** { *; }

-dontwarn io.ktor.**

# ===== OkHttp (精简版) =====
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# 只保留必要的 OkHttp 类
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.Call { *; }

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ===== Coroutines (精简版) =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# 不完全保留,只保留核心类
-keep class kotlinx.coroutines.CoroutineScope { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.flow.Flow { *; }
-keep class kotlinx.coroutines.flow.FlowCollector { *; }

# ===== SLF4J (Ktor logging 依赖) =====
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }

# ===== 第三方库 (允许混淆) =====
# Coil - 允许混淆,只保留必要接口
-keep,allowobfuscation class coil3.ImageLoader
-dontwarn coil3.**

# JSoup - 允许混淆
-keep,allowobfuscation class org.jsoup.Jsoup
-dontwarn org.jsoup.**

# Compose - 允许系统自动处理
-dontwarn androidx.compose.**

# ===== 关键业务类 (仅保留公共 API) =====
# 不使用 { *; },允许内部方法被混淆
-keep,allowobfuscation class com.example.everytalk.statecontroller.ApiHandler {
    public <methods>;
}
-keep,allowobfuscation class com.example.everytalk.statecontroller.MessageSender {
    public <methods>;
}
-keep,allowobfuscation class com.example.everytalk.statecontroller.AppViewModel {
    public <methods>;
}

# ===== 优化配置 =====
# 启用所有安全的优化
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-repackageclasses ''

# ===== 移除调试代码 =====
# 移除 Log 调用以减小体积和提升性能
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# ===== 保持枚举 =====
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===== 保持 Parcelable =====
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 保留泛型签名
-keepattributes Signature
-keepattributes Exceptions
