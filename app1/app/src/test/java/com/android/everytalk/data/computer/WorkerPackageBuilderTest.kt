package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkerPackageBuilderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `部署包按路径排序并生成稳定 hash`() {
        val root = temporaryFolder.newFolder("worker")
        root.resolve("z.js").writeText("z")
        root.resolve("index.js").writeText("export default {}")
        val packageResult = WorkerPackageBuilder().build(root)
        assertEquals(listOf("index.js", "z.js"), packageResult.files.map { it.relativePath })
        assertEquals(packageResult.requestHash, WorkerPackageBuilder().build(root).requestHash)
    }

    @Test
    fun `部署包拒绝敏感文件`() {
        val root = temporaryFolder.newFolder("worker")
        root.resolve(".env").writeText("TOKEN=secret")
        assertThrows(IllegalArgumentException::class.java) { WorkerPackageBuilder().build(root) }
    }

    @Test
    fun `部署包拒绝证书和 Token 文件`() {
        val root = temporaryFolder.newFolder("worker-sensitive")
        root.resolve("worker.js").writeText("export default {}")
        root.resolve("service-token.json").writeText("secret")
        assertThrows(IllegalArgumentException::class.java) { WorkerPackageBuilder().build(root) }
    }

    @Test
    fun `部署包跳过构建缓存并拒绝误命名文件中的高置信 Secret`() {
        val root = temporaryFolder.newFolder("worker-content-sensitive")
        root.resolve("worker.js").writeText("export default {}")
        root.resolve("build").mkdirs()
        root.resolve("build/generated.js").writeText("not deployed")
        root.resolve("config.txt").writeText("api_key=abcdefghijklmnopqrstuvwxyz")
        assertThrows(IllegalArgumentException::class.java) { WorkerPackageBuilder().build(root) }
        root.resolve("config.txt").delete()
        val result = WorkerPackageBuilder().build(root)
        assertEquals(false, result.files.any { it.relativePath.startsWith("build/") })
    }

    @Test
    fun `结构化配置固定入口和非敏感资源绑定并参与 hash`() {
        val root = temporaryFolder.newFolder("worker-config")
        root.resolve(".everytalk").mkdirs()
        root.resolve(".everytalk/worker.json").writeText(
            """{"entry":"src/index.js","bindings":[{"name":"DB","type":"d1","id":"db-1"},{"name":"ASSETS","type":"r2_bucket","id":"bucket-a"}]}""",
        )
        root.resolve("src").mkdirs()
        root.resolve("src/index.js").writeText("export default {}")
        root.resolve("worker.js").writeText("export default {}")

        val result = WorkerPackageBuilder().build(root)

        assertEquals("src/index.js", result.entryPoint)
        assertEquals(listOf("DB", "ASSETS").sorted(), result.bindings.map { it.name }.sorted())
        assertEquals(false, result.files.any { it.relativePath == ".everytalk/worker.json" })
        assertEquals(false, result.requestHash.isBlank())
    }

    @Test
    fun `没有明确入口时拒绝多入口项目`() {
        val root = temporaryFolder.newFolder("worker-ambiguous")
        root.resolve("worker.js").writeText("export default {}")
        root.resolve("index.js").writeText("export default {}")
        assertThrows(IllegalArgumentException::class.java) { WorkerPackageBuilder().build(root) }
    }
}
