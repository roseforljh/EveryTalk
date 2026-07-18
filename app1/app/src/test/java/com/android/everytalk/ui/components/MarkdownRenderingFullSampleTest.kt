package com.android.everytalk.ui.components

import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.extractFencedCodeBlockContent
import com.android.everytalk.ui.components.markdown.markdownToPlainText
import com.android.everytalk.ui.components.markdown.prepareMarkdownForMikePenz
import com.android.everytalk.ui.components.table.TableUtils
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

    @Test
    fun `pioneer pricing answer parses table lists bold text and prices safely`() {
        val parts = ContentParser.parseCompleteContent(pioneerPricingMarkdown, isStreaming = false)
        val tableParts = parts.filterIsInstance<ContentPart.Table>()
        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.content }

        assertEquals(1, tableParts.size)
        assertEquals(5, tableParts.single().lines.size)
        assertEquals(
            listOf("套餐", "价格", "适合对象", "包含内容 / 特点"),
            TableUtils.parseTableRow(tableParts.single().lines[0])
        )
        assertEquals(
            listOf(
                "**Free**",
                "**$0/月**",
                "试用、个人探索",
                "含 **$30 inference credit**；Inference API；Continuous model optimization；Agent mode；Adaptive Inference"
            ),
            TableUtils.parseTableRow(tableParts.single().lines[2])
        )
        assertEquals(
            listOf(
                "**Pro**",
                "**$20/用户/月**",
                "扩展中的团队",
                "包含 Free 全部功能；**2026 年 8 月 1 日前免费 inference**（受 rate limits 限制）；更高 rate limits；可下载模型权重；Deep Research mode；可邀请团队成员"
            ),
            TableUtils.parseTableRow(tableParts.single().lines[3])
        )

        assertTrue(text.contains("几个重点区别："))
        assertTrue(text.contains("1. **Free**"))
        assertTrue(text.contains("2. **Pro**"))
        assertTrue(text.contains("3. **Enterprise**"))
        val processed = prepareMarkdownForMikePenz(pioneerPricingMarkdown, "pricing")
        assertEquals(pioneerPricingMarkdown, processed)
        assertFalse(processed.contains("everytalk-math-inline:"))

        val plainText = markdownToPlainText(pioneerPricingMarkdown)
        assertTrue(plainText.contains("Free"))
        assertTrue(plainText.contains("$0/月"))
        assertTrue(plainText.contains("$30 inference credit"))
        assertFalse(plainText.contains("**"))
        assertFalse(plainText.contains("|:---"))
    }

    @Test
    fun `pioneer free credit answer keeps blockquotes and price text safe`() {
        val parts = ContentParser.parseCompleteContent(pioneerFreeCreditMarkdown, isStreaming = false)
        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.content }

        assertEquals(1, parts.size)
        assertTrue(parts.single() is ContentPart.Text)
        assertTrue(text.contains("> **$30 inference credit included**"))
        assertTrue(text.contains("> $30/month credit"))
        assertTrue(text.contains("> Is the $30 inference credit in the Free plan a one-time credit or does it renew every month?"))
        assertFalse(parts.any { it is ContentPart.Table })
        assertFalse(parts.any { it is ContentPart.Code })
        assertFalse(parts.any { it is ContentPart.Math })
        val processed = prepareMarkdownForMikePenz(pioneerFreeCreditMarkdown, "free-credit")
        assertEquals(pioneerFreeCreditMarkdown, processed)
        assertFalse(processed.contains("everytalk-math-inline:"))

        val plainText = markdownToPlainText(pioneerFreeCreditMarkdown)
        assertTrue(plainText.contains("$30 inference credit included"))
        assertTrue(plainText.contains("$30/month credit"))
        assertTrue(plainText.contains("support@fastino.ai"))
        assertFalse(plainText.contains("**"))
        assertFalse(plainText.contains("> "))
    }

    @Test
    fun `pioneer detailed pricing table normalizes html breaks in cells`() {
        val parts = ContentParser.parseCompleteContent(pioneerDetailedPricingMarkdown, isStreaming = false)
        val tableParts = parts.filterIsInstance<ContentPart.Table>()
        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.content }

        assertEquals(1, tableParts.size)
        assertEquals(5, tableParts.single().lines.size)
        assertEquals(
            listOf("套餐名称", "价格", "适用人群", "核心功能与权益"),
            TableUtils.parseTableRow(tableParts.single().lines[0])
        )

        val freeCells = TableUtils.parseTableRow(tableParts.single().lines[2])
        assertEquals("**Free (免费版)**", freeCells[0])
        assertEquals("**$0** / 月", freeCells[1])
        assertTrue(
            freeCells[3],
            freeCells[3].contains("赠送 **$30** 的推理额度\n• 基础推理 API")
        )
        assertFalse(freeCells[3].contains("<br>"))

        val proCells = TableUtils.parseTableRow(tableParts.single().lines[3])
        assertEquals("**Pro (专业版)**", proCells[0])
        assertEquals("**$20** / 用户/月", proCells[1])
        assertTrue(
            proCells[3],
            proCells[3].contains("包含 Free 版所有功能，并额外提供:\n• **限时免费推理**")
        )
        assertFalse(proCells[3].contains("<br>"))

        assertTrue(text.contains("### 套餐对比表"))
        assertTrue(text.contains("### 核心区别解析"))
        val processed = prepareMarkdownForMikePenz(pioneerDetailedPricingMarkdown, "detailed-pricing")
        assertEquals(pioneerDetailedPricingMarkdown, processed)
        assertFalse(processed.contains("everytalk-math-inline:"))
    }

    @Test
    fun `pioneer monthly credit answer keeps headings bullets and price text safe`() {
        val parts = ContentParser.parseCompleteContent(pioneerMonthlyCreditMarkdown, isStreaming = false)
        val text = parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.content }

        assertEquals(1, parts.size)
        assertTrue(parts.single() is ContentPart.Text)
        assertTrue(text.contains("是的，**每个月都有**。"))
        assertTrue(text.contains("### 💡 补充一个目前的“限时福利”："))
        assertTrue(text.contains("* **在 2026 年 8 月之前，平台免收所有套餐的超额费用"))
        assertFalse(parts.any { it is ContentPart.Table })
        assertFalse(parts.any { it is ContentPart.Code })
        assertFalse(parts.any { it is ContentPart.Math })

        val processed = prepareMarkdownForMikePenz(pioneerMonthlyCreditMarkdown, "monthly-credit")
        assertEquals(pioneerMonthlyCreditMarkdown, processed)
        assertFalse(processed.contains("everytalk-math-inline:"))

        val plainText = markdownToPlainText(pioneerMonthlyCreditMarkdown)
        assertTrue(plainText.contains("每个月都有"))
        assertTrue(plainText.contains("$75"))
        assertTrue(plainText.contains("$40/月"))
        assertFalse(plainText.contains("**"))
    }

    private companion object {
        private val pioneerDetailedPricingMarkdown = """
            **Pioneer AI**（由 Fastino Labs 开发，是一个用于微调、评估和部署小语言模型（SLMs）和大语言模型（LLMs）的开发者平台）目前提供三种主要的套餐方案：**Free（免费版）**、**Pro（专业版）**和**Enterprise（企业版）**。

            以下是各个套餐的价格、适用人群及核心功能区别：

            ### 套餐对比表

            | 套餐名称 | 价格 | 适用人群 | 核心功能与权益 |
            |:---|:---:|:---|:---|
            | **Free (免费版)** | **$0** / 月 | 个人开发者、平台初期的探索与测试用户 | • 赠送 **$30** 的推理额度<br>• 基础推理 API（Inference API）<br>• 持续模型优化（Continuous model optimization）<br>• 自适应推理（Adaptive Inference）<br>• 代理模式（Agent mode） |
            | **Pro (专业版)** | **$20** / 用户/月 | 处于扩展阶段、有生产环境部署需求的团队 | 包含 Free 版所有功能，并额外提供：<br>• **限时免费推理**（在 2026 年 8 月 1 日前免收超额费用，受速率限制约束）<br>• 更高的 API 速率限制（Rate limits）<br>• **支持下载模型权重**（Downloadable model weights）<br>• 深度研究模式（Deep Research Mode）<br>• 支持邀请团队成员协作 |
            | **Enterprise (企业版)** | **自定义** (需联系销售) | 具有复杂工作流、高安全与合规要求的大型企业 | 包含 Pro 版所有功能，并额外提供：<br>• 支持部署在用户自己的云端或私有 VPC（BYO cloud / private VPC）<br>• 专属的 H100 显卡算力集群（Dedicated H100 fleet）<br>• 24/7 专属技术支持与解决方案工程师（dedicated SE） |

            ---

            ### 核心区别解析

            1. **推理费用与额度**：
               * **Free 版**每月赠送 $30 的免费额度，适合轻度测试。
               * **Pro 版**目前提供非常优惠的政策：在 **2026 年 8 月 1 日之前**，平台免收超额推理费用（All-you-can-eat inference），只要在合理的速率限制内，你可以无限制地进行推理。
            2. **模型所有权（权重下载）**：
               * 只有 **Pro 及以上版本**才支持**下载模型权重**。如果你在 Pioneer 上微调（Fine-tune）了自己的模型并希望导出到本地或其他平台部署，必须订阅 Pro 版。
            3. **部署与算力保障**：
               * 免费版和 Pro 版均使用 Pioneer 的公共云端算力。
               * **Enterprise 版**支持将模型部署在企业自己的私有云（VPC）中，并提供独占的 H100 算力集群，保障数据隐私和极高的响应速度。
        """.trimIndent()

        private val pioneerMonthlyCreditMarkdown = """
            是的，**每个月都有**。

            根据 Pioneer AI 官方最新的定价文档（Pricing Docs），关于免费额度的具体规则如下：

            1. **按月自动刷新**：
               官方文档明确指出：**“You get free usage included each month”**（你每个月都会获得包含的免费额度）。这意味着上个月的额度用完或过期后，下个月会自动重新注入，供你继续免费使用。
            2. **实际额度可能比想象的更多**：
               虽然其官网主页部分地方写着 $30，但其官方最新的定价文档中明确写道：**免费版每月赠送的额度其实是 $75**（*“You get $75 of free usage included each month”*）。
            3. **无需绑定信用卡**：
               获取和使用这个每月免费额度**不需要绑定任何信用卡**（No credit card required），注册账号即可直接使用，完全不用担心被意外扣费。

            ### 💡 补充一个目前的“限时福利”：
            官方文档提到，目前 Pioneer 正在实行**免超额费政策**：
            * **在 2026 年 8 月之前，平台免收所有套餐的超额费用（waiving overage charges）**。
            * 也就是说，即使你在免费版（Free）不小心用超了，或者在专业版（Pro，最新文档价格已更新为 **$40/月**）进行高频调用，在 2026 年 8 月之前，只要在合理的速率限制（Rate limits）内，你都不需要支付任何超额费用。
        """.trimIndent()

        private val pioneerFreeCreditMarkdown = """
            从官网价格页的写法看，**Free 套餐的 “$30 inference credit included” 更像是一次性赠送额度，不是明确写着每个月都有 $30**。

            原因是它只写了：

            > **$30 inference credit included**

            没有写：

            > $30/month credit
            > monthly credits
            > renews every month

            所以更稳妥的理解是：**注册/开通 Free 时包含 $30 推理额度，用完就没了**。

            另外官网还写了：

            > **No overage charges until August 2026**

            以及 Pro 有：

            > **Free inference until Aug 1, 2026**

            也就是说，目前到 2026 年 8 月前，他们在超额计费上可能比较宽松，但 **Free 的 $30 是否每月刷新，官网没有明确说明**。

            如果你要确认，建议看两处：

            1. 登录后台 billing / usage 页面，看 credit 是否显示 renewal date；
            2. 发邮件问官方：support@fastino.ai

            你可以这样问：

            > Is the $30 inference credit in the Free plan a one-time credit or does it renew every month?
        """.trimIndent()

        private val pioneerPricingMarkdown = """
            我查了 pioneer.ai 官网 Pricing 页和文档页，目前它有 **Free、Pro、Enterprise** 三档：

            | 套餐 | 价格 | 适合对象 | 包含内容 / 特点 |
            |:---|:---:|:---|
            | **Free** | **$0/月** | 试用、个人探索 | 含 **$30 inference credit**；Inference API；Continuous model optimization；Agent mode；Adaptive Inference |
            | **Pro** | **$20/用户/月** | 扩展中的团队 | 包含 Free 全部功能；**2026 年 8 月 1 日前免费 inference**（受 rate limits 限制）；更高 rate limits；可下载模型权重；Deep Research mode；可邀请团队成员 |
            | **Enterprise** | **定制报价** | 大团队、复杂工作流、企业部署 | BYO cloud / private VPC；Dedicated H100 fleet；SOC2 / HIPAA 合规；24/7 SLA；专属 SE/解决方案工程师；定制价格 |

            几个重点区别：

            1. **Free**
               - 免费用。
               - 主要靠赠送的 **$30 inference credits** 体验平台。
               - 基础能力都有：API、自动模型优化、Agent mode、Adaptive Inference。

            2. **Pro**
               - **$20/人/月**。
               - 适合团队正式用。
               - 最大卖点是：到 **2026 年 8 月 1 日前 inference 免费**，但官方说明受速率限制。
               - 比 Free 多：更高限额、模型权重下载、Deep Research、团队成员邀请。

            3. **Enterprise**
               - 不公开标价，需要联系销售。
               - 主要面向对安全、私有化、算力、SLA 有要求的公司。
               - 支持私有 VPC / 自带云、专用 H100 集群、合规和 24/7 支持。

            补充：官网还写了 **“No overage charges until August 2026”**，意思是 2026 年 8 月前不会收超额费用，但具体 rate limit / 使用上限还是以账号后台显示为准。
        """.trimIndent()

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
