package com.android.everytalk.data.computer

import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class ComputerSshFoundationTest {
    @Test
    fun `取消探测命令会先关通道解除阻塞读取`() = kotlinx.coroutines.runBlocking {
        val client = io.mockk.mockk<net.schmizz.sshj.SSHClient>(relaxed = true)
        val session = io.mockk.mockk<net.schmizz.sshj.connection.channel.direct.Session>(relaxed = true)
        val command = io.mockk.mockk<net.schmizz.sshj.connection.channel.direct.Session.Command>(relaxed = true)
        val reading = java.util.concurrent.CountDownLatch(2)
        val closed = java.util.concurrent.CountDownLatch(1)
        val releaseRemoteClose = java.util.concurrent.CountDownLatch(1)
        fun blockedStream() = object : java.io.InputStream() {
            override fun close() { closed.countDown() }
            override fun read(): Int {
                reading.countDown()
                check(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return -1
            }
        }
        io.mockk.every { client.isConnected } returns true
        io.mockk.every { client.isAuthenticated } returns true
        io.mockk.every { client.startSession() } returns session
        io.mockk.every { session.exec("probe") } returns command
        io.mockk.every { command.inputStream } returns blockedStream()
        io.mockk.every { command.errorStream } returns blockedStream()
        io.mockk.every { command.outputStream } returns java.io.ByteArrayOutputStream()
        io.mockk.every { command.isOpen } returns true
        io.mockk.every { command.join(any(), any()) } answers { Thread.yield() }
        io.mockk.every { command.close() } answers {
            releaseRemoteClose.await(5, java.util.concurrent.TimeUnit.SECONDS)
            Unit
        }
        io.mockk.every { session.close() } answers {
            releaseRemoteClose.await(5, java.util.concurrent.TimeUnit.SECONDS)
            Unit
        }
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { ComputerSshConnection(client).execute("probe") }
        try {
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            kotlinx.coroutines.withTimeout(1_000) { job.join() }
            assertEquals(0L, closed.count)
        } finally {
            releaseRemoteClose.countDown()
            closed.countDown()
            job.cancel()
        }
    }

    @Test
    fun `SSH关闭阻塞也不能阻塞取消调用线程`() = kotlinx.coroutines.runBlocking {
        val client = io.mockk.mockk<net.schmizz.sshj.SSHClient>(relaxed = true)
        val started = java.util.concurrent.CountDownLatch(1)
        val releaseClose = java.util.concurrent.CountDownLatch(1)
        io.mockk.every { client.connect("example.com", 22) } answers {
            started.countDown()
            releaseClose.await(5, java.util.concurrent.TimeUnit.SECONDS)
            throw java.io.IOException("closed")
        }
        io.mockk.every { client.close() } answers {
            releaseClose.await(5, java.util.concurrent.TimeUnit.SECONDS)
            Unit
        }
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            ComputerSshClient(clientFactory = { client }).probeHostKey("example.com", 22)
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            executor.submit { job.cancel() }.get(1, java.util.concurrent.TimeUnit.SECONDS)
            Unit
        } finally {
            releaseClose.countDown()
            executor.shutdownNow()
            kotlinx.coroutines.withTimeout(5_000) { job.join() }
        }
    }

    @Test
    fun `IO连接成功但返回调用方之前取消也会关闭连接`() = kotlinx.coroutines.runBlocking {
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                queue.add(block)
            }
        }
        val client = io.mockk.mockk<net.schmizz.sshj.SSHClient>(relaxed = true)
        io.mockk.every { client.isAuthenticated } returns true
        val computer = Computer(
            id = "cancel-return", displayName = "test", host = "example.com", port = 22, username = "root",
            hostKeyAlgorithm = "ssh-ed25519", hostKeyBlobBase64 = "AQ==",
            authKind = ComputerAuthKind.PASSWORD, runMode = ComputerRunMode.DIRECT,
        )
        var delivered = false
        val job = launch(dispatcher) {
            ComputerSshClient(clientFactory = { client }).connect(computer, ComputerCredential.Password(charArrayOf('p')))
            delivered = true
        }
        requireNotNull(queue.poll(5, java.util.concurrent.TimeUnit.SECONDS)).run()
        val returning = requireNotNull(queue.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        returning.run()
        // 取消清理也在 IO 上；驱动它返回调用方的最后一次调度。
        while (!job.isCompleted) {
            requireNotNull(queue.poll(5, java.util.concurrent.TimeUnit.SECONDS)).run()
        }
        assertTrue(!delivered)
        io.mockk.verify(atLeast = 1) { client.close() }
    }
    @Test
    fun `取消指纹探测会关闭连接且不变成认证失败`() = kotlinx.coroutines.runBlocking {
        val client = io.mockk.mockk<net.schmizz.sshj.SSHClient>(relaxed = true)
        val started = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        io.mockk.every { client.connect("example.com", 22) } answers {
            started.countDown()
            check(closed.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "取消没有关闭 SSH 连接" }
            throw java.io.IOException("closed")
        }
        io.mockk.every { client.close() } answers { closed.countDown() }
        var failure: Throwable? = null
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                ComputerSshClient(clientFactory = { client }).probeHostKey("example.com", 22)
            } catch (error: Throwable) {
                failure = error
            }
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        kotlinx.coroutines.withTimeout(5_000) { job.cancel(); job.join() }
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        io.mockk.verify(atLeast = 1) { client.close() }
    }
    @Test
    fun `endpoint validator accepts domain ipv4 and ipv6 without url syntax`() {
        assertEquals(
            "vps.example.com",
            ComputerEndpointValidator.validate(" VPS.Example.COM. ", 22, "root").host,
        )
        assertEquals("192.0.2.8", ComputerEndpointValidator.validate("192.0.2.8", 2222).host)
        assertEquals("2001:db8::8", ComputerEndpointValidator.validate("[2001:db8::8]", 22).host)
    }

    @Test
    fun `endpoint validator rejects url userinfo path embedded port and malformed ipv4`() {
        val invalidHosts = listOf(
            "https://vps.example.com",
            "root@vps.example.com",
            "vps.example.com/home",
            "vps.example.com:22",
            "192.0.2.999",
            "vps.example.com\nsecond-host",
        )

        invalidHosts.forEach { host ->
            val failure = runCatching { ComputerEndpointValidator.validate(host, 22) }.exceptionOrNull()
            assertTrue("Host 应被拒绝：$host", failure is ComputerException)
            assertEquals(ComputerErrorCodes.HOST_INVALID, (failure as ComputerException).code)
        }
    }

    @Test
    fun `host key codec emits openssh blob and sha256 fingerprint`() {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public

        val encoded = ComputerHostKeyCodec.encode(publicKey)

        assertEquals("ssh-rsa", encoded.algorithm)
        assertTrue(encoded.blob.isNotEmpty())
        assertEquals(encoded.blob.toList(), Base64.getDecoder().decode(encoded.blobBase64).toList())
        assertTrue(encoded.fingerprint.startsWith("SHA256:"))
        assertTrue('=' !in encoded.fingerprint)
    }
}
