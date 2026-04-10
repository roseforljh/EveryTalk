package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentParserNestedListFenceTest {

    @Test
    fun `indented fenced block inside list prose should render as code blocks`() {
        val input = """
            根据您搜索的“龙虾”（OpenClaw）AI 助手，以下是针对不同操作系统的**一键安装命令**：

            ### 1. 官方标准安装命令 (Linux / macOS)
            在终端中输入以下命令：
            ```bash
            curl -fsSL https://openclaw.ai/install.sh | bash
            ```

            ### 2. Windows 系统安装
            Windows 用户建议使用 **PowerShell**（管理员权限）运行以下命令：
            ```powershell
            iwr -useb https://openclaw.ai/install.ps1 | iex
            ```
            *注：如果遇到权限问题，可能需要先运行 `Set-ExecutionPolicy RemoteSigned -Scope CurrentUser`。*

            ### 3. 社区优化版/中文一键脚本 (适合国内环境)
            如果官方链接下载较慢，可以使用社区维护的快速安装脚本：
            *   **macOS:**
                ```bash
                curl -fsSL https://raw.githubusercontent.com/736773174/openclaw-setup-cn/main/install.sh | bash
                ```
            *   **Windows (PowerShell):**
                ```powershell
                irm https://raw.githubusercontent.com/736773174/openclaw-setup-cn/main/install.ps1 | iex
                ```

            ### 安装后的后续操作：
            1.  **启动配置向导**：安装完成后，输入以下命令开始配置模型和 API Key：
                ```bash
                openclaw onboard
                ```
            2.  **检查状态**：确保服务正在运行：
                ```bash
                openclaw gateway status
                ```
        """.trimIndent()

        val streamingParts = ContentParser.parseCompleteContent(input, isStreaming = true)
        val completeParts = ContentParser.parseCompleteContent(input, isStreaming = false)
        val streamingCodeParts = streamingParts.filterIsInstance<ContentPart.Code>()
        val completeCodeParts = completeParts.filterIsInstance<ContentPart.Code>()

        assertEquals(streamingCodeParts.size, completeCodeParts.size)
        assertEquals(6, completeCodeParts.size)
        assertTrue(completeCodeParts.any { it.language == "bash" && it.content.contains("openclaw.ai/install.sh | bash") })
        assertTrue(completeCodeParts.any { it.language == "powershell" && it.content.contains("install.ps1 | iex") })
        assertTrue(completeCodeParts.any { it.language == "bash" && it.content.contains("install.sh | bash") })
        assertTrue(completeCodeParts.any { it.language == "powershell" && it.content.contains("install.ps1 | iex") })
        assertTrue(completeCodeParts.any { it.language == "bash" && it.content.contains("openclaw onboard") })
        assertTrue(completeCodeParts.any { it.language == "bash" && it.content.contains("openclaw gateway status") })
    }

    @Test
    fun `windows list commands should stay as rich code blocks with current openclaw copy`() {
        val input = """
            根据您搜索的“龙虾”（OpenClaw）一键安装需求，以下是针对不同操作系统的安装命令：

            ### 1. 官方标准一键安装（Linux / macOS）
            在终端中复制并运行以下命令：
            ```bash
            curl -fsSL https://openclaw.ai/install.sh | bash
            ```

            ### 2. Windows 系统安装
            Windows 用户通常需要通过 **PowerShell**（管理员权限）运行安装脚本。目前社区推荐的一键安装方式如下：
            *   **方式 A（官方/通用）：**
                打开 PowerShell，输入：
                ```powershell
                irm https://openclaw.ai/install.ps1 | iex
                ```
            *   **方式 B（社区简化版/Qclaw）：**
                部分博主（如秋芝2046）提供的简化脚本，通常在 PowerShell 中运行：
                ```powershell
                iex (irm https://qclaw.io/install.ps1)
                ```

            ### 3. 安装后的初始化
            安装完成后，您需要运行以下命令来启动配置向导（选择模型、设置 API Key 等）：
            ```bash
            openclaw onboard --install-daemon
            ```
        """.trimIndent()

        val streamingParts = ContentParser.parseCompleteContent(input, isStreaming = true)
        val completeParts = ContentParser.parseCompleteContent(input, isStreaming = false)
        val streamingCodeParts = streamingParts.filterIsInstance<ContentPart.Code>()
        val completeCodeParts = completeParts.filterIsInstance<ContentPart.Code>()

        assertEquals(streamingCodeParts.size, completeCodeParts.size)
        assertEquals(4, completeCodeParts.size)
        assertTrue(completeCodeParts.any { it.language == "bash" && it.content.contains("openclaw.ai/install.sh | bash") })
        assertTrue(completeCodeParts.any { it.language == "powershell" && it.content.contains("irm https://openclaw.ai/install.ps1 | iex") })
        assertTrue(completeCodeParts.any { it.language == "powershell" && it.content.contains("iex (irm https://qclaw.io/install.ps1)") })
        assertTrue(completeCodeParts.any { it.language == "bash" && it.content.contains("openclaw onboard --install-daemon") })
    }
}
