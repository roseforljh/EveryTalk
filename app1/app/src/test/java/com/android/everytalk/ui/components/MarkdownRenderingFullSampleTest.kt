package com.android.everytalk.ui.components

import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.extractFencedCodeBlockContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingFullSampleTest {

    @Test
    fun `full markdown sample parses code blocks and tables without leaking fences`() {
        val parts = ContentParser.parseCompleteContent(fullMarkdownSample, isStreaming = false)
        val codeParts = parts.filterIsInstance<ContentPart.Code>()
        val tableParts = parts.filterIsInstance<ContentPart.Table>()

        assertEquals(3, codeParts.size)
        assertTrue(codeParts.all { it.language == "bash" })
        assertTrue(codeParts.any { it.content.contains("git clone https://github.com/username/awesome-project.git") })
        assertTrue(codeParts.any { it.content.contains("go run main.go") })
        assertTrue(codeParts.any { it.content.contains("npm run dev") })
        assertTrue(codeParts.none { it.content.contains("```") })

        assertEquals(2, tableParts.size)
        assertTrue(tableParts.any { table -> table.lines.any { it.contains("响应时间") } })
        assertTrue(tableParts.any { table -> table.lines.any { it.contains("任务内容") } })

        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.content }
        assertTrue(text.contains("# 张三 (Alex Zhang)"))
        assertTrue(text.contains("# 🚀 Awesome Project Name"))
        assertTrue(text.contains("# 📝 会议纪要：Q3 产品规划会议"))
    }

    @Test
    fun `full markdown sample streaming blocks keep code highlighting inputs clean`() {
        val result = StreamBlockParser.parse(fullMarkdownSample, "full-sample")
        val codeBlocks = result.blocks.filterIsInstance<StreamBlock.CodeBlock>()
        val extracted = codeBlocks.map { extractFencedCodeBlockContent(it.text) }

        assertEquals(3, codeBlocks.size)
        assertTrue(extracted.all { it.language == "bash" })
        assertTrue(extracted.any { it.code.contains("git clone https://github.com/username/awesome-project.git") })
        assertTrue(extracted.any { it.code.contains("go run main.go") })
        assertTrue(extracted.any { it.code.contains("npm run dev") })
        assertFalse(extracted.any { it.code.contains("```") })
    }

    private companion object {
        private val fullMarkdownSample = """
            以下是直接渲染出来的 Markdown 格式内容：

            # 张三 (Alex Zhang)
            **全栈开发工程师** | 5年经验 | 现居：北京

            ---

            ## 📬 联系方式
            - **Email:** alex.zhang@example.com
            - **GitHub:** [github.com/alexzhang](https://github.com)
            - **个人博客:** [alexblog.dev](https://github.com)

            ## 🛠 技术栈
            - **前端:** React, Vue.js, TypeScript, Tailwind CSS
            - **后端:** Node.js, Go, Python (Django)
            - **数据库:** PostgreSQL, Redis, MongoDB
            - **工具/部署:** Docker, Kubernetes, AWS, CI/CD

            ## 💼 工作经历

            ### **高级开发工程师** | 某某科技有限公司
            *2022.03 - 至今*
            - 负责核心业务系统的重构，将系统响应时间降低了 **30%**。
            - 带领 5 人前端团队，引入 TypeScript 和自动化测试，代码覆盖率提升至 **85%**。

            ### **软件开发工程师** | 某互联网大厂
            *2020.07 - 2022.03*
            - 参与高并发 API 网关的开发，日均处理请求量达 **千万级**。
            - 优化数据库查询索引，解决了几处长达数秒的慢查询问题。

            ## 🎓 教育背景
            - **计算机科学与技术 (学士)** | 某重点大学 | *2016 - 2020*

            ---
            ---

            # 🚀 Awesome Project Name

            这是一个用 Go 和 React 编写的高性能、轻量级任务管理工具。

            ## ✨ 特性
            - [x] 实时同步（基于 WebSocket）
            - [x] 支持 Markdown 格式的任务描述
            - [x] 黑暗模式 (Dark Mode)
            - [ ] 移动端 App 支持 (开发中...)

            ## 📦 快速开始

            ### 前提条件
            确保你已安装了 [Go (1.18+)](https://golang.org) 和 [Node.js (16+)](https://nodejs.org)。

            ### 安装步骤

            1. **克隆仓库**
               ```bash
               git clone https://github.com/username/awesome-project.git
               cd awesome-project
               ```

            2. **启动后端服务**
               ```bash
               cd backend
               go run main.go
               ```

            3. **启动前端服务**
               ```bash
               cd ../frontend
               npm install
               npm run dev
               ```

            ## 📊 性能对比

            | 框架 | 内存占用 | 响应时间 (QPS 10k) |
            |:---|:---:|---:|
            | **本系统** | **15MB** | **1.2ms** |
            | 传统系统 A | 120MB | 15.4ms |
            | 传统系统 B | 85MB | 8.9ms |

            ## 📄 开源协议
            本项目基于 [MIT License](LICENSE) 协议开源。

            ---
            ---

            # 📝 会议纪要：Q3 产品规划会议

            **时间:** 2026年5月30日 10:00 - 11:30  
            **主持人:** @产品经理-小王  
            **记录人:** @助理-小李  
            **参会人员:** 研发团队、设计团队、运营团队  

            ---

            ## 🎯 会议议程
            1. 回顾 Q2 季度数据与目标达成情况。
            2. 讨论 Q3 核心功能点（用户社区、积分商城）。
            3. 确定各模块的排期与负责人。

            ## 💡 核心讨论内容
            - **用户社区模块:** 
              - 研发团队提出，由于涉及敏感词过滤，需要接入第三方安全 API。
              - 设计团队已完成第一版 UI，预计下周一进行评审。
            - **积分商城模块:**
              - 运营团队希望在 8 月中旬上线，配合周年庆活动。

            ## 📌 待办事项 (Action Items)

            | 任务内容 | 负责人 | 截止日期 | 状态 |
            |:---|:---:|:---:|:---:|
            | 接入第三方敏感词过滤 API 调研 | @研发-老张 | 06-05 | ⏳ 进行中 |
            | 社区 UI 设计稿评审与修改 | @设计-小美 | 06-02 | 📅 未开始 |
            | 积分商城后端架构设计 | @架构师-老李 | 06-10 | 📅 未开始 |

            ## 📅 下次会议安排
            - **主题:** 社区 UI 评审会
            - **时间:** 2026年6月2日 14:00
        """.trimIndent()
    }
}
