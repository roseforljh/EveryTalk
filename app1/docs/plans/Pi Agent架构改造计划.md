# EveryTalk Android Pi Agent 架构改造计划

> 状态：实施中，统一运行、上下文主链和旧循环清理已完成  
> 记录日期：2026-08-13  
> 适用工程：app1/  
> 参考项目：earendil-works/pi  
> 参考提交：6f707eb36064e82af9c1320a7634f4dfad21049b  
> 参考包：@earendil-works/pi-agent-core 0.84.1  
> 许可证：MIT  
> 改造性质：Android 本地架构重构，不新增 EveryTalk 后端

## 一、结论

Pi 已经包含成熟的 Agent Loop、逐次模型请求消息、工具结果回填、上下文转换、上下文压缩和 Usage 汇总逻辑。

EveryTalk 适合吸收 Pi 的架构语义，并使用 Kotlin、Coroutine、Flow 和 Room 在 Android 内原生实现。Pi 当前使用 TypeScript，要求 Node.js 22.19.0 及以上。把 npm Runtime 塞入 Android 会增加体积、启动链路、进程通信、崩溃面和维护成本，因此本计划不直接运行 Pi 包。

本次改造的核心结果：

1. 四个 Provider Client 内的四套工具循环合并为一套 Agent Loop。
2. 每次 Agent 主循环模型调用形成一条独立内部 Assistant 记录；所有真实上游请求都形成独立 AgentRequest 和独立 Usage。
3. 每次模型调用前统一执行上下文组装、预算、裁剪和压缩。
4. 用户界面继续只显示一条 AI 消息，执行过程按真实发生顺序投影。
5. 当前上下文、一次 Agent 运行消耗、整个会话历史消耗使用三套独立口径。
6. Computer Tool、SSH、Workspace、服务器权限档位和现有交互继续保留。
7. Android 被回收、网络中断或审批暂停后，依靠 Room 中的 Agent 状态恢复，禁止盲目重放状态未知的写操作。

一句话定义：

> Pi 提供 Agent 的运行语义，EveryTalk 使用 Android 原生代码实现同等语义，并保留自己的 Provider、Computer 和界面体系。

### 1.3 当前实施进度（2026-08-14）

已进入代码实施，当前文本主链已经完成：

1. 普通聊天、MCP、联网搜索、VPS Agent 全部进入同一个 Kotlin `AgentLoop`。
2. 四种协议只通过 `streamSingleTurn` 执行单次模型请求，工具循环和上下文推进由 AgentLoop 负责。
3. Room 17 已保存 Run、Entry、Request、Usage、ContextSnapshot、Compaction 和 Provider continuation。
4. 上下文按完整工具原子组重建，压缩保留近期原文，支持超长单轮切分。
5. 文本发送前的旧 `AutoContextCompression` 已退出正式路径，避免旧链与 AgentLoop 双重压缩；图片路径只保留窗口裁剪。
6. OpenAI Responses、Anthropic、Gemini 和 OpenAI 兼容推理内容的当前 Run 连续状态已接入。
7. 当前请求、当前 Run、整个会话三套 Usage 已分开。
8. 工具结果统一限制大小；用户取消会收尾活动请求；App 重启会把旧活动 Run 和 Request 标为 `INTERRUPTED`。
9. SSE 已设置 30 分钟总上限、2 分钟 Socket 空闲上限和 120 秒首个有效事件上限。

当前保留项：

1. 审批中的 Run 目前按中断封存，尚未实现跨进程继续等待同一张审批卡片。
2. UNKNOWN 写工具禁止自动重放，尚未实现用户选择后的恢复执行界面。
3. Harness v2/v3 属于 Pi 实验路线，本次没有引入。

### 1.1 与现有计划的关系

* [给AI一个服务器.md](./给AI一个服务器.md) 继续定义服务器、Workspace、SSH、Container 和用户交互。本文只调整调用这些能力的 Agent 运行内核。
* [参考Hermes的模型与上下文能力改进计划.md](./参考Hermes的模型与上下文能力改进计划.md) 中的模型能力、Token 估算和渠道适配继续作为现有基础。
* Hermes 计划中“完整 Agent Loop 不在当前范围”的旧边界由本文覆盖。涉及 Agent 多轮请求、Usage、上下文压缩和工具循环时，以本文为准。
* 三份文档发生冲突时，服务器产品行为以服务器计划为准，模型能力来源以 Hermes 计划为准，Agent 运行和上下文口径以本文为准。

### 1.2 与输入框 Agent 开关的关系

AgentLoop 是内部统一运行内核，不代表输入框中的 Agent 功能始终开启。

| 用户开启的能力 | AgentLoop 获得的工具 |
| --- | --- |
| 全部关闭 | 工具列表为空，只运行一次模型请求 |
| MCP | 当前会话选择的 MCP Tool |
| 联网搜索 | 当前搜索工具或 Provider 原生搜索能力 |
| Agent | 当前会话选择服务器的 Computer Tool |
| 多项同时开启 | 合并对应工具，并通过同一 Tool Registry 调度 |

因此普通聊天、MCP、联网搜索和 VPS Agent 共享同一套请求、Usage 和上下文逻辑。Agent 开关仍只控制服务器能力及其标签、长按选服交互。

## 二、为什么当前架构必须调整

### 2.1 已确认的 Token 口径错误

第二次 Agent 运行的日志中，界面收到的输入 Token 如下：

| Agent 请求序号 | 当前日志写入的“输入” | 从累计值还原出的本次真实输入 |
| --- | ---: | ---: |
| 1 | 11,251 | 11,251 |
| 2 | 22,607 | 11,356 |
| 3 | 34,534 | 11,927 |

第二轮真实输入：

    22,607 - 11,251 = 11,356

第三轮真实输入：

    34,534 - 22,607 = 11,927

每轮实际上下文只小幅增长，增长内容来自本轮新增的 Assistant 工具调用和 Tool Result。这属于正常 Agent 行为。日志中的 22,607 和 34,534 是多次请求相加后的消费总量，不能代表某一次请求占用了多少上下文。

当前错误链路：

    Provider 本次 Usage
        ↓
    TokenUsageAccumulator 按可见 AI 消息累计所有 Agent 请求
        ↓
    MessageTokenUsageStore 写入 message.tokenUsage
        ↓
    AiContextUsagePopup 把累计值显示成“输入”
        ↓
    totalConversationTokenUsage 再累计所有 AI 消息
        ↓
    会话历史消费被拿去和单次模型上下文窗口比较

因此用户看到会话 Token 达到 1,236,763，并和 1,050,000 的上下文窗口并列。两个数字的统计范围不同，进度条没有有效含义。

### 2.2 请求内容没有发现递归嵌套

现有请求会携带：

1. 原始会话历史。
2. 当前 Agent 运行已经产生的 Assistant 工具调用。
3. 与工具调用配对的 Tool Result。

这些内容会让后续请求逐步变大，模型必须看到工具结果才能继续判断。当前证据没有显示“把上一轮完整 HTTP Payload 再塞进下一轮 Payload”的递归嵌套。

真正的问题集中在两处：

1. 多次请求的 Usage 被累计到同一个 message.tokenUsage。
2. Provider Client 同时负责请求、流解析、工具执行、上下文延续和循环终止，职责混在一起，难以证明每轮口径正确。

### 2.3 已确认的无限等待

日志显示某次模型流在 21:35:30 建立连接，到 21:45:57 用户手动停止前，没有收到内容、Usage 或错误。

当前配置：

    NETWORK_SSE_REQUEST_TIMEOUT_MS = Long.MAX_VALUE
    NETWORK_SSE_SOCKET_TIMEOUT_MS = Long.MAX_VALUE

连接成功后，只要上游保持连接却不返回有效事件，Agent 就会永久等待。统一 Agent Loop 必须同时接管首个有效事件超时、流空闲超时、取消和有限重试。

### 2.4 四套工具循环已经产生结构性重复

以下客户端都包含自己的循环、工具历史格式、执行顺序和结束判断：

* OpenAIDirectClient.kt
* OpenAIResponsesClient.kt
* AnthropicDirectClient.kt
* GeminiDirectClient.kt

继续在四个文件中分别修 Usage、压缩、审批、恢复和超时，会让同一问题出现四种行为。统一循环是修复根因的必要条件。

## 三、采用 Pi 的范围

### 3.1 直接采用的设计语义

| Pi 设计 | EveryTalk 对应实现 |
| --- | --- |
| Agent Loop 统一驱动多轮模型与工具 | Kotlin AgentLoop |
| 每次模型响应是一条独立 AssistantMessage | 每个 AgentRequest 对应独立 AgentEntry.Assistant |
| Tool Result 是独立消息 | AgentEntry.ToolResult |
| 每次调用前执行 transformContext | AgentContextManager.prepareRequest |
| transform 后执行 convertToLlm | Provider Transport 协议映射 |
| 摘要加最近完整消息重建有效上下文 | AgentCompactionEntry 加 retained tail |
| Tool Call 与 Tool Result 保持配对 | Context 原子组规则 |
| Usage 保存在各次 Assistant 响应上 | AgentRequestUsage 一次请求一行 |
| Usage 总量另行汇总 | Run 与 Conversation 查询聚合 |
| Agent 事件按生命周期发出 | AgentEvent 流映射到现有执行过程 UI |

### 3.2 按 Android 场景改写的部分

1. TypeScript Promise 和 AsyncIterable 改为 Kotlin suspend、Flow 和结构化并发。
2. Pi 的会话文件改为 Room 事务。
3. Pi 的 CLI 事件改为 EveryTalk 的一条可见 AI 消息和执行过程投影。
4. Pi 工具钩子接入 EveryTalk 的手动批准、智能批准、完全批准。
5. Pi 的本机工具语义接入 EveryTalk Computer Tool、SSH、Workspace 和现有幂等执行记录。
6. 增加 Android 进程死亡、锁屏、断网和 Activity 重建后的恢复规则。
7. Provider 原生连续状态只作为优化缓存，中立内部消息始终保留。

### 3.3 明确不采用的部分

1. 不引入 Node.js、Bun、npm Runtime 或 JavaScript Bridge。
2. 不复制 Pi 的 CLI、终端界面和文件会话格式。
3. 不替换 EveryTalk 的 Provider 配置、模型参数和渠道体系。
4. 不把 SSH 凭据、会话或 Agent 状态上传到新后端。
5. 不在本次改造中加入子 Agent、技能市场或远程控制面。

## 四、目标架构

~~~mermaid
flowchart TD
    UI["聊天 UI<br/>一条可见 AI 消息"] --> Controller["MessageSender / ApiHandler<br/>启动、停止、UI 投影"]
    Controller --> Loop["AgentLoop<br/>唯一多轮调度器"]

    Loop --> Context["AgentContextManager<br/>组装、预算、裁剪、压缩"]
    Loop --> Tools["AgentToolRuntime<br/>注册、审批、执行、结果归一化"]
    Loop --> Store["AgentRunStore<br/>Room 事务与恢复"]
    Loop --> Transport["ModelTurnTransport<br/>一次模型请求"]

    Transport --> OpenAIChat["OpenAI Chat"]
    Transport --> OpenAIResponses["OpenAI Responses"]
    Transport --> Anthropic["Anthropic"]
    Transport --> Gemini["Gemini"]

    Tools --> MCP["MCP / 联网搜索"]
    Tools --> Computer["Computer Tool<br/>SSH / Workspace / Container"]

    Store --> Room["Room<br/>Run / Entry / Request / Usage / Context / Compaction"]
    Loop --> Events["AgentEvent Flow"]
    Events --> Controller
~~~

### 4.1 各层唯一职责

| 层 | 只负责什么 | 禁止承担什么 |
| --- | --- | --- |
| MessageSender / ApiHandler | 启动运行、取消、把 AgentEvent 投影到界面 | 自己计算工具循环、累计上下文 |
| AgentLoop | 状态机、请求轮次、工具顺序、结束条件 | 拼接四家 Provider JSON |
| AgentContextManager | 生成本次有效上下文、预算、压缩、快照 | 发网络请求、操作 UI |
| AgentToolRuntime | 找工具、预检权限、执行、生成统一结果 | 修改模型上下文窗口 |
| ModelTurnTransport | 编码一次请求、解析一次响应流 | 执行工具、继续下一轮 |
| AgentRunStore | 用事务保存状态和恢复信息 | 决定业务策略 |
| UI 投影 | 展示一条回答、过程和统计 | 作为新架构的上下文事实来源 |

## 五、统一 Agent Loop

### 5.1 状态

    CREATED
      ↓
    PREPARING_CONTEXT
      ├─ 需要压缩 → COMPACTING_CONTEXT → PREPARING_CONTEXT
      ↓
    WAITING_MODEL
      ↓
    STREAMING_MODEL
      ├─ 无工具调用 → COMPLETED
      └─ 有工具调用
             ↓
       CHECKING_PERMISSION
             ├─ 需用户决定 → WAITING_APPROVAL
             ├─ 拒绝 → 写入拒绝 Tool Result
             └─ 通过
                    ↓
              EXECUTING_TOOL
                    ↓
              PERSISTING_RESULT
                    ↓
              PREPARING_CONTEXT

    任意活动状态
      ├─ 用户停止 → CANCELLED
      ├─ 可说明的失败 → FAILED
      ├─ 满足安全重试条件 → RETRYING → WAITING_MODEL
      └─ 进程消失 → INTERRUPTED 或 WAITING_APPROVAL

同一 AgentRun 可以有多个 AgentRequest。每个 AgentRequest 只允许调用 Provider 一次。没有开启任何工具的普通聊天也创建 AgentRun，正常情况下只有一个 purpose=AGENT_TURN 的 AgentRequest。

### 5.2 标准事件顺序

一次正常运行的事件顺序：

    RunStarted
    RequestPreparing(ordinal=1)
    ContextPrepared
    ModelRequestStarted
    AssistantStarted
    ReasoningDelta / TextDelta / ToolCallDelta
    AssistantCompleted
    RequestUsageFinalized

    ToolApprovalRequested       可选
    ToolApprovalResolved        可选
    ToolExecutionStarted
    ToolExecutionUpdated        可选
    ToolExecutionCompleted
    ToolResultPersisted

    RequestPreparing(ordinal=2)
    ...
    RunCompleted

硬性顺序：

1. AssistantCompleted 必须先于该 Assistant 中任一 Tool Call 的执行。
2. ToolExecutionStarted 前必须把调用参数、审批结果和状态写入 Room。
3. ToolExecutionCompleted 后必须先保存 Tool Result，再允许下一次模型请求。
4. RequestUsageFinalized 只更新当前 requestId，不覆盖其他请求。
5. RunCompleted 只能在最后一次 Assistant 没有待执行工具时发生。

### 5.3 循环伪代码

~~~kotlin
suspend fun run(runId: String) {
    while (true) {
        val prepared = contextManager.prepareRequest(runId)
        val request = runStore.createRequest(runId, prepared.snapshot)
        val assistant = transport.streamTurn(request, prepared.context)
            .persistAsIndependentAssistant(request.id)

        if (assistant.toolCalls.isEmpty()) {
            runStore.completeRun(runId)
            return
        }

        for (toolCall in assistant.toolCalls) {
            val result = toolRuntime.executeWithApproval(runId, request.id, toolCall)
            runStore.appendToolResult(runId, request.id, toolCall.id, result)
        }
    }
}
~~~

这段伪代码只表达职责边界。正式实现还要处理取消、超时、循环上限、Provider 错误、部分输出和进程恢复。

### 5.4 循环边界

必须设置以下保护：

| 项目 | 初始规则 |
| --- | --- |
| 单次 Agent 最大模型请求数 | 由现有工具循环上限迁入统一常量 |
| 单次 Agent 最大连续工具调用数 | 统一常量，达到后给模型生成明确终止结果 |
| 相同 Tool Call 重复 | 以 toolCallId 加请求哈希命中已有结果 |
| 相同参数死循环 | 连续重复达到阈值后终止，并把原因显示给用户 |
| 没有文本但 finish 正常 | 允许工具轮；最终轮为空则报“模型未返回内容” |
| Provider 返回未知 finish reason | 保存原值，映射为可解释的失败或完成状态 |

## 六、Provider 单次请求适配层

### 6.1 统一接口

~~~kotlin
interface ModelTurnTransport {
    val protocol: ProviderProtocol

    fun streamTurn(
        request: ModelTurnRequest,
    ): Flow<ModelTurnEvent>
}
~~~

网络流直接继承 AgentRun 父 Coroutine 的取消，不新增一套取消信号。

ModelTurnRequest 至少包含：

| 字段 | 作用 |
| --- | --- |
| requestId | 唯一标识本次真实模型请求 |
| runId | 所属 Agent 运行 |
| ordinal | 该运行中的请求序号 |
| purpose | AGENT_TURN 或 COMPACTION |
| configSnapshot | 渠道、端点、模型和参数快照 |
| systemPrompt | 当前有效系统提示 |
| messages | Provider 中立消息 |
| tools | 当前公开的工具定义 |
| outputLimit | 本次最大输出 |
| continuationState | 可选的 Provider 原生连续状态 |

ModelTurnEvent 至少包含：

* ConnectionEstablished
* FirstResponseReceived
* AssistantStarted
* ReasoningDelta
* TextDelta
* ToolCallDelta
* ToolCallCompleted
* UsageUpdated
* UsageFinal
* NativeContinuationUpdated
* Finish
* Failure

### 6.2 Provider Client 改造规则

四个现有 Client 最终只保留：

1. 将 Provider 中立消息编码为本渠道协议。
2. 发起一次 HTTP 流请求。
3. 将流式响应解析为 ModelTurnEvent。
4. 归一化本次请求的 Usage。
5. 返回 finish reason 和原生连续状态。

从四个 Client 移出的逻辑：

* while 工具循环。
* 工具执行。
* 权限弹窗。
* 下一轮工具历史拼接。
* 多轮 Usage 累计。
* 上下文压缩触发。
* Agent 完成判断。

### 6.3 四种协议映射

| 中立内容 | OpenAI Chat | OpenAI Responses | Anthropic | Gemini |
| --- | --- | --- | --- | --- |
| User | role=user | input item | role=user | user content |
| Assistant 文本 | role=assistant | output_text item | text block | model part |
| Assistant 推理 | 支持时映射渠道字段 | reasoning item | thinking block | thought part |
| Tool Call | assistant.tool_calls | function_call item | tool_use block | functionCall part |
| Tool Result | role=tool | function_call_output | tool_result block | functionResponse part |
| System | system/developer | instructions | system | systemInstruction |

Provider 转换必须满足：

1. 中立消息不保存渠道专属 JSON。
2. 渠道专属 JSON 只存在于请求快照、调试摘要或 continuationState。
3. 切换 Provider 后，历史可以从中立消息重新编码。
4. Tool Call ID 在转换前后保持稳定。
5. 不支持的推理块可以过滤或转成受控文本，禁止丢失 Tool Call 和 Tool Result。

Provider 在服务端执行、App 无法接管的原生工具继续由 Transport 配置和解析。它们产生的状态、结果和 Usage 映射为统一事件，但不进入本地 Tool Executor。

## 七、内部消息、可见消息和请求 Payload

### 7.1 三层数据

    Room 可见 Message
        用于聊天列表、用户编辑、重生成和最终回答

    AgentRun + AgentEntry
        用于完整保存每次 Assistant、Tool Call、审批和 Tool Result

    Provider Payload
        每次请求临时从中立上下文生成

可见 Message 是界面投影。新 Agent 运行的真实上下文来源为可见用户消息、旧版普通 AI 消息和 AgentEntry。已经关联 AgentRun 的可见 AI 消息不能再次进入请求，否则最终回答会和内部 Assistant 记录重复。

### 7.2 AgentEntry 内容

AgentEntry 使用密封类型，至少支持：

| 类型 | 是否发给模型 | 内容 |
| --- | --- | --- |
| Assistant | 是 | 按原始顺序保存 reasoning、text、toolCall 块 |
| ToolResult | 是 | toolCallId、统一结果、错误标志、截断信息 |
| ApprovalRequest | 否 | 权限说明、风险理由、待审批参数 |
| ApprovalDecision | 否 | 通过、拒绝、决策时间和权限档位 |
| Status | 否 | 等待、恢复、取消等界面状态 |

用户消息继续保存在 messages 表。AgentRun 保存触发它的 userMessageId。

### 7.3 会话重建规则

AgentContextManager 按可见消息顺序重建中立上下文：

1. 用户消息直接加入。
2. 没有关联 AgentRun 的旧 AI 消息直接加入。
3. 关联 AgentRun 的 AI 消息展开为该 Run 中可发给模型的 Assistant 和 ToolResult。
4. ApprovalRequest、ApprovalDecision 和纯 Status 被过滤。
5. 当前 Run 已完成的 AgentEntry 只追加一次。
6. 未完成的部分 Assistant 默认不进入下一次请求，恢复处理完成后再决定。

必须为每个中立项生成稳定 TranscriptItemId：

    message:{messageId}
    agent:{agentEntryId}

上下文压缩检查点使用该 ID 表示“摘要覆盖到哪里”，避免混用可见消息序号和 Agent 内部序号。

### 7.4 防重复不变量

每次请求保存 payloadFingerprint。测试中必须验证：

1. 同一个 messageId 或 agentEntryId 在一次 Payload 中最多出现一次。
2. ToolResult 只能跟随匹配的 Tool Call。
3. 已展开 AgentRun 后，不再加入它的可见 AI 投影。
4. 当前请求不能包含自己的未完成 Assistant。
5. 重试使用相同上下文快照时，指纹一致。

## 八、Tool Registry、权限和执行顺序

### 8.1 统一工具入口

AgentToolRuntime 统一管理：

* MCP Tool。
* 联网搜索。
* Computer Tool。
* Provider 原生工具的结果桥接。

每个工具描述包含：

| 字段 | 作用 |
| --- | --- |
| name | 稳定工具名 |
| schema | 发给模型的参数定义 |
| source | MCP、WEB、COMPUTER、PROVIDER |
| executor | 实际执行入口 |
| resultNormalizer | 转成统一 Tool Result |
| approvalPolicy | 是否进入权限预检 |
| outputPolicy | 最大回传量和截断方式 |

现有 ComputerToolExecutor 继续作为七个 Computer Tool 的实际执行入口。AgentToolRuntime 只负责统一调度，不重写 SSH、Workspace、Container 和幂等执行能力。

### 8.2 执行顺序

第一版统一采用模型返回顺序串行执行：

1. 保持操作顺序可预测。
2. 避免多个写操作同时修改 VPS。
3. 审批卡片与 Tool Result 顺序稳定。
4. Provider 切换后行为一致。

未来若增加并行执行，只允许经过明确标记、互不依赖的只读工具。本计划不实施该扩展。

### 8.3 统一权限预检

每次工具执行前调用：

~~~kotlin
suspend fun beforeToolCall(
    run: AgentRun,
    call: AgentToolCall,
): ToolPermissionDecision
~~~

返回值：

* Execute
* WaitForUser
* Reject

三档服务器权限沿用现有产品定义：

| 权限档位 | 行为 |
| --- | --- |
| 手动批准 | 继续使用现有 ComputerHostCommandPolicy 判断，命中需批准操作时展示卡片 |
| 智能批准 | 模型通过 ask_user_approval 表达是否申请；需要时展示卡片，其余操作直接执行 |
| 完全批准 | 所有 Computer 操作直接执行；用户选择该档位时只警告一次 |

权限判断发生在统一 Loop，Provider 只负责传回 Tool Call。审批结果必须先写入 AgentEntry，再开始执行。

### 8.4 审批暂停

等待用户时：

1. Run 状态变为 WAITING_APPROVAL。
2. ApprovalRequest 持久化。
3. 当前网络请求已经结束，不占用 SSE 连接。
4. App 进程被回收后，重新进入会话仍能恢复审批卡片。
5. 用户拒绝后，生成结构化 Tool Result 告诉模型“用户拒绝”，再由 Agent 决定是否继续。
6. 同一个 approvalRequestId 只能决策一次。

### 8.5 幂等与未知状态

Computer Tool 已有 toolCallId、请求哈希和 ComputerExecution 记录，继续作为执行幂等基础。

统一规则：

1. 相同 toolCallId 和相同请求哈希直接读取已有结果。
2. 相同 toolCallId 和不同请求哈希视为冲突。
3. 执行前状态为 QUEUED，真正发出命令后为 RUNNING。
4. Android 中断且无法确认结果时标为 UNKNOWN。
5. UNKNOWN 的写操作禁止自动重放。

## 九、Token 与上下文的严格口径

### 9.1 逐请求字段

| 字段 | 定义 | 是否用于上下文窗口 |
| --- | --- | --- |
| promptTokens | 本次请求实际完整输入，包括系统提示、历史、工具定义和当前工具历史 | 是 |
| freshInputTokens | 本次没有命中缓存的输入 | 否，只用于消费说明 |
| cacheReadTokens | 本次从提示缓存读取的输入 | 否，是 promptTokens 的组成或渠道拆分项 |
| cacheWriteTokens | 本次写入提示缓存的输入 | 否，是渠道拆分项 |
| outputTokens | 本次 Assistant 输出 | 是，完成后计入实际占用 |
| reasoningTokens | 本次推理 Token | 依渠道定义保存，作为 outputTokens 子项时禁止重复相加 |
| requestTotalTokens | 本次规范化总量，通常为 promptTokens 加 outputTokens | 否，用于本次消费 |
| providerTotalTokens | Provider 原始 total 字段，可空 | 否，用于核对 |

不同 Provider 对缓存字段的定义不同。归一化层必须明确每个字段是否已经包含在 input 中，禁止直接把 input、cacheRead 和 cacheWrite 无条件相加。

统一归一化规则：

1. promptTokens 永远表示本次完整逻辑输入。
2. Provider 的 input 已经包含缓存 Token 时，freshInputTokens 等于 input 减 cacheReadTokens。
3. Provider 将新输入、缓存读取和缓存写入分开报告时，promptTokens 由三项按该 Provider 官方口径合成。
4. reasoningTokens 已包含在 outputTokens 时只做明细，requestTotalTokens 禁止再次相加。
5. 保留 rawUsageJson，新增渠道字段时可以重新核对，不能靠猜测修正历史值。

### 9.2 三个用户可见总量

| 名称 | 计算方式 | 用途 |
| --- | --- | --- |
| activeContextTokens | 最近一次 purpose=AGENT_TURN 请求的 promptTokens 加已生成 outputTokens | 与模型上下文窗口比较 |
| runTotalTokens | 当前 AgentRun 所有实发 AgentRequest 的 requestTotalTokens 之和，包含压缩请求 | 展示本次任务消耗 |
| conversationLifetimeTokens | 当前会话所有新架构请求的 requestTotalTokens 之和，加可识别的旧版历史消费 | 展示历史总消耗 |

只有 activeContextTokens 可以出现在上下文进度条分子。

发送前还需要一个估算值：

    estimatedActiveContextTokens
      = estimatedPromptTokens + reservedOutputTokens

它用于判断能否发送和是否压缩。收到最终 Usage 后，界面切换到实测 activeContextTokens，并保留估算差值用于后续校准。

### 9.3 逐请求保存

每个 AgentRequest 都有独立 AgentRequestUsage。例子：

| requestId | ordinal | promptTokens | outputTokens |
| --- | ---: | ---: | ---: |
| req-1 | 1 | 11,251 | 该轮真实输出 |
| req-2 | 2 | 11,356 | 该轮真实输出 |
| req-3 | 3 | 11,927 | 该轮真实输出 |

禁止再把三次输入写成 34,534 后放进某一条 message.tokenUsage。

### 9.4 缺少最终 Usage

请求取消、断网或 Provider 不返回 Usage 时：

1. 保存发送前估算。
2. Usage 状态标为 ESTIMATED、PARTIAL 或 UNKNOWN。
3. 会话历史总消耗只累计有明确口径的字段。
4. UI 明确显示“估算”，禁止伪装成实测。
5. 未知数据不参与下一次估算校准。

## 十、上下文管理

### 10.1 每次模型请求前都执行

Pi 的 transformContext 会在每次模型调用前运行。EveryTalk 对应流程：

    读取中立会话
        ↓
    展开历史 AgentRun，过滤 UI-only 记录
        ↓
    注入系统提示、当前模型配置、工具定义
        ↓
    校验 Tool Call 与 Tool Result 配对
        ↓
    应用有效压缩检查点
        ↓
    估算本次完整输入
        ↓
    超阈值时压缩或裁剪
        ↓
    Provider 协议转换
        ↓
    保存 AgentContextSnapshot
        ↓
    发起请求

这样可以覆盖同一 AgentRun 内第 2、3、4 次模型请求。当前逻辑主要围绕可见消息发送前处理，无法可靠覆盖 Provider Client 内部自行进行的每轮循环。

### 10.2 完整请求估算

估算必须包含：

* System Prompt。
* 可见用户历史。
* 展开的内部 Assistant。
* Tool Call 参数。
* Tool Result。
* 图片和附件。
* 本次公开的工具 schema。
* Provider 协议开销。
* 原生连续状态带来的已知输入。
* 预留输出 Token。

RequestTokenEstimator 可以继续复用，但入口迁入 AgentContextManager。所有 Provider 通过同一个估算结果进入预算判断。

### 10.3 实测校准

每个配置维度维护轻量校准值：

    calibration = measuredPromptTokens - estimatedPromptTokens

键至少包含：

* Provider 协议。
* 规范化端点。
* 模型 ID。
* 工具 schema profile。

只使用最终实测 Usage 更新校准。使用平滑后的近期误差，禁止把一次异常值永久叠加到所有请求。

## 十一、通用压缩和 Provider 原生压缩

### 11.1 通用压缩是主路径

中立会话和通用压缩检查点是可恢复事实来源：

    系统提示
    + 较早上下文摘要
    + 最近完整消息
    + 当前 Agent 已完成工具链

压缩触发条件：

    estimatedPromptTokens + reservedOutputTokens
        >= contextWindowTokens × configuredThreshold

现有自动压缩开关和阈值继续使用。触发位置改到每次 AgentRequest 前。

### 11.2 压缩检查点

AgentCompactionEntry 保存：

* sessionId。
* configIdSnapshot。
* summary。
* summarizedThroughItemId。
* prefixFingerprint。
* retainedTail。
* tokensBefore。
* estimatedTokensAfter。
* 生成摘要的 Usage。
* 状态和时间。

应用检查点前重新计算 prefixFingerprint。历史被编辑、删除、重生成或换分支后，指纹不一致就废弃检查点并重建。

压缩请求的模型输出写入 AgentCompactionEntry，不创建对话中的 AgentEntry.Assistant，也不投影到 AI 正文。

### 11.3 Tool Call 与 Tool Result 是原子组

切分规则：

1. 不在 Tool Call 和对应 Tool Result 之间切断。
2. 不保留孤立 Tool Result。
3. 不把 Assistant 中多个 Tool Call 的一部分丢掉。
4. 一个工具执行失败也要保留对应 Tool Result。
5. 压缩摘要必须保留对后续有影响的文件、命令、端口、错误和未完成事项。

### 11.4 单轮本身过长

如果一个用户轮次内包含大量工具结果，完整保留该轮仍可能超出窗口。按以下顺序处理：

1. 先对每个 Tool Result 应用统一输出策略。
2. 仍超限时，从本轮内部寻找合法原子组边界。
3. 总结本轮较早部分。
4. 保留本轮最近的完整 Assistant 加 Tool Result 原子组。
5. 将“历史摘要”和“本轮前缀摘要”合并为明确分区。

这对应 Pi 对 split turn 的处理思路，可以解决几十次工具调用集中在同一用户消息内的情况。

### 11.5 Tool 输出控制

工具执行的完整原始结果和发给模型的结果分开：

| 数据 | 保存位置 | 规则 |
| --- | --- | --- |
| 完整结果 | 现有工具结果来源或 App 私有结果文件 | 流式写入，受单文件和会话保留上限控制 |
| 模型结果 | AgentEntry.ToolResult | 按统一 Token 预算截断 |
| UI 摘要 | executionTrace | 只显示必要状态和摘要 |

截断必须保留：

* 开头和结尾。
* exit code。
* stdout 与 stderr 是否被截断。
* 原始字符数或字节数。
* 获取完整结果的本地引用。

禁止让一次无边界的诊断命令输出挤掉整个会话上下文。

新增的 App 私有结果文件放在现有应用数据目录，不增加顶层工程目录。AgentEntry 只保存相对引用、大小、摘要和截断标志。删除会话时同步删除对应文件；文件写入失败时仍保存受控 Tool Result，不能让工具链因归档失败而丢失执行结果。

### 11.6 压缩失败边界

1. 摘要完整返回并持久化后，新的检查点才能生效。
2. 取消、断网、超时或空摘要不会覆盖上一个有效检查点。
3. 软阈值压缩失败时，使用上一个有效检查点和原子组安全裁剪继续组装。
4. 安全裁剪后仍超过硬窗口时，在发请求前明确失败，禁止发送已知超限的 Payload。
5. 压缩失败产生的 AgentRequest 和 Usage 仍然保留，状态标为 FAILED。

### 11.7 Provider 原生连续状态

OpenAI Responses 和 Anthropic 原生连续或压缩能力可以继续使用，但只属于可丢失优化。

ProviderContinuationState 的有效键包括：

* sessionId。
* configId。
* Provider 协议。
* 规范化端点。
* 模型。
* System Prompt 指纹。
* Tool schema 指纹。
* summarizedThroughItemId。

以下变化立即失效：

* 更换 Provider、端点或模型。
* 修改 System Prompt。
* 工具集合或 schema 变化。
* 编辑、删除、重生成历史。
* 通用压缩检查点变化。

失效后直接从中立消息重建请求，不能导致会话丢失。

## 十二、网络超时、取消和重试

### 12.1 超时分层

| 超时 | 初始值 | 说明 |
| --- | ---: | --- |
| 建连超时 | 30 秒 | DNS、TCP、TLS 和请求建立 |
| 首个有效事件超时 | 120 秒 | 从连接建立到首个内容、推理、工具或 Usage 事件 |
| 流空闲超时 | 90 秒 | 两个有效事件之间允许的最长空白 |
| 总流时长 | 不设固定上限 | 持续收到有效事件时允许长任务 |

纯心跳可以维持底层连接，但不能无限延长“没有任何模型进展”的等待。Provider 有明确排队事件时，可以映射为有效状态并显示给用户。

超时值集中放在 PerformanceConfig，不分散到四个 Client。

### 12.2 取消

用户点击停止后：

1. 取消 AgentRun 的父 Coroutine。
2. 取消当前 HTTP Flow。
3. 取消尚未开始的工具。
4. 已经发到 VPS 的工具按 ComputerExecution 状态处理。
5. 保存已收到的部分文本和推理，标记为 PARTIAL。
6. Run 进入 CANCELLED。
7. 禁止取消清理误删已经完成的 Tool Result。

### 12.3 自动重试边界

只允许在以下条件全部满足时自动重试一次：

1. 错误属于建连失败、可重试 5xx、429 或首包前断线。
2. 没有收到任何用户可见文本、推理或完整 Tool Call。
3. 本轮没有执行任何工具。
4. 取消信号没有触发。

已经出现部分输出、完整 Tool Call 或状态未知的工具后，禁止自动重试。此时向用户显示明确错误，避免重复回答或重复修改 VPS。

每次真正发到上游的尝试都创建独立 AgentRequest，并通过 retryOfRequestId 关联。

## 十三、Android 生命周期与恢复

### 13.1 持久化时机

以下状态必须立即落库：

* Run 创建。
* Request 创建和开始。
* Assistant 完整结束。
* Tool Call 完整解析。
* ApprovalRequest 创建。
* ApprovalDecision 完成。
* Tool 执行开始。
* Tool Result 完成。
* Compaction 完成。
* Run 完成、失败、取消或中断。

流式文字可以节流保存，AssistantCompleted 时必须强制写入最终内容。

### 13.2 App 重启后的处理

| 中断前状态 | 恢复行为 |
| --- | --- |
| PREPARING_CONTEXT | 标为 INTERRUPTED，允许用户重新发起 |
| WAITING_MODEL / STREAMING_MODEL | 保留部分输出，标为 INTERRUPTED，不接续旧 HTTP 流 |
| WAITING_APPROVAL | 恢复审批卡片 |
| Tool QUEUED 且确认未发出 | 可重新进入预检 |
| Tool RUNNING | 先查询 ComputerExecution 和 VPS 侧状态 |
| Tool UNKNOWN 且只读 | 明确标记后允许用户选择重试 |
| Tool UNKNOWN 且写操作 | 禁止自动重放，要求用户决定 |
| Tool 已完成但结果未回填 | 从幂等记录读取结果并补写 ToolResult |

### 13.3 恢复时的上下文规则

1. 只有 FINAL Assistant 和 FINAL ToolResult 默认进入下一次请求。
2. PARTIAL Assistant 保留给用户查看，不自动当作模型已完成回答。
3. UNKNOWN 工具必须先解决状态，不能伪造成功或失败结果。
4. 恢复动作本身写入 Status AgentEntry，便于界面解释发生了什么。

## 十四、Room 数据模型

### 14.1 新实体

当前数据库版本为 16。若实施期间没有其他迁移，目标版本为 17；若版本已经变化，则使用当时版本加一。

#### AgentRunEntity

| 字段 | 说明 |
| --- | --- |
| id | Run ID |
| sessionId | 会话 ID |
| userMessageId | 触发本次运行的用户消息 |
| visibleAssistantMessageId | UI 中唯一 AI 消息 |
| configIdSnapshot | 启动时配置 |
| status | 当前状态 |
| currentRequestOrdinal | 当前模型请求序号 |
| terminalReason | 完成、失败、取消原因 |
| createdAt / updatedAt | 时间 |

索引：

* sessionId
* visibleAssistantMessageId 唯一索引
* status

#### AgentEntryEntity

| 字段 | 说明 |
| --- | --- |
| id | Entry ID |
| runId | 所属 Run |
| sequence | Run 内严格递增序号 |
| kind | ASSISTANT、TOOL_RESULT、APPROVAL_REQUEST、APPROVAL_DECISION、STATUS |
| requestId | 来源请求，可空 |
| toolCallId | 工具关联，可空 |
| payloadJson | 对应密封类型内容 |
| status | STREAMING、FINAL、PARTIAL、UNKNOWN |
| createdAt / finalizedAt | 时间 |

约束：

* runId 加 sequence 唯一。
* toolCallId 建索引。
* 删除 Run 时级联删除 Entry。

#### AgentRequestEntity

| 字段 | 说明 |
| --- | --- |
| id | Request ID |
| runId | 所属 Run |
| ordinal | Run 内所有上游请求的严格递增序号 |
| purpose | AGENT_TURN 或 COMPACTION |
| modelTurnOrdinal | Agent 主循环轮次；压缩请求为空 |
| attempt | 同一逻辑轮的网络尝试序号 |
| retryOfRequestId | 重试来源，可空 |
| provider / endpoint / model | 本次真实配置快照 |
| payloadFingerprint | 中立有效上下文指纹 |
| status | PREPARED、STREAMING、COMPLETED、FAILED、CANCELLED |
| finishReason | Provider 结束原因 |
| startedAt / firstEventAt / finishedAt | 性能时间 |

约束：

* runId 加 ordinal 唯一，每次真实发往上游的请求都有自己的 ordinal。
* runId、status 建索引。

#### AgentRequestUsageEntity

以 requestId 为主键，一对一保存：

* promptTokens。
* freshInputTokens。
* cacheReadTokens。
* cacheWriteTokens。
* outputTokens。
* reasoningTokens。
* requestTotalTokens。
* providerTotalTokens。
* source。
* quality，取 MEASURED、ESTIMATED、PARTIAL、UNKNOWN。
* rawUsageJson，可选，用于兼容新 Provider 字段。

#### AgentContextSnapshotEntity

以 requestId 为主键，一对一保存：

* systemPromptTokens。
* conversationTextTokens。
* mediaTokens。
* toolSchemaTokens。
* protocolOverheadTokens。
* estimatedPromptTokens。
* reservedOutputTokens。
* contextWindowTokens。
* activeContextTokens。
* calibrationTokens。
* compactionId。
* transcriptFingerprint。
* source。

#### AgentCompactionEntryEntity

保存：

* id。
* sessionId。
* configIdSnapshot。
* summary。
* summarizedThroughItemId。
* prefixFingerprint。
* retainedTailJson。
* tokensBefore。
* estimatedTokensAfter。
* summaryRequestId，指向生成本次摘要的 AgentRequest。
* status。
* createdAt。

#### ProviderContinuationStateEntity

保存：

* id。
* sessionId。
* configId。
* provider、endpoint、model。
* systemPromptFingerprint。
* toolSchemaFingerprint。
* summarizedThroughItemId。
* opaqueStateJson。
* updatedAt。

该表保存渠道专属优化状态，随时允许删除和重建。

### 14.2 为什么不继续塞进 Message

一条可见 AI Message 可能包含十几次模型请求。继续向 Message 增加以下 JSON 会重复当前错误：

* 多次 Usage。
* 多份上下文快照。
* 多个 Provider continuation。
* 多个请求状态。

独立实体能按 requestId 保证一对一口径，也能在 App 中断后精确判断运行停在哪里。

### 14.3 旧数据迁移

迁移规则：

1. 保留 messages.tokenUsage、contextUsageSnapshot 和 contextCompressionState 原字段。
2. 不尝试从旧累计 Token 反推每次请求，历史记录缺少可靠边界。
3. 新版本运行只写新 Agent 表。
4. 旧消息统计标记为 LEGACY_CUMULATIVE。
5. 旧数据可以加入 conversationLifetimeTokens，但必须单独注明“旧版累计”。
6. 旧数据禁止用于 activeContextTokens。
7. 旧压缩状态在能够通过原指纹验证时可读取一次并迁移；无法验证就废弃，原聊天消息不删除。
8. 数据库迁移必须有 Room Migration Test，禁止 destructiveMigration。

## 十五、UI 投影

### 15.1 聊天消息

用户仍然看到：

* 一条用户消息。
* 一条对应的 AI 消息。
* AI 消息内一个可展开的完整执行过程。
* 最终回答正文。

AgentEntry.Assistant、ToolResult、Approval 和 Status 按 sequence 投影到 executionTrace，严格遵循真实顺序。禁止把所有思考集中显示后再显示所有工具。

同一会话同时只允许一个活动 AgentRun。运行期间继续沿用现有停止按钮，不在本计划中增加运行中插入新用户消息或 Pi 的 steering message。

### 15.2 运行中的状态

顶部折叠区域显示当前阶段，例如：

* 正在整理上下文。
* 正在等待模型。
* 正在执行 exec。
* 等待你的批准。
* 正在继续分析。

只由 AgentEvent 推进状态。收到 AssistantCompleted 但还有 Tool Call 时，停止按钮继续保留，状态不能看起来像已经结束。

### 15.3 上下文统计弹窗

弹窗改为三组：

#### 当前上下文

    12,300 / 1,050,000
    实测或估算

明细：

* 输入。
* 当前输出。
* 工具定义。
* 媒体。
* 缓存命中，作为输入明细展示，不额外加入进度条。

#### 本次 Agent

    3 次 Agent 模型请求
    0 次上下文压缩请求
    总输入消费
    总输出消费
    总 Token

#### 会话历史

    历史总请求数
    历史总 Token
    旧版累计数据，可选单列

不得再把 conversationLifetimeTokens 放进上下文窗口进度条。

## 十六、文件改造清单

### 16.1 新增文件

保持现有顶层目录，只在已有包内增加：

| 文件 | 职责 |
| --- | --- |
| data/agent/AgentModels.kt | AgentRun、AgentEntry、AgentEvent、状态和中立内容模型 |
| data/agent/AgentLoop.kt | 唯一 Agent 状态机 |
| data/agent/AgentContextManager.kt | 上下文重建、预算、裁剪、压缩和快照 |
| data/agent/AgentToolRuntime.kt | Tool Registry、权限预检、串行执行和结果归一化 |
| data/agent/AgentToolResultStore.kt | 大型工具结果的 App 私有文件写入、读取和会话级清理 |
| data/agent/AgentRunStore.kt | Room 事务、序号分配、恢复查询和聚合 |
| data/network/llm/ModelTurnTransport.kt | Provider 单次请求接口和事件 |
| data/database/entities/AgentEntities.kt | 七个新 Room Entity |
| data/database/daos/AgentDao.kt | Run、Entry、Request、Usage、Context、Compaction 查询 |

测试文件按现有测试目录放置，不新增顶层目录。

### 16.2 主要修改文件

| 文件 | 改动 |
| --- | --- |
| MessageSenderSendFlow.kt | 启动 AgentLoop，移除对 Provider 内部工具循环的依赖 |
| ApiHandler.kt | 接收统一 AgentEvent |
| ApiHandlerStreamProcessor.kt | 只做 UI 投影和流式缓冲 |
| OpenAIDirectClient.kt | 改为单次 OpenAI Chat Transport |
| OpenAIResponsesClient.kt | 改为单次 Responses Transport |
| AnthropicDirectClient.kt | 改为单次 Anthropic Transport |
| GeminiDirectClient.kt | 改为单次 Gemini Transport |
| AppStreamEvent.kt | 与 AgentEvent 建立清晰映射，移除 requestOrdinal 累计补丁 |
| RequestTokenEstimator.kt | 保留估算算法，由 AgentContextManager 统一调用 |
| AutoContextCompression.kt | 压缩能力迁入 AgentContextManager，旧入口仅做过渡 |
| MessageContextWindow.kt | 改为对中立 Transcript 原子组裁剪 |
| ContextUsageSnapshot.kt | 迁移到逐请求语义或由新实体取代 |
| ContextCompressionState.kt | 迁移到 AgentCompactionEntry 和 ContinuationState |
| ComputerToolExecutor.kt | 接收统一 ToolContext，保留执行和幂等逻辑 |
| ComputerHostCommandPolicy.kt | 由 beforeToolCall 统一调用 |
| ChatEntities.kt | 保留旧字段兼容，增加必要关联 |
| AppDatabase.kt | 注册新实体、DAO 和 Migration |
| AiContextUsagePopup.kt | 展示三套统计口径 |
| PerformanceConfig.kt | 增加分层 SSE 超时 |

### 16.3 最终删除或停用

完成全渠道迁移后：

* 删除 TokenUsageAccumulator.kt。
* 删除 MessageTokenUsageStore.kt。
* 删除四个 Client 内的工具循环和多轮历史拼接代码。
* 删除 totalConversationTokenUsage 作为上下文进度来源的逻辑。
* 删除 Long.MAX_VALUE 的 SSE 请求和 Socket 超时。
* 删除旧 AutoContextCompression 的重复运行入口。

旧数据类型可以保留反序列化能力，直到明确完成历史兼容周期。

## 十七、分阶段实施

每个阶段都能独立审查和回退，禁止一次性重写全部 Provider。

### 阶段 1：修正逐请求 Usage

当前状态：已完成。

实施：

1. 新增 AgentRequest、AgentRequestUsage 和 AgentContextSnapshot。
2. Provider 每次请求直接写独立 Usage。
3. UI 先切换到 active、run、lifetime 三套口径。
4. 旧 TokenUsageAccumulator 暂时只服务旧路径。

完成标准：

* 11,251、11,356、11,927 分别落在三条请求记录。
* 当前上下文显示第三次真实占用。
* 本次 Agent 显示三次之和。

回退点：

* 新表是增量结构，旧 message 字段仍在，可回退读取路径。

### 阶段 2：建立中立 Agent 模型和持久化

当前状态：已完成主链与 Room 17 持久化，界面仍兼容读取原 executionTrace。

实施：

1. 新增 AgentRun、AgentEntry、AgentRunStore。
2. 建立可见 Message 与 AgentRun 的一对一投影关联。
3. 先双写 executionTrace 和 AgentEntry。
4. 增加中立 Transcript 重建与防重复测试。

完成标准：

* 一条可见 AI 消息下能还原多次 Assistant 和 ToolResult。
* 重启后执行过程顺序不丢失。

回退点：

* UI 仍可读取原 executionTrace。

### 阶段 3：OpenAI Chat 接入统一 Loop

当前状态：已完成正式文本路径。

实施：

1. OpenAIDirectClient 改成单次 Transport。
2. AgentLoop 接管工具循环。
3. 接入 ContextManager 和 ToolRuntime。
4. 用内部开关保留旧路径，完成对比后删除。

完成标准：

* 文本、推理、工具、权限、停止和 Usage 全部经过统一事件。
* Payload 快照不存在重复历史。

回退点：

* 仅切回 OpenAI Chat 旧路径，不影响其他 Provider。

### 阶段 4：接入其余三种协议

当前状态：四种协议均已接入单次 Transport，Provider 连续状态定向检查通过。

顺序：

1. Gemini。
2. Anthropic。
3. OpenAI Responses。

每接入一个协议就完成该协议的文本、推理、工具、Usage、错误和取消测试，再迁移下一个。

完成标准：

* 四个 Client 都不再执行工具。
* 同一组中立消息在四种协议中保持 Tool 配对。

回退点：

* 每个 Provider 有独立迁移开关。

### 阶段 5：权限和所有工具统一

当前状态：工具 Registry、三档 Computer 权限和结果截断已进入统一调用路径。审批跨进程恢复未完成。

实施：

1. MCP、联网搜索和 Computer 接入 Tool Registry。
2. 三档服务器权限进入 beforeToolCall。
3. 审批请求和决定持久化。
4. 工具结果统一限制和截断。

完成标准：

* 权限等待不会重复执行工具。
* 工具顺序和 executionTrace 顺序一致。

回退点：

* ComputerToolExecutor 本体未重写，可以恢复旧调用入口。

### 阶段 6：上下文与压缩统一

当前状态：已完成。旧 `AutoContextCompression` 已退出正式发送路径，图片路径只保留窗口裁剪。

实施：

1. 每次 AgentRequest 前调用 AgentContextManager。
2. 通用压缩迁移到中立 Transcript。
3. Tool 原子组、split turn 和输出截断生效。
4. 接入 ProviderContinuationState。

完成标准：

* 同一 Run 的每一轮都执行预算。
* 压缩后没有孤立 Tool Call 或 Tool Result。
* 切换 Provider 后仍能继续会话。

回退点：

* 禁用新压缩时仍可使用安全裁剪和完整中立历史。

### 阶段 7：生命周期恢复

当前状态：已实现用户取消收尾和 App 重启封存；审批续接与 UNKNOWN 写工具的用户恢复流程未完成。

实施：

1. App 启动时扫描未终止 Run。
2. 恢复审批。
3. 对齐 ComputerExecution 幂等状态。
4. 部分 Assistant、UNKNOWN 工具和中断请求按规则收尾。

完成标准：

* 强制杀进程后没有工具重复执行。
* 已完成 Tool Result 可以补写。

回退点：

* 可以只把未终止 Run 标为 INTERRUPTED，关闭自动恢复细节。

### 阶段 8：清理旧循环

当前状态：已完成。

实施：

1. 删除四套重复循环。
2. 删除旧累计器和旧上下文进度来源。
3. 删除过渡双写。
4. 更新架构文档和代码注释。

实际完成：

1. 四家 Provider 的工具执行器、历史续接和内部多轮循环已从源码删除。
2. `TokenUsageAccumulator` 及旧测试已删除。
3. Provider 只保留单次 Transport、Payload 构建、SSE 解析、Usage 和错误处理。
4. 工具结果大小限制保留在统一 `AgentToolRuntime`，Provider 历史压缩函数及旧测试已删除。
5. Responses 与 Anthropic 的原生压缩检查点会随当前 Run 连续状态跨工具轮传递，并按已覆盖的内部 Assistant ID 去重。

完成标准：

* 全项目只有一个 Agent 工具循环入口。
* 全项目只有一个 activeContextTokens 计算入口。
* Provider Client 只执行单次请求。

## 十八、验证计划

### 18.1 单元检查

只覆盖非平凡公共逻辑：

* AgentLoop 状态迁移。
* Tool Call 与 Tool Result 配对。
* Transcript 展开去重。
* 三套 Token 聚合。
* 压缩切点。
* split turn。
* 权限暂停和恢复。
* 超时和取消。
* UNKNOWN 工具禁止自动重放。

### 18.2 Provider Payload 快照

使用同一组中立消息生成四份 Payload 快照，检查：

1. 基础历史只出现一次。
2. Assistant Tool Call 与 Tool Result 顺序正确。
3. Tool Call ID 一致。
4. System Prompt 和工具 schema 没有重复。
5. Provider 切换不会读取上一 Provider 的专属 JSON。

### 18.3 Room Migration Test

从版本 16 的真实 schema 升级，检查：

* 原会话和消息数量不变。
* 新表与索引完整。
* 旧 tokenUsage 仍可读取。
* 新运行能写入七类实体。
* 删除会话后相关 Agent 数据级联清理。

### 18.4 已知日志回归

固定回归样本：

| 检查项 | 期望 |
| --- | --- |
| 第 1 次 promptTokens | 11,251 |
| 第 2 次 promptTokens | 11,356 |
| 第 3 次 promptTokens | 11,927 |
| Run 请求数 | 3 |
| 当前上下文来源 | 第 3 次请求 |
| Run 总消耗 | 三次 requestTotalTokens 之和 |
| 会话总消耗 | 不进入上下文进度条 |

### 18.5 真机验收边界

自动检查只覆盖编译、单元逻辑、Payload 和 Room 迁移。真机上的以下行为由用户验收：

* 四家 Provider 的真实流式输出。
* Agent 多轮工具速度。
* 权限卡片交互。
* 锁屏、切后台和断网。
* SSH 工具实际状态恢复。
* 上下文弹窗显示。

实施过程中禁止为了验收卸载 App、清除 App 数据或覆盖用户真机安装。

## 十九、完成定义

以下条件全部满足才算架构改造完成：

1. 四种协议共用一个 AgentLoop。
2. Provider Client 每次只执行一次模型请求。
3. 每次模型请求有独立 Request、Usage 和 ContextSnapshot。
4. 一条可见 AI 消息可以投影完整内部执行链。
5. 当前上下文只取最近一次实际请求。
6. Run 总消耗和会话总消耗独立统计。
7. 每轮请求前都执行上下文预算。
8. 压缩保留摘要和最近完整上下文。
9. Tool Call 与 Tool Result 永远配对。
10. Tool 输出有统一边界，完整结果仍可本地查看。
11. 三档服务器权限全部经过统一 Tool 预检。
12. 审批暂停和 App 重启不会重复执行工具。
13. SSE 无有效事件时会在明确超时后结束。
14. Provider 切换后可以从中立消息重建上下文。
15. 旧聊天数据不丢失，旧累计 Usage 不再冒充当前上下文。
16. TokenUsageAccumulator 和四套 Provider 工具循环完成清理。

## 二十、风险与取舍

### 20.1 数据库结构增加

增加七类实体会提高迁移工作量，但能换来逐请求事实、可靠恢复和清晰统计。继续把数据塞入 Message JSON 会让多请求关系无法约束。

### 20.2 中立消息转换存在渠道差异

四家 Provider 对推理块、工具结果和缓存的定义不同。中立层保存公共语义，渠道独有字段保存在原始 Usage 或 continuationState，避免强行抹平差异。

### 20.3 通用压缩会产生额外模型请求

摘要本身有成本。该请求使用 purpose=COMPACTION 的独立 AgentRequest 和 AgentRequestUsage，计入 runTotalTokens 与 conversationLifetimeTokens，不增加 modelTurnOrdinal，也不占用 Agent 工具轮数。

### 20.4 Provider 原生连续状态可能失效

原生状态可以减少重复传输，但它受模型、工具和历史变化影响。中立消息始终保留，因此失效只影响性能，不影响会话恢复。

### 20.5 完整 Tool 输出和上下文容量冲突

模型无法无限接收命令输出。完整结果留在本地，模型接收带截断说明的受控结果，可以保住 Agent 连续工作所需的上下文。

### 20.6 重构期间双路径复杂

逐 Provider 迁移期间会短暂保留新旧路径。迁移开关只用于开发回退，阶段 8 必须删除，禁止长期维护两套 Agent Loop。

## 二十一、明确排除项

本计划不包含：

* EveryTalk 云端 Agent 后端。
* 用户 SSH 凭据同步。
* Pi npm Runtime 嵌入 Android。
* 子 Agent 和多 Agent 协作。
* 新的 VPS 资源额度限制。
* 修改服务器与会话的绑定关系。
* 重做现有服务器页面和权限卡片视觉设计。
* 自动重放结果未知的 VPS 写操作。
* 把完整隐藏思维链发送到界面。
* 运行中插入用户消息、steering message 和多 Run 并发。

## 二十二、Pi 固定参考

所有实现对照固定提交，避免主分支更新后语义漂移：

* Agent Loop：  
  https://github.com/earendil-works/pi/blob/6f707eb36064e82af9c1320a7634f4dfad21049b/packages/agent/src/agent-loop.ts
* Agent 类型与 transformContext、convertToLlm：  
  https://github.com/earendil-works/pi/blob/6f707eb36064e82af9c1320a7634f4dfad21049b/packages/agent/src/types.ts
* 上下文压缩实现：  
  https://github.com/earendil-works/pi/blob/6f707eb36064e82af9c1320a7634f4dfad21049b/packages/agent/src/harness/compaction/compaction.ts
* 压缩说明：  
  https://github.com/earendil-works/pi/blob/6f707eb36064e82af9c1320a7634f4dfad21049b/packages/coding-agent/docs/compaction.md
* Usage 汇总：  
  https://github.com/earendil-works/pi/blob/6f707eb36064e82af9c1320a7634f4dfad21049b/packages/coding-agent/src/core/usage-totals.ts
* OpenAI 请求适配参考：  
  https://github.com/earendil-works/pi/blob/6f707eb36064e82af9c1320a7634f4dfad21049b/packages/ai/src/api/openai-completions.ts
* MIT License：  
  https://github.com/earendil-works/pi/blob/6f707eb36064e82af9c1320a7634f4dfad21049b/LICENSE

## 二十三、审查时需要确认的决策

文档当前已经给出默认方案，审查重点集中在以下四项：

1. 接受“学习 Pi 语义，Kotlin 原生实现”，不嵌入 Node Runtime。
2. 接受新 Agent 表作为真实运行记录，Message 继续作为 UI 投影。
3. 接受当前上下文、本次 Agent、会话历史三套 Token 口径。
4. 接受按 Provider 分阶段迁移，全部完成后删除旧循环。

以上四项通过后，可以按第十七章顺序实施。
