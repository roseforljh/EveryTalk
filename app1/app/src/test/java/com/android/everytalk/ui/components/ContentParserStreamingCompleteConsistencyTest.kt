package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentParserStreamingCompleteConsistencyTest {

    @Test
    fun `streaming and complete parsing should keep top level fenced commands structurally consistent`() {
        val input = """
            部署 `CLIProxyAPI` 项目到 VPS 上，主要有两种推荐方式：**Docker 部署（最简单、推荐）** 和 **二进制/源码部署**。

            以下是详细的步骤指南：

            ### 方法一：使用 Docker Compose 部署（推荐）

            1. 安装 Docker 和 Docker Compose：
            ```bash
            curl -fsSL https://get.docker.com | bash
            ```

            2. 克隆项目并进入目录：
            ```bash
            git clone https://github.com/router-for-me/CLIProxyAPI.git
            cd CLIProxyAPI
            ```

            3. 启动容器：
            ```bash
            docker compose up -d
            ```

            ### 方法二：直接运行二进制文件

            1. 下载预编译二进制文件：
            ```bash
            wget https://github.com/router-for-me/CLIProxyAPI/releases/download/v6.9.14/CLIProxyAPI_6.9.14_linux_amd64.tar.gz
            tar -zxvf CLIProxyAPI_6.9.14_linux_amd64.tar.gz
            ```

            2. 运行：
            ```bash
            ./CLIProxyAPI
            ```
        """.trimIndent()

        val streamingParts = ContentParser.parseCompleteContent(input, isStreaming = true)
        val completeParts = ContentParser.parseCompleteContent(input, isStreaming = false)

        val streamingCode = streamingParts.filterIsInstance<ContentPart.Code>()
        val completeCode = completeParts.filterIsInstance<ContentPart.Code>()

        assertEquals(streamingCode.size, completeCode.size)
        assertEquals(5, streamingCode.size)
        assertTrue(completeCode.any { it.content.contains("curl -fsSL https://get.docker.com | bash") })
        assertTrue(completeCode.any { it.content.contains("docker compose up -d") })
        assertTrue(completeCode.any { it.content.contains("./CLIProxyAPI") })
    }

    @Test
    fun `bug txt sample should parse all fenced commands into code blocks`() {
        val input = """
            部署 `CLIProxyAPI` 项目到 VPS 上，主要有两种推荐方式：**Docker 部署（最简单、推荐）** 和 **二进制/源码部署**。

            以下是详细的步骤指南：

            ### 方法一：使用 Docker Compose 部署（推荐）

            这是最快且最容易维护的方法。

            1. **安装 Docker 和 Docker Compose**（如果尚未安装）：
            ```bash
            curl -fsSL https://get.docker.com | bash
            ```

            2. **克隆项目并进入目录**：
            ```bash
            git clone https://github.com/router-for-me/CLIProxyAPI.git
            cd CLIProxyAPI
            ```

            3. **准备配置文件**：
            - 复制示例配置文件：
            ```bash
            cp config.example.yaml config.yaml
            ```

            4. **启动容器**：
            ```bash
            docker compose up -d
            ```

            ### 方法二：直接运行二进制文件（适合不使用 Docker 的用户）

            1. **下载预编译二进制文件**：
            ```bash
            wget https://github.com/router-for-me/CLIProxyAPI/releases/download/v6.9.14/CLIProxyAPI_6.9.14_linux_amd64.tar.gz
            tar -zxvf CLIProxyAPI_6.9.14_linux_amd64.tar.gz
            ```

            2. **配置文件**：
            ```bash
            cp config.example.yaml config.yaml
            ```

            3. **运行**：
            ```bash
            ./CLIProxyAPI
            ```
        """.trimIndent()

        val streamingParts = ContentParser.parseCompleteContent(input, isStreaming = true)
        val completeParts = ContentParser.parseCompleteContent(input, isStreaming = false)
        val streamingCodeParts = streamingParts.filterIsInstance<ContentPart.Code>()
        val completeCodeParts = completeParts.filterIsInstance<ContentPart.Code>()

        assertEquals(streamingCodeParts.size, completeCodeParts.size)
        assertEquals(7, completeCodeParts.size)
        assertTrue(completeCodeParts.any { it.content.contains("curl -fsSL https://get.docker.com | bash") })
        assertTrue(completeCodeParts.any { it.content.contains("git clone https://github.com/router-for-me/CLIProxyAPI.git") })
        assertTrue(completeCodeParts.any { it.content.contains("cp config.example.yaml config.yaml") })
        assertTrue(completeCodeParts.any { it.content.contains("docker compose up -d") })
        assertTrue(completeCodeParts.any { it.content.contains("wget https://github.com/router-for-me/CLIProxyAPI/releases/download") })
        assertTrue(completeCodeParts.any { it.content.contains("./CLIProxyAPI") })
    }

    @Test
    fun `streaming and complete parsing should keep ragged escaped pipe table structurally consistent`() {
        val input = """
            | A | B | C | D |
            | :--- | :--- | :---: | :--- |
            | row A | escaped \| pipe | `x | y` | tail |
            | row B | only two cells |
        """.trimIndent()

        val streamingParts = ContentParser.parseCompleteContent(input, isStreaming = true)
        val completeParts = ContentParser.parseCompleteContent(input, isStreaming = false)
        val streamingTables = streamingParts.filterIsInstance<ContentPart.Table>()
        val completeTables = completeParts.filterIsInstance<ContentPart.Table>()

        assertEquals(
            streamingParts.map { it.javaClass.simpleName },
            completeParts.map { it.javaClass.simpleName }
        )
        assertEquals(1, streamingTables.size)
        assertEquals(1, completeTables.size)
        assertEquals(4, completeTables.single().lines.size)
    }

    @Test
    fun `table cell with promoted math should stay table`() {
        val input = """
            | Dimension | LaTeX | Edge |
            | :--- | :---: | :--- |
            | A | ${'$'}${'$'}\sum_{i=1}^{n} x_i^2 \ge \frac{(\sum x_i)^2}{n}${'$'}${'$'} | tail |
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input, isStreaming = true)
        val tables = parts.filterIsInstance<ContentPart.Table>()

        assertEquals(1, tables.size)
        assertEquals(3, tables.single().lines.size)
        assertTrue(tables.single().lines[2].contains("${'$'}${'$'}\\sum"))
    }
}
