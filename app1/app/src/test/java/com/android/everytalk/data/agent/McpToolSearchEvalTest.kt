package com.android.everytalk.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具搜索评测：真实 MCP 工具清单（GitHub 113、Jina 12 等，scripts/ops/dump_mcp_tools.py 抓取）
 * + 模型常写的查询 → 应命中的工具。默认一次加载 8 个，所以“前 8 命中”决定模型能不能拿到工具，
 * “第 1 命中”反映排序质量。改动排序算法后看这里的数字，不凭感觉。
 */
class McpToolSearchEvalTest {
    private val tools = run {
        val text = requireNotNull(javaClass.classLoader!!.getResource("mcp_tool_search_eval_tools.json")).readText()
        Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }.map { tool ->
            val server = tool["server"]!!.jsonPrimitive.content
            val name = tool["name"]!!.jsonPrimitive.content
            mapOf<String, Any>("type" to "function", "function" to mapOf(
                "name" to "mcp_${server.hashCode().toUInt().toString(16).padStart(8, '0').repeat(4)}_$name",
                "description" to McpToolDiscovery.tagServer(server, tool["description"]!!.jsonPrimitive.content),
                "parameters" to mapOf("type" to "object", "properties" to
                    tool["params"]!!.jsonArray.associate { it.jsonPrimitive.content to mapOf("type" to "string") }),
            ))
        }
    }

    private val cases = listOf(
        "list pull requests" to setOf("list_pull_requests", "search_pull_requests"),
        "create issue" to setOf("issue_write"),
        "comment on issue" to setOf("add_issue_comment"),
        "read file contents" to setOf("get_file_contents"),
        "create branch" to setOf("create_branch"),
        "merge pull request" to setOf("merge_pull_request"),
        "search code" to setOf("search_code"),
        "list commits" to setOf("list_commits"),
        "get commit details" to setOf("get_commit"),
        "latest release" to setOf("get_latest_release"),
        "list workflow runs" to setOf("actions_list"),
        "workflow job logs" to setOf("get_job_logs"),
        "dependabot alerts" to setOf("list_dependabot_alerts"),
        "list notifications" to setOf("list_notifications"),
        "fork repository" to setOf("fork_repository"),
        "star repository" to setOf("star_repository"),
        "create gist" to setOf("create_gist"),
        "search repositories" to setOf("search_repositories"),
        "get authenticated user" to setOf("get_me"),
        "list issues" to setOf("list_issues", "search_issues"),
        "review pull request" to setOf("pull_request_review_write"),
        "pull request diff" to setOf("pull_request_read"),
        "commit and push files" to setOf("push_files", "create_or_update_file"),
        "list branches" to setOf("list_branches"),
        "list tags" to setOf("list_tags"),
        "code scanning alerts" to setOf("list_code_scanning_alerts"),
        "trigger workflow" to setOf("actions_run_trigger"),
        "search users" to setOf("search_users"),
        "list discussions" to setOf("list_discussions"),
        "update pull request" to setOf("update_pull_request"),
        "screenshot webpage" to setOf("capture_screenshot_url"),
        "read url content" to setOf("read_url"),
        "search arxiv papers" to setOf("search_arxiv"),
        "extract pdf" to setOf("extract_pdf"),
        "search images" to setOf("search_images"),
        "deduplicate strings" to setOf("deduplicate_strings"),
        "rerank documents by relevance" to setOf("sort_by_relevance"),
        "guess publish date of url" to setOf("guess_datetime_url"),
    )

    /** 模型被要求写英文查询；中文查询走词表兜底，命中率单独统计。 */
    private val chineseCases = listOf(
        "列出拉取请求" to setOf("list_pull_requests", "search_pull_requests"),
        "创建问题" to setOf("issue_write"),
        "评论问题" to setOf("add_issue_comment"),
        "读取文件内容" to setOf("get_file_contents"),
        "创建分支" to setOf("create_branch"),
        "合并拉取请求" to setOf("merge_pull_request"),
        "搜索代码" to setOf("search_code"),
        "列出提交" to setOf("list_commits"),
        "最新发布" to setOf("get_latest_release"),
        "工作流日志" to setOf("get_job_logs"),
        "列出通知" to setOf("list_notifications"),
        "复刻仓库" to setOf("fork_repository"),
        "搜索仓库" to setOf("search_repositories"),
        "网页截图" to setOf("capture_screenshot_url"),
        "读取网页链接" to setOf("read_url"),
        "搜索论文" to setOf("search_arxiv", "search_ssrn"),
        "提取PDF" to setOf("extract_pdf"),
        "搜索图片" to setOf("search_images"),
    )

    private fun search(query: String): List<String> =
        McpToolDiscovery.search(AgentContentBlock.ToolCall("c", McpToolDiscovery.NAME, buildJsonObject { put("query", query) }), tools)
            .content.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content.substringAfter('_').substringAfter('_') }

    @Test
    fun `真实工具清单上的检索命中率`() {
        evaluate("英文", cases, minTop1Percent = 90)
        evaluate("中文", chineseCases, minTop1Percent = 80)
    }

    private fun evaluate(label: String, cases: List<Pair<String, Set<String>>>, minTop1Percent: Int) {
        val results = cases.map { (query, expected) -> Triple(query, expected, search(query)) }
        val top1 = results.count { (_, expected, found) -> found.firstOrNull() in expected }
        val top8 = results.count { (_, expected, found) -> found.any { it in expected } }
        val misses = results.filter { (_, expected, found) -> found.firstOrNull() !in expected }
            .joinToString("\n") { (query, expected, found) -> "  $query → ${found.take(3)}（应为 $expected）" }
        println("工具搜索评测（$label）：${cases.size} 条，第 1 命中 $top1，前 8 命中 $top8\n$misses")
        assertTrue("$label 前 8 必须全部命中：\n$misses", top8 == cases.size)
        assertTrue("$label 第 1 命中率低于 $minTop1Percent%：\n$misses", top1 * 100 >= cases.size * minTop1Percent)
    }
}
