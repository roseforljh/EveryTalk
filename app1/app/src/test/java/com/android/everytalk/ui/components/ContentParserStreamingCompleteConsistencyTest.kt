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

        val parts = ContentParser.parseCompleteContent(input, isStreaming = true)
        val codeParts = parts.filterIsInstance<ContentPart.Code>()

        assertEquals(7, codeParts.size)
        assertTrue(codeParts.any { it.content.contains("curl -fsSL https://get.docker.com | bash") })
        assertTrue(codeParts.any { it.content.contains("git clone https://github.com/router-for-me/CLIProxyAPI.git") })
        assertTrue(codeParts.any { it.content.contains("cp config.example.yaml config.yaml") })
        assertTrue(codeParts.any { it.content.contains("docker compose up -d") })
        assertTrue(codeParts.any { it.content.contains("wget https://github.com/router-for-me/CLIProxyAPI/releases/download") })
        assertTrue(codeParts.any { it.content.contains("./CLIProxyAPI") })
    }
}
