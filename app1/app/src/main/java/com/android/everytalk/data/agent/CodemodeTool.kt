package com.android.everytalk.data.agent

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pi codemode（packages/coding-agent/docs/codemode.md，v1.5.2）的 Kotlin 移植。
 *
 * 模型写一段 JavaScript，在隔离的 JS 引擎里通过 `tools.<name>(args)` 调用本轮其他工具，
 * 只有脚本输出进入上下文。引擎不能回调宿主，所以 JS 把调用排进队列，宿主轮询
 * `__cm_poll()` 取出调用、执行后用 `__cm_resolve()` 交回结果，直到脚本结束。
 * 沙箱没有计时器、网络和文件系统：没有待执行调用而脚本仍未结束，就是在等永远不会完成的 Promise。
 */
object CodemodeTool {
    const val NAME = "codemode"

    /** Pi 默认输出上限 10000 token；按 4 字符估算 1 token。 */
    private const val DEFAULT_MAX_OUTPUT_TOKENS = 10_000L
    private const val CHARS_PER_TOKEN = 4

    // ponytail: Pi 默认不限时，但手机上失控的死循环会一直耗电；用户也可随时停止整个 Run。
    private const val DEFAULT_TIMEOUT_MS = 10 * 60_000L

    /** 内联声明共享约 3000 token（Pi codemode.inlineBudget），其余用 searchTools/describeTool 查找。 */
    private const val INLINE_DECLARATION_BUDGET_CHARS = 3_000 * CHARS_PER_TOKEN

    internal const val MAX_STORE_VALUE_CHARS = 262_144
    internal const val MAX_STORE_CHARS = 1_048_576
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 私有候选状态经完整 JSON 大小检查后返回，不修改调用者；提交点是成功 ToolResult 落库。 */
    internal fun applyStoreDelta(base: Map<String, JsonElement>, delta: Map<String, JsonElement>): Map<String, JsonElement> {
        val candidate = base.toMutableMap()
        delta.forEach { (key, value) ->
            if (value is kotlinx.serialization.json.JsonNull) candidate.remove(key) else candidate[key] = value
        }
        require(candidate.values.all { it.toString().length <= MAX_STORE_VALUE_CHARS }) { "Codemode store value exceeds 262144 UTF-16 characters" }
        require(JsonObject(candidate).toString().length <= MAX_STORE_CHARS) { "Codemode store exceeds 1048576 UTF-16 characters" }
        return candidate
    }

    /** 可在脚本里调用的工具：控制类工具需要人工流程，codemode 不能嵌套自己。 */
    fun callableTools(tools: List<Map<String, Any>>?): List<CodemodeToolInfo> = tools.orEmpty().mapNotNull { definition ->
        @Suppress("UNCHECKED_CAST")
        val function = definition["function"] as? Map<String, Any> ?: definition
        val name = function["name"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (name == NAME || name == McpToolDiscovery.NAME || name == AgentToolResultReadTool.NAME || name in AgentControlToolNames.all) return@mapNotNull null
        CodemodeToolInfo(
            name = name,
            id = identifier(name),
            description = function["description"]?.toString().orEmpty(),
            parameters = anyToJson(function["parameters"]) as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /** 有可调用工具且尚未注入时追加 codemode 定义；恢复运行时请求快照里已有，保持幂等。 */
    fun withCodemode(tools: List<Map<String, Any>>?): List<Map<String, Any>>? {
        if (tools == null || tools.any { (it["function"] as? Map<*, *>)?.get("name") == NAME || it["name"] == NAME }) return tools
        val callable = callableTools(tools)
        if (callable.isEmpty()) return tools
        return tools + definition(callable)
    }

    fun definition(callable: List<CodemodeToolInfo>): Map<String, Any> {
        val declarations = StringBuilder()
        var hidden = 0
        callable.forEach { tool ->
            val line = "- tools.${tool.id}(args: ${tool.parameters}) — ${tool.description.lineSequence().firstOrNull().orEmpty().take(200)}\n"
            if (declarations.length + line.length <= INLINE_DECLARATION_BUDGET_CHARS) declarations.append(line) else hidden++
        }
        val description = buildString {
            appendLine("Run a JavaScript script that calls the other tools. Only the script's output reaches you, so use it to run several calls in parallel and to filter large results (logs, file contents, command output) before you read them.")
            appendLine("The script is the body of an async function: top-level await and return work. There are no Node APIs, timers, network or file system; reach the outside world only through tools.")
            appendLine("Optional first line: // @options: {\"max_output_tokens\": 2000, \"timeout_ms\": 60000} (defaults: 10000 tokens, 10 minutes).")
            appendLine("Globals: tools.<name>(args) resolves to the tool's result (parsed JSON or text) and rejects with an Error on failure, so use Promise.allSettled to keep partial results; text(value) adds an output item; console.log(...) adds a line to the console block; return value adds a text item; exit() ends successfully; store(key, value)/load(key) keep small JSON values across codemode calls in this run; ALL_TOOLS lists every callable tool; searchTools(query, {limit}) and describeTool(name) find tools and their parameter schemas.")
            appendLine("Tools that need the user's approval cannot run inside a script; call them directly instead. Tool calls are real and are not undone when the script fails.")
            appendLine("Callable tools:")
            append(declarations)
            if (hidden > 0) appendLine("…and $hidden more; find them with searchTools() or ALL_TOOLS.")
        }.trimEnd()
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to NAME,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "code" to mapOf("type" to "string", "description" to "JavaScript source, not a markdown code fence."),
                    ),
                    "required" to listOf("code"),
                ),
            ),
            "executionMode" to "sequential",
        )
    }

    data class Options(val maxOutputTokens: Long, val timeoutMs: Long)

    /** 选项行只能在第一行；出现在别处时与 Pi 一样在运行前失败。 */
    fun parseOptions(code: String): Options {
        val lines = code.lines()
        val marker = "// @options:"
        lines.drop(1).firstOrNull { it.trimStart().startsWith(marker) }?.let {
            throw IllegalArgumentException("The // @options: line must be the first line of the script")
        }
        val first = lines.firstOrNull()?.trimStart().orEmpty()
        if (!first.startsWith(marker)) return Options(DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_TIMEOUT_MS)
        val options = runCatching { Json.parseToJsonElement(first.removePrefix(marker).trim()).jsonObject }
            .getOrElse { throw IllegalArgumentException("Invalid // @options: JSON: ${it.message}") }
        return Options(
            maxOutputTokens = options["max_output_tokens"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(1) ?: DEFAULT_MAX_OUTPUT_TOKENS,
            timeoutMs = options["timeout_ms"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(1) ?: DEFAULT_TIMEOUT_MS,
        )
    }

    /**
     * 运行脚本。[callTool] 执行一次嵌套调用，返回 (成功, 结果)；失败时结果是错误文本。
     * [store] 是本 Run 的 store/load 状态，只有脚本成功时才写回。
     */
    suspend fun run(
        code: String,
        isolate: CodemodeIsolate,
        callable: List<CodemodeToolInfo>,
        store: Map<String, JsonElement>,
        maxModelResultTokens: Long,
        callTool: suspend (name: String, args: JsonObject) -> Pair<Boolean, JsonElement>,
    ): CodemodeOutcome {
        val startedAt = System.nanoTime()
        fun seconds() = "%.1fs".format(java.util.Locale.ROOT, (System.nanoTime() - startedAt) / 1e9)
        val options = try {
            parseOptions(code)
        } catch (error: IllegalArgumentException) {
            return CodemodeOutcome("Script failed in ${seconds()}\nScript error: ${error.message}", isError = true)
        }
        val maxChars = (minOf(options.maxOutputTokens, maxModelResultTokens) * CHARS_PER_TOKEN).toInt()
        val poll: CodemodePoll = try {
            withTimeout(options.timeoutMs) {
                isolate.evaluate(CODEMODE_PRELUDE)
                applyStoreDelta(store, emptyMap())
                isolate.evaluate("__cm_init(${json.encodeToString(callable)}, JSON.parse(${json.encodeToString(json.encodeToString(store))}));''")
                isolate.evaluate("__cm_start(${json.encodeToString(code)});''")
                val semaphore = Semaphore(MAX_PARALLEL_TOOL_CALLS)
                var idlePolls = 0
                var finished: CodemodePoll? = null
                while (finished == null) {
                    val current = json.decodeFromString<CodemodePoll>(isolate.evaluate("__cm_poll()"))
                    if (current.state != "running") {
                        finished = current
                        continue
                    }
                    if (current.calls.isEmpty()) {
                        // 每次 evaluate 结束都会清空微任务；连续两次没有新调用说明脚本在等不会完成的 Promise。
                        if (++idlePolls >= 2) {
                            finished = snapshot(isolate).copy(
                                state = "failed",
                                error = "Script is awaiting a promise that never resolves (the sandbox has no timers or I/O)",
                            )
                        }
                        continue
                    }
                    idlePolls = 0
                    val results = coroutineScope {
                        current.calls.map { call ->
                            async { semaphore.withPermit { call.id to callTool(call.name, call.args) } }
                        }.awaitAll()
                    }
                    results.forEach { (id, result) ->
                        isolate.evaluate("__cm_resolve(${json.encodeToString(id)}, ${result.first}, ${json.encodeToString(json.encodeToString(result.second))});''")
                    }
                }
                checkNotNull(finished)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            // 同步死循环会挡住后续 evaluate，取部分输出只等 1 秒。
            val partial = withTimeoutOrNull(1_000) { runCatching { snapshot(isolate) }.getOrNull() }
            (partial ?: CodemodePoll(state = "failed")).copy(state = "failed", error = "Script timed out after ${options.timeoutMs}ms")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            CodemodePoll(state = "failed", error = error.message ?: error.javaClass.simpleName)
        }
        val completed = try {
            if (poll.state == "done") applyStoreDelta(store, poll.store.orEmpty())
            poll
        } catch (error: IllegalArgumentException) {
            poll.copy(state = "failed", error = error.message, store = null)
        }
        return CodemodeOutcome(
            format(completed, seconds(), maxChars), isError = completed.state != "done",
            storeDelta = completed.store.takeIf { completed.state == "done" },
        )
    }

    private suspend fun snapshot(isolate: CodemodeIsolate): CodemodePoll =
        json.decodeFromString(isolate.evaluate("__cm_snapshot()"))

    internal fun format(poll: CodemodePoll, wallTime: String, maxChars: Int): String {
        val body = buildString {
            val items = poll.items.orEmpty()
            items.forEachIndexed { index, item ->
                if (items.size > 1) appendLine("==> text ${index + 1}/${items.size} <==")
                appendLine(item)
            }
            val console = poll.console.orEmpty()
            if (console.isNotEmpty()) {
                appendLine("<console_output>")
                console.forEach(::appendLine)
                appendLine("</console_output>")
            }
            if (poll.state != "done") append("Script error: ").appendLine(poll.error ?: "unknown error")
        }.trimEnd()
        val header = if (poll.state == "done") "Script completed in $wallTime" else "Script failed in $wallTime"
        return "$header\n${truncateMiddle(body, maxChars)}".trimEnd()
    }

    /** 超长输出保留开头和结尾，与 Pi 一致。 */
    internal fun truncateMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val keep = (maxChars / 2).coerceAtLeast(1)
        return text.take(keep) + "\n…[${text.length - keep * 2} characters omitted]…\n" + text.takeLast(keep)
    }

    /** JS 标识符只允许字母、数字、_ 和 $；其余字符替换成 _，与 Pi 相同。 */
    fun identifier(name: String): String {
        val replaced = name.replace(Regex("[^A-Za-z0-9_$]"), "_")
        return if (replaced.firstOrNull()?.isDigit() == true) "_$replaced" else replaced
    }

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> value
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJson(v) })
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map(::anyToJson))
        is Array<*> -> kotlinx.serialization.json.JsonArray(value.map(::anyToJson))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}

@Serializable
data class CodemodeToolInfo(
    val name: String,
    val id: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class CodemodeCall(val id: String, val name: String, val args: JsonObject = JsonObject(emptyMap()))

@Serializable
data class CodemodePoll(
    val state: String,
    val error: String? = null,
    val calls: List<CodemodeCall> = emptyList(),
    val items: List<String>? = null,
    val console: List<String>? = null,
    val store: Map<String, JsonElement>? = null,
)

data class CodemodeOutcome(
    val text: String,
    val isError: Boolean,
    val storeDelta: Map<String, JsonElement>? = null,
)

/** 单次脚本独占的 JS 执行环境；evaluate 返回表达式的字符串结果。 */
interface CodemodeIsolate : AutoCloseable {
    suspend fun evaluate(expression: String): String
}

fun interface CodemodeEngine {
    suspend fun open(): CodemodeIsolate
}

/**
 * AndroidX JavaScriptSandbox 实现。运行在 WebView 提供的隔离进程，没有网络、文件和 JS Interface；
 * 每个脚本独占一个 isolate，结束、失败、超时或取消都关闭它，失控的同步死循环随之终止。
 */
class SandboxCodemodeEngine(private val context: Context) : CodemodeEngine {
    override suspend fun open(): CodemodeIsolate {
        if (!JavaScriptSandbox.isSupported()) throw IllegalStateException("当前设备不支持 Android JavaScriptSandbox")
        val sandbox = awaitFuture(JavaScriptSandbox.createConnectedInstanceAsync(context))
        try {
            check(sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION) &&
                sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
                "当前 WebView 缺少 codemode 所需的 JavaScriptSandbox 能力"
            }
            val parameters = IsolateStartupParameters()
            if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                parameters.setMaxHeapSizeBytes(256L * 1024 * 1024)
            }
            parameters.setMaxEvaluationReturnSizeBytes(32 * 1024 * 1024)
            val isolate = sandbox.createIsolate(parameters)
            return object : CodemodeIsolate {
                override suspend fun evaluate(expression: String): String = awaitFuture(isolate.evaluateJavaScriptAsync(expression))
                override fun close() {
                    isolate.close()
                    sandbox.close()
                }
            }
        } catch (error: Throwable) {
            sandbox.close()
            throw error
        }
    }

    private suspend fun <T> awaitFuture(future: ListenableFuture<T>): T = suspendCancellableCoroutine { continuation ->
        future.addListener({
            runCatching { future.get() }
                .onSuccess { if (continuation.isActive) continuation.resume(it) }
                .onFailure { error ->
                    val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
                    if (continuation.isActive) continuation.resumeWithException(cause)
                }
        }, Executor { it.run() })
        continuation.invokeOnCancellation { future.cancel(true) }
    }
}

/** 引擎内的运行时。所有输出、调用队列和 store 都在这里，宿主只做轮询和结果回填。 */
internal const val CODEMODE_PRELUDE = """
(() => {
  const g = globalThis;
  const MAX_OUTPUT_CHARS = 16777216, MAX_OUTPUT_CALLS = 100000;
  const AsyncFunction = (async () => {}).constructor;
  let tools = [], byId = new Map(), byName = new Map(), storeBase = Object.create(null), storeWrites = Object.create(null);
  const pending = [], waiters = new Map(), items = [], consoleLines = [];
  let nextId = 0, state = "idle", error = null, outputChars = 0, outputCalls = 0;
  class CodemodeExit extends Error {}
  class CodemodeLimit extends Error {}
  const show = (v) => typeof v === "string" ? v : v === undefined ? "undefined" : JSON.stringify(v, null, 2);
  const account = (s) => {
    outputChars += s.length; outputCalls += 1;
    if (outputChars > MAX_OUTPUT_CHARS || outputCalls > MAX_OUTPUT_CALLS) throw new CodemodeLimit("Script output limit exceeded; write large data with a tool instead");
  };
  g.text = (v) => { const s = show(v); account(s); items.push(s); };
  const log = (...a) => { const s = a.map((x) => typeof x === "string" ? x : show(x)).join(" "); account(s); consoleLines.push(s); };
  g.console = { log, info: log, warn: log, error: log, debug: log };
  g.exit = () => { throw new CodemodeExit("exit"); };
  g.store = (key, value) => {
    const k = String(key), encoded = value === undefined ? "null" : JSON.stringify(value);
    if (encoded.length > 262144) throw new CodemodeLimit("Codemode store value exceeds 262144 UTF-16 characters");
    const v = JSON.parse(encoded), candidate = Object.assign(Object.create(null), storeBase, storeWrites);
    candidate[k] = v;
    for (const name of Object.keys(candidate)) if (candidate[name] === null) delete candidate[name];
    if (JSON.stringify(candidate).length > 1048576) throw new CodemodeLimit("Codemode store exceeds 1048576 UTF-16 characters");
    storeWrites[k] = v;
  };
  g.load = (key) => { const k = String(key); const v = Object.prototype.hasOwnProperty.call(storeWrites, k) ? storeWrites[k] : storeBase[k]; return v === null ? undefined : v; };
  const call = (name, args) => new Promise((resolve, reject) => {
    const id = String(++nextId);
    waiters.set(id, { resolve, reject });
    pending.push({ id, name, args: args == null ? {} : JSON.parse(JSON.stringify(args)) });
  });
  g.tools = new Proxy({}, {
    get(_, key) {
      if (typeof key !== "string") return undefined;
      const tool = byId.get(key) || byName.get(key);
      return tool ? (args) => call(tool.name, args) : undefined;
    },
    has(_, key) { return byId.has(key) || byName.has(key); },
    ownKeys() { return [...byId.keys()]; },
    getOwnPropertyDescriptor(_, key) { return byId.has(key) ? { enumerable: true, configurable: true } : undefined; },
  });
  g.describeTool = async (name) => {
    const t = byId.get(name) || byName.get(name);
    return t ? { name: t.id, description: t.description, parameters: t.parameters } : undefined;
  };
  g.searchTools = async (query, options = {}) => {
    const terms = String(query).toLowerCase().split(/[^a-z0-9一-鿿]+/).filter(Boolean);
    const scored = tools.map((t) => {
      const hay = (t.name + " " + t.description + " " + JSON.stringify(t.parameters)).toLowerCase();
      return { t, score: t.name.toLowerCase() === String(query).toLowerCase() || t.id.toLowerCase() === String(query).toLowerCase() ? 10000 : terms.reduce((sum, term) => sum + (hay.split(term).length - 1) + (t.name.toLowerCase().includes(term) ? 3 : 0), 0) };
    }).filter((x) => x.score > 0).sort((a, b) => b.score - a.score);
    return scored.slice(0, options.limit || 8).map(({ t }) => ({ name: t.id, description: t.description }));
  };
  g.__cm_init = (list, store) => {
    tools = list; byId = new Map(list.map((t) => [t.id, t])); byName = new Map(list.map((t) => [t.name, t]));
    g.ALL_TOOLS = list.map((t) => ({ name: t.id, description: t.description }));
    storeBase = Object.assign(Object.create(null), store || {});
  };
  const finish = (v) => { if (v !== undefined) { const s = show(v); items.push(s); } state = "done"; };
  const fail = (e) => {
    if (e instanceof CodemodeExit) { state = "done"; return; }
    state = "failed";
    error = e && e.stack ? String(e.stack) : String(e);
  };
  g.__cm_start = (source) => {
    state = "running";
    let main;
    try { main = new AsyncFunction(source); } catch (e) { fail(e); return; }
    try { main().then(finish, fail); } catch (e) { fail(e); }
  };
  const snapshot = () => ({ state, error, calls: [], items, console: consoleLines });
  g.__cm_snapshot = () => JSON.stringify(snapshot());
  g.__cm_poll = () => JSON.stringify(state === "running"
    ? { state, calls: pending.splice(0) }
    : { ...snapshot(), store: state === "done" ? storeWrites : null });
  g.__cm_resolve = (id, ok, payload) => {
    const w = waiters.get(id); if (!w) return;
    waiters.delete(id);
    const value = JSON.parse(payload);
    if (ok) w.resolve(value);
    else w.reject(new Error(typeof value === "string" ? value : JSON.stringify(value)));
  };
})();
"""
