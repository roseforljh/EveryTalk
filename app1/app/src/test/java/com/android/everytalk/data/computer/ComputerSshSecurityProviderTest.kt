package com.android.everytalk.data.computer

import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertTrue
import org.junit.Test
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
        } finally {
            Security.removeProvider(providerName)
            if (originalProvider != null) {
                Security.insertProviderAt(originalProvider, originalPosition)
            }
        }
    }

    /** 模拟 Android 系统内置、缺少 SSHJ 所需算法的精简 BC。 */
    @Suppress("DEPRECATION")
    private class EmptyBouncyCastleProvider : Provider(
        BouncyCastleProvider.PROVIDER_NAME,
        0.1,
        "Android 精简 BC 测试替身",
    )
}
