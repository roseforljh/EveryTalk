package com.android.everytalk.data.computer

import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Provider
import java.security.Security

class ComputerSshSecurityProviderTest {
    @Test
    fun `创建SSH客户端前替换Android同名精简BC`() = runBlocking {
        val providerName = BouncyCastleProvider.PROVIDER_NAME
        val originalProvider = Security.getProvider(providerName)
        val originalPosition = Security.getProviders().indexOfFirst { it.name == providerName } + 1

        try {
            Security.removeProvider(providerName)
            Security.addProvider(EmptyBouncyCastleProvider())

            // 连接本机关闭端口，只触发真实 SSHClient 创建流程，不依赖外部服务器。
            runCatching {
                ComputerSshClient(
                    connectTimeoutMillis = 100,
                    readTimeoutMillis = 100,
                ).probeHostKey("127.0.0.1", 1)
            }

            assertTrue(
                "同名精简 BC 应在 SSHClient 创建前被完整版替换",
                Security.getProvider(providerName) is BouncyCastleProvider,
            )
            assertTrue(
                "完整版 BC 必须能够解析服务器的 Ed25519 Host Key",
                KeyFactory.getInstance("Ed25519", providerName).provider is BouncyCastleProvider,
            )
            assertTrue(
                "替换系统 BC 后必须继续提供 Android HTTPS 依赖的 BKS",
                KeyStore.getInstance("BKS", providerName).provider is BouncyCastleProvider,
            )
        } finally {
            Security.removeProvider(providerName)
            if (originalProvider != null) {
                Security.insertProviderAt(originalProvider, originalPosition)
            }
        }
    }

    @Test
    fun `Release保留BC动态加载的SSH算法实现`() {
        val rules = findAppFile("proguard-rules.pro").readText(Charsets.UTF_8)
        val requiredRules = listOf(
            "-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }",
            "-keep class org.bouncycastle.jcajce.provider.digest.** { *; }",
            "-keep class org.bouncycastle.jcajce.provider.symmetric.** { *; }",
            "-keep class org.bouncycastle.jcajce.provider.asymmetric.** { *; }",
            "-keep class org.bouncycastle.jcajce.provider.keystore.** { *; }",
            "-keep class org.bouncycastle.jcajce.provider.drbg.** { *; }",
        )

        requiredRules.forEach { rule ->
            assertTrue("Release 缺少 BC 动态算法保留规则：$rule", rules.contains(rule))
        }
    }

    @Test
    fun `替换系统BC前先验证网络信任库能力`() {
        val source = findAppFile(
            "src/main/java/com/android/everytalk/data/computer/ComputerSshClient.kt"
        ).readText(Charsets.UTF_8)
        val bundledProviderAt = source.indexOf("val bundledProvider = BouncyCastleProvider()")
        val bksValidationAt = source.indexOf("KeyStore.getInstance(\"BKS\", bundledProvider)")
        val removeSystemProviderAt = source.indexOf("Security.removeProvider(providerName)")

        assertTrue("必须创建 App 自带的完整 BC", bundledProviderAt >= 0)
        assertTrue(
            "必须先确认 BKS 可用，再替换 Android 系统 BC",
            bksValidationAt in bundledProviderAt until removeSystemProviderAt,
        )
    }

    /** 从 Gradle、IDE 和仓库根目录三种测试工作目录中定位 App 文件。 */
    private fun findAppFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("app1/app/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) { "找不到 $relativePath" }
    }

    /** 模拟 Android 系统内置、缺少 SSHJ 所需算法的精简 BC。 */
    @Suppress("DEPRECATION")
    private class EmptyBouncyCastleProvider : Provider(
        BouncyCastleProvider.PROVIDER_NAME,
        0.1,
        "Android 精简 BC 测试替身",
    )
}
