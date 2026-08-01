# 参考 Hermes 的模型与上下文能力改进计划

> 状态：第一至第五阶段已完成，后续架构阶段待实施  
> 记录日期：2026-08-02  
> 首轮实施完成日期：2026-08-02  
> 对照项目：`NousResearch/hermes-agent`  
> 对照提交：`87bc710609f8b89b6e6b4aa418dde8ee30ec6873`  
> 对照版本：Hermes Agent `v0.19.1`，仓库发布版 `v2026.7.30`  
> 适用工程：`app1/`

## 一、目标

把 Hermes 中适合聊天客户端的模型能力管理、上下文预算、真实 token 用量、上下文恢复和工具 schema 控制思路吸收到 EveryTalk。

本计划优先解决五个直接影响用户的问题：

1. 模型的最大输出和上下文窗口不能长期依赖统一默认值。
2. 发送前的 token 估算必须覆盖完整请求，尤其是系统提示和工具 schema。
3. 各渠道返回的真实 usage 必须进入统一状态流，不能只写日志。
4. 输入历史过长和输出上限过大必须分开处理。
5. 用户需要看到上下文空间被哪些内容占用，以及当前数字来自实测还是估算。

后续再处理滚动摘要、MCP 工具渐进加载、全文搜索、长期记忆和辅助模型。完整 Agent Loop、子代理、凭据池和终端工具体系不在 EveryTalk 当前产品范围内。

## 二、调研基线与约束

### 2.1 Hermes 调研基线

本计划以固定提交为证据基线，避免 Hermes 主分支继续变化后无法复现结论。调研中重点核对了：

- `agent/model_metadata.py`：模型上下文、输出上限、端点探测、缓存和家族兜底。
- `agent/models_dev.py`：`models.dev` 社区目录的缓存与能力查询。
- `agent/context_breakdown.py`：系统提示、工具、规则、技能、记忆和对话的占用明细。
- `agent/context_compressor.py`、`agent/conversation_compression.py`：上下文压缩与摘要连续性。
- `tools/tool_search.py`：工具 schema 延迟公开、目录搜索和桥接调用。
- `agent/memory_manager.py`、`agent/usage_pricing.py`：记忆与 usage 相关通路。

### 2.2 EveryTalk 实施约束

- 保持 `LLMProvider` 和 `ProviderRegistry` 的现有路由，不绕开 Provider 层。
- 状态继续通过现有 `StateFlow`、ViewModel 和 Controller 分层流动。
- 不引入新的 tokenizer 依赖，首版使用可测试的本地估算器。
- 官方渠道的默认参数和模型限制以实施时的最新官方文档或官方端点为准，并记录核对日期。
- OpenAI 兼容渠道允许用户自由定义参数，不把某一家官方渠道的参数集合强加给兼容接口。
- `models.dev` 属于社区数据，只能作为低优先级回退来源。
- 不修改根目录的 README、许可证和更新日志。
- 不删除 Room 中的原始聊天记录。裁剪、摘要和软归档只影响请求组装或记录状态。
- 当前工作区正在进行模型参数和数据库改造。涉及 Room 迁移时必须以实施时已经合并的 schema 版本为起点，不能预设固定迁移版本号。

## 三、EveryTalk 当前基础与差距

| 能力 | 当前基础 | 主要差距 |
|---|---|---|
| 模型参数 | `ModelParameters.kt` 已支持思考参数、自定义参数、最大输出和上下文窗口 | 默认值仍是通用的 `4096 / 128000`，缺少逐模型能力来源和可信度 |
| 模型列表 | `ApiClient.fetchModels()` 兼容 Gemini、OpenAI 风格和常见反代格式 | 只保留模型 ID，丢弃上下文、最大输出、模态和能力字段 |
| 请求裁剪 | `MessageContextWindow.kt` 按完整对话轮次裁剪，保留系统消息和最新一轮 | 字符数直接当 token，英文明显高估，工具 schema 未计入，媒体统一估算为 4096 |
| 请求组装 | `MessageSenderSendFlow.kt` 已集中组装系统提示、历史和工具 | 当前先裁剪消息，后组装工具，裁剪阶段无法看到完整请求成本 |
| usage | OpenAI Chat 和 Responses 已读取部分缓存 token 字段 | `AppStreamEvent` 没有 usage 事件，真实输入、输出、缓存和推理 token 没有进入状态层 |
| 错误处理 | `ApiHandlerErrorController.kt` 会保留部分内容并完成状态收尾 | 上下文错误全部进入通用错误路径，输入过长和输出上限过大没有分类恢复 |
| MCP | 已有意图分类、候选排序和 Provider 工具循环 | `selectMcpCandidates()` 仍返回全部启用工具，完整 schema 会全部注入请求 |
| 历史记录 | Room 保存完整会话、消息、模型和渠道 | 缺少全文索引、摘要状态和长期记忆实体 |
| 提示缓存 | `PromptCachePolicy.kt` 会稳定排序工具 schema，并为官方 OpenAI 生成缓存键 | usage 未统一，无法量化缓存命中带来的真实收益 |

## 四、目标数据流

```text
用户配置与当前模型
        |
        v
模型能力解析器 <--- 官方端点 / 官方目录 / 本地缓存 / 社区回退
        |
        v
完整请求组装器 <--- 系统提示 + 历史消息 + 图片 + 工具 schema
        |
        v
上下文预算器 ---> 安全裁剪 / 后续滚动摘要
        |
        v
Provider 请求与流式解析
        |
        +----> 统一 Usage 事件 ---> 消息记录 / 会话统计 / 占用明细
        |
        +----> 结构化错误分类 ---> 输入裁剪重试 / 输出上限降级重试
```

数据流必须只有一个模型能力解析入口、一个请求成本估算入口和一个 usage 归一化结构，避免各 Provider 各自维护一套相互冲突的数字。

## 五、第一阶段：模型能力元数据

### 5.1 目标

建立逐渠道、逐端点、逐模型的能力结构，让最大上下文、最大输出、输入输出模态和推理能力都带有来源。

### 5.2 能力结构

首版最小字段：

```text
modelId
providerProtocol
endpointIdentity
contextWindowTokens
maxOutputTokens
inputModalities
outputModalities
supportsReasoning
source
sourceUpdatedAt
```

`source` 至少区分：

```text
USER_OVERRIDE
LIVE_ENDPOINT
OFFICIAL_CATALOG
LOCAL_CACHE
COMMUNITY_CATALOG
FAMILY_FALLBACK
```

缓存项必须保留原始来源和抓取时间。缓存自身不能提升数据可信度。

### 5.3 解析优先级

1. 用户对当前模型的明确覆盖值。
2. 当前 API 端点实时返回的模型能力字段。
3. EveryTalk 维护并注明官方来源和核对日期的逐模型目录。
4. 未过期的本地缓存，按缓存中记录的原始来源排序。
5. `models.dev` 社区目录。
6. 小型模型家族兜底表。
7. 全部缺失时使用保守默认值，并在界面明确标记为估算。

同名模型在不同端点可能有不同限制。缓存键必须至少包含渠道协议、规范化 API 地址和模型 ID，禁止只按模型 ID 缓存。

### 5.4 实施任务

- [x] 新增最小 `ModelCapability` 数据结构和来源枚举。
- [x] 把 `/models` 的公共解析逻辑从“只提取 ID”扩展为“保留可识别能力字段”。
- [x] 保留现有模型 ID 列表接口，避免一次性改动全部调用方。
- [x] 为 Gemini、Anthropic、OpenAI 官方渠道建立小型官方能力目录，实施时逐项核对最新官方文档。
- [x] OpenAI 兼容渠道不推断统一思考参数，仅采纳端点元数据、用户值或保守回退。
- [x] 给能力解析结果增加来源、更新时间和过期策略。
- [x] 参数对话框显示生效值及来源，用户填写的覆盖值始终优先。

### 5.5 主要涉及文件

- `app/src/main/java/com/android/everytalk/data/DataClass/ModelParameters.kt`
- `app/src/main/java/com/android/everytalk/data/network/core/ApiClient.kt`
- `app/src/main/java/com/android/everytalk/provider/ProviderRegistry.kt`
- `app/src/main/java/com/android/everytalk/ui/screens/settings/ModelParametersDialog.kt`
- 配置持久化相关 Entity、Converter、DAO，仅在确认现有 schema 后修改

### 5.6 验收标准

- [x] 同一个模型在两个端点返回不同窗口时，解析结果互不污染。
- [x] 用户覆盖值高于所有自动来源。
- [x] 官方渠道未返回能力字段时，能按官方目录、缓存、社区目录、家族兜底依次降级。
- [x] 界面能够区分“用户设置”“端点报告”“官方目录”“社区回退”“估算”。
- [x] 旧配置没有能力字段时仍可正常解析。
- [x] 单元测试覆盖来源优先级、缓存隔离、过期和旧数据兼容。

### 5.7 风险

- 代理端点可能返回虚假或复制自上游的元数据。用户覆盖值和来源展示可以降低误导风险。
- 官方模型规格会变化。目录项必须记录核对日期，不能复制 Hermes 的大规模硬编码表。

## 六、第二阶段：完整请求 token 估算

### 6.1 根因

`MessageContextWindow.kt` 当前把文本字符数直接计为 token。英文请求会明显高估，中文和代码也没有独立策略。`MessageSenderSendFlow.kt` 在工具列表组装前执行裁剪，MCP 和自定义工具的 JSON schema 完全没有进入预算。

### 6.2 实施方案

- [x] 新增单一 `RequestTokenEstimator`，供裁剪、占用明细和错误恢复共同使用。
- [x] 估算输入覆盖系统提示、全部消息角色、文本、图片或文件占位、工具 schema 和协议固定开销。
- [x] 首版采用分字符类型的保守估算：ASCII 文本按约四字符一个 token，CJK 文字按更高密度估算，JSON 和代码单独增加安全余量。
- [x] 工具 schema 先经 `PromptCachePolicy.normalizeTools()` 稳定化，再按序列化后的真实 JSON 估算。
- [x] 调整请求组装顺序，先得到最终系统提示和工具列表，再执行上下文预算与历史裁剪。
- [x] 按完整用户轮次裁剪，继续保留系统消息和最新一轮。
- [x] 为媒体成本保留按 Provider 覆盖的入口，未知渠道才使用固定保守值。
- [x] 真实 usage 通路完成后，用实际输入 token 与估算值记录偏差，逐步校准各协议系数。

### 6.3 主要涉及文件

- `app/src/main/java/com/android/everytalk/statecontroller/message/MessageContextWindow.kt`
- `app/src/main/java/com/android/everytalk/statecontroller/message/MessageSenderSendFlow.kt`
- `app/src/main/java/com/android/everytalk/data/network/prompt/PromptCachePolicy.kt`
- 新增的请求估算器及其单元测试

### 6.4 验收标准

- [x] 没有工具时，英文长文本的估算不再按字符数一比一放大。
- [x] 注入大型 MCP schema 后，可用历史预算会同步减少。
- [x] 系统提示、最新一轮和完整轮次边界保持不变。
- [x] 估算器输出各分类明细，分类合计等于总估算值。
- [x] 极长字符串、空消息、多模态消息和十万级 schema 不发生整数溢出。

## 七、第三阶段：统一真实 usage 通路

### 7.1 统一结构

新增统一 usage 数据结构和 `AppStreamEvent.Usage`。建议字段：

```text
inputTokens
outputTokens
reasoningTokens
cachedInputTokens
cacheWriteTokens
totalTokens
isFinal
source
```

字段缺失时保持 `null`，禁止用 `0` 混淆“服务端明确返回零”和“服务端未返回”。

### 7.2 Provider 解析

- [x] OpenAI Chat Completions：解析流末尾 `usage`，官方端点按能力开启 `stream_options.include_usage`。
- [x] OpenAI Responses：解析 `response.completed.response.usage`，保留 input、output、reasoning 和缓存明细。
- [x] Gemini：解析流式响应中的 `usageMetadata`。
- [x] Anthropic：合并 `message_start` 与 `message_delta` 中的 usage 字段。
- [x] OpenAI 兼容端点：收到 usage 就解析；需要额外请求字段时采用兼容性开关或失败回退，避免让不支持 `stream_options` 的端点直接失败。
- [x] Provider 只负责解析原始格式，统一结构的累计、去重和最终写入由公共状态层完成。

### 7.3 持久化与状态

- [x] usage 绑定 AI 消息 ID，防止并发流串到另一条消息。
- [x] 流中可更新临时值，最终 usage 到达后覆盖估算值。
- [x] 消息持久化保存最终 usage 和来源。
- [x] 老消息没有 usage 时保持可读取，并显示估算标识。
- [x] 日志只记录摘要，不记录提示词、密钥或完整响应。

### 7.4 主要涉及文件

- `app/src/main/java/com/android/everytalk/data/network/core/AppStreamEvent.kt`
- `app/src/main/java/com/android/everytalk/data/network/llm/OpenAIDirectClient.kt`
- `app/src/main/java/com/android/everytalk/data/network/llm/OpenAIResponsesClient.kt`
- `app/src/main/java/com/android/everytalk/data/network/llm/GeminiDirectClient.kt`
- `app/src/main/java/com/android/everytalk/data/network/llm/AnthropicDirectClient.kt`
- `app/src/main/java/com/android/everytalk/statecontroller/api/ApiHandlerStreamProcessor.kt`
- Message、MessageEntity、Converters 和 Room 迁移相关文件

### 7.5 验收标准

- [x] 四类直连协议各有至少一组 payload 或流事件测试。
- [x] usage 事件晚于 Finish、缺字段、重复到达时不会重复累计。
- [x] UI 状态、消息记录和日志中的最终 token 数一致。
- [x] 服务端不返回 usage 时自动回退到估算，并清楚标记来源。
- [x] 缓存 token 不会重复计入普通输入 token。

## 八、第四阶段：上下文错误分类与恢复

### 8.1 错误类型

新增公共错误分类器，至少输出：

```text
INPUT_CONTEXT_TOO_LONG
OUTPUT_LIMIT_TOO_HIGH
RATE_LIMITED
AUTHENTICATION
NETWORK
OTHER
```

优先读取 HTTP 状态、Provider 错误 code、type 和结构化字段。仅在上游没有结构化错误时使用小范围文本特征兜底。

### 8.2 恢复策略

#### 输入历史过长

1. 从错误中的明确限制更新本次请求使用的模型能力值。
2. 重新计算完整请求成本。
3. 再移除最旧的一个完整对话轮次。
4. 未产生任何正文时允许自动重试一次。
5. 仍然失败时展示清楚的上下文错误，不进入网络重试循环。

#### 输出上限过大

1. 优先使用错误响应给出的最大输出值。
2. 缺少明确上限时采用保守降级值。
3. 只降低本次请求的最大输出，不裁剪无关历史。
4. 未产生任何正文时允许自动重试一次。

#### 已产生部分正文

保留现有部分内容并停止自动重试，避免用户看到重复回答。错误信息继续附在当前消息中。

### 8.3 主要涉及文件

- `app/src/main/java/com/android/everytalk/statecontroller/api/ApiHandlerErrorController.kt`
- `app/src/main/java/com/android/everytalk/statecontroller/api/ApiHandler.kt`
- 各 Provider 的错误响应解析位置
- 模型能力解析器和请求预算器

### 8.4 验收标准

- [x] 输入过长只触发历史裁剪，输出上限过大只触发输出参数降级。
- [x] 同一请求同一错误类别最多自动恢复一次。
- [x] 已有部分输出时不自动重发。
- [x] 401、429、网络超时不会被识别成上下文错误。
- [x] `CancellationException` 始终继续抛出，不被恢复逻辑吞掉。

## 九、第五阶段：上下文占用明细

### 9.1 展示内容

```text
当前已用 / 模型窗口
系统提示
历史对话
工具 schema
图片与文件
预留输出
剩余空间
数据来源：实测 / 估算
```

真实 usage 只能提供服务端汇总值，分类仍来自发送前估算。界面需要同时保留两者：顶部总量优先使用最终 usage，分类明细使用同一次请求的估算快照，并显示差值。

### 9.2 实施任务

- [x] 定义不可变 `ContextUsageSnapshot`，在发送前绑定消息 ID 保存。
- [x] 收到最终 usage 后补齐实测总量和估算偏差。
- [x] 在模型参数或会话信息入口展示明细，不占用聊天消息主内容区域。
- [x] 旧消息、未发送草稿和不返回 usage 的渠道都能显示估算值。
- [x] 所有数字使用统一 token 格式，避免同一界面同时出现字符数和 token 数。

### 9.3 验收标准

- [x] 分类之和、预留输出和剩余空间计算一致。
- [x] 工具开关变化会影响工具 schema 占用。
- [x] 切换模型或端点后立即使用新的窗口值。
- [x] 实测值和估算值不会被混成一个无来源数字。

## 十、后续架构阶段

### 10.1 结构化滚动摘要与原消息软归档

目标：在长会话中保留事实、约束、决策和未完成事项，减少简单丢弃旧消息造成的信息断层。

- [ ] 只摘要完整对话轮次，保护系统提示和最近若干轮。
- [ ] 摘要使用结构化字段：已确认事实、用户偏好、关键决策、未完成事项、重要引用。
- [ ] 原消息继续保留在 Room，只标记为请求组装时已归档。
- [ ] 新摘要成功保存后才能替换请求中的旧轮次。
- [ ] 摘要失败时回退到现有轮次裁剪，不能删除原始消息。
- [ ] 重复摘要时以前一版摘要为输入，并保留摘要版本和覆盖范围。

验收重点：恢复历史后摘要连续、原消息可见、失败不丢数据、同一轮不会被重复摘要。

### 10.2 MCP 工具渐进加载

目标：减少大型 MCP 工具集合占用的上下文，同时保证模型知道可用能力。

- [ ] 核心工具始终直接可见。
- [ ] 延迟工具先提供紧凑名称和描述目录，完整参数 schema 按需公开。
- [ ] 只有工具 schema 成本超过明确阈值，或工具数量超过安全上限时才启用桥接。
- [ ] 保留意图分类作为排序信号，不能让分类器直接剥夺用户已启用的工具。
- [ ] `tool_search`、`tool_describe`、`tool_call` 必须受当前会话启用范围约束。
- [ ] 工具参数缺失时返回 schema 提示，避免模型盲目重试。
- [ ] 提示缓存键继续基于稳定化后的最终可见 schema。

EveryTalk 首版不采用 Hermes 当前“只要存在可延迟工具就启用桥”的逻辑。激活条件必须由真实 schema 成本和兼容性测试决定。

### 10.3 Room 全文搜索与小容量长期记忆

- [ ] 使用 Room FTS 为消息文本和必要的元数据建立本地全文索引。
- [ ] 搜索结果定位到会话和原消息，不复制整份消息数据。
- [ ] 长期记忆只保存短小、可解释、可删除的用户偏好和稳定事实。
- [ ] 记忆记录来源消息 ID、创建时间、更新时间和启用状态。
- [ ] 用户可以查看、编辑、禁用和删除记忆。
- [ ] 首版不引入向量数据库或云端记忆服务。

### 10.4 辅助模型体系

- [ ] 将压缩模型、标题模型等辅助模型与主聊天模型分开配置。
- [ ] 未配置辅助模型时回退到主模型或本地规则。
- [ ] 辅助请求使用独立 usage 记录，不能计入主回答 token。
- [ ] 摘要模型失败时不影响原会话继续发送。

## 十一、明确暂不照搬的 Hermes 设计

1. 不复制 Hermes 在不同运行路径和模型覆盖中使用的多套固定压缩阈值，例如 50% 与 85%。EveryTalk 先以能力元数据、预留输出和实测偏差确定自己的阈值。
2. 不采用“存在任意可延迟工具就强制启用工具桥”的规则。
3. 不复制 Hermes 的大型硬编码模型数值表。EveryTalk 只保留小型官方目录和最后一级家族兜底。
4. 不引入完整 Agent Loop、子代理、凭据池、终端工具和远程执行体系。
5. 不把社区模型目录当作官方事实来源。
6. 不在摘要或裁剪过程中删除 Room 原消息。

## 十二、实施顺序与依赖

```text
阶段 1 模型能力元数据
        |
        v
阶段 2 完整请求 token 估算
        |
        +------------------+
        |                  |
        v                  v
阶段 3 统一 usage      阶段 4 错误分类基础
        |                  |
        +---------+--------+
                  v
          阶段 5 上下文占用明细
                  |
                  v
       滚动摘要 / MCP 渐进加载
                  |
                  v
        全文搜索 / 长期记忆 / 辅助模型
```

每个阶段单独合并和验证。阶段 1 至阶段 3 完成前，不开始上下文占用 UI；完整请求估算完成前，不实现自动摘要阈值；工具 schema 成本可测量前，不实现 MCP 渐进加载。

## 十三、验证矩阵

| 变更类型 | 必须验证 |
|---|---|
| 模型能力解析 | 来源优先级、端点隔离、缓存过期、旧配置兼容单测 |
| token 估算与裁剪 | ASCII、CJK、代码、JSON、媒体、工具 schema、整轮裁剪单测 |
| usage 解析 | OpenAI Chat、Responses、Gemini、Anthropic 的流事件单测 |
| 错误恢复 | 输入过长、输出过大、429、401、网络超时、部分输出后的行为单测 |
| Room 字段或实体 | 对应迁移测试、旧数据库升级测试、DAO 测试 |
| Compose 占用明细 | 状态映射测试和 `assembleDebug` |
| 每个 Kotlin 阶段 | 相关单测通过后执行 `:app:compileDebugKotlin` |
| Compose、资源或数据库阶段 | 至少执行一次 `:app:assembleDebug` |

## 十四、阶段完成定义

每一阶段只有同时满足以下条件才算完成：

- [x] 公共入口只有一套，Provider 内没有复制业务规则。
- [x] 新逻辑有至少一个可运行的单元测试或迁移测试。
- [x] 失败路径、旧数据和并发消息 ID 已覆盖。
- [x] 日志不包含密钥、完整提示词和隐私数据。
- [x] 相关测试、Kotlin 编译或 Debug APK 构建通过。
- [x] 计划中的验收项已逐条回读核对。

## 十五、参考链接

- [Hermes Agent 仓库固定提交](https://github.com/NousResearch/hermes-agent/tree/87bc710609f8b89b6e6b4aa418dde8ee30ec6873)
- [模型元数据解析](https://github.com/NousResearch/hermes-agent/blob/87bc710609f8b89b6e6b4aa418dde8ee30ec6873/agent/model_metadata.py)
- [models.dev 社区目录接入](https://github.com/NousResearch/hermes-agent/blob/87bc710609f8b89b6e6b4aa418dde8ee30ec6873/agent/models_dev.py)
- [上下文占用明细](https://github.com/NousResearch/hermes-agent/blob/87bc710609f8b89b6e6b4aa418dde8ee30ec6873/agent/context_breakdown.py)
- [上下文压缩器](https://github.com/NousResearch/hermes-agent/blob/87bc710609f8b89b6e6b4aa418dde8ee30ec6873/agent/context_compressor.py)
- [会话压缩逻辑](https://github.com/NousResearch/hermes-agent/blob/87bc710609f8b89b6e6b4aa418dde8ee30ec6873/agent/conversation_compression.py)
- [工具渐进加载](https://github.com/NousResearch/hermes-agent/blob/87bc710609f8b89b6e6b4aa418dde8ee30ec6873/tools/tool_search.py)
- [usage 与价格归一化](https://github.com/NousResearch/hermes-agent/blob/87bc710609f8b89b6e6b4aa418dde8ee30ec6873/agent/usage_pricing.py)
