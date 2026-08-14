# EveryTalk Agent 有状态执行架构改造计划

## 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | 核心实现已完成，定向自动验证通过，待真机与隔离 VPS 集成验收 |
| 日期 | 2026-08-15 |
| 适用工程 | app1/ |
| 改造范围 | Android Agent Loop、ComputerExecution、VPS Runtime Wrapper |
| 核心目标 | 让 Agent、工具调用和 VPS 长任务具备可查询、可恢复、可续接的状态 |
| 运行位置 | EveryTalk Android App 与用户自己的 VPS |
| 云端依赖 | 无 |
| Tool 数量 | 保持现有七个 Computer Tool |
| 资源策略 | 不限制用户 VPS 的 CPU、内存、磁盘和 PID |

相关文档：

1. [Pi Agent架构改造计划.md](./Pi%20Agent架构改造计划.md)
2. [给AI一个服务器.md](./给AI一个服务器.md)
3. [参考Hermes的模型与上下文能力改进计划.md](./参考Hermes的模型与上下文能力改进计划.md)

本文补充 Pi Agent 与 VPS Computer 之间缺失的远端执行层。发生冲突时：

1. Agent Loop、上下文和模型请求口径以 Pi Agent 计划为准。
2. 服务器产品交互、SSH、Workspace 和权限策略以服务器计划为准。
3. 远端命令状态、长任务恢复和执行结果续接以本文为准。

## 一、结论

当前 EveryTalk 已经拥有 Agent 控制状态：

1. AgentRun 保存一次用户输入对应的完整 Agent 运行。
2. AgentEntry 保存 Assistant、Tool Call、Tool Result 和审批事件。
3. AgentRequest 保存每一次真实模型请求。
4. Room 可以恢复审批、上下文、Usage 和已完成工具结果。

本轮已补齐 VPS 远端执行状态：

1. 前台和后台 exec 都通过 Runtime V2 保存远端 Execution、状态和日志。
2. SSH 断开、App 重启或网络暂时不可用时，Android 按固定 Execution ID 对账，不把网络错误伪装成失败。
3. ComputerExecution 保存目标、完成模式、远端进程、状态目录、远端状态和退出码。
4. App 启动及进入当前 Workspace 时按需恢复，终态后复用原 AgentRun 补写唯一 Tool Result。
5. 活动后台任务通过 ComputerSessionState 注入下一轮模型上下文，后台任务不会被停止按钮误取消。

最终采用四层状态：

~~~text
Conversation
├── Agent 控制状态
│   ├── AgentRun
│   ├── AgentRequest
│   └── AgentEntry
├── Workspace 状态
│   └── 文件、项目和环境
├── Tool 执行状态
│   └── ComputerExecution
└── VPS 远端状态
    ├── Execution ID
    ├── PID 与进程起始标记
    ├── 状态文件
    ├── stdout / stderr
    └── 退出码
~~~

一句话定义：

> Pi 语义负责 Agent 如何思考和继续，ComputerExecution 负责工具调用事实，VPS Wrapper 负责命令是否仍在运行以及最终产生了什么结果。

## 二、方案边界

### 2.1 保持不变

1. 普通聊天、MCP、联网搜索和 VPS Agent 继续由同一个 Kotlin AgentLoop 驱动。
2. Pi 继续负责上下文组装、压缩、Usage、Tool Result 回填和模型循环。
3. SSH 凭据继续只保存在 Android Keystore。
4. Android 继续直接连接用户 VPS。
5. Workspace 继续按“会话 × 服务器”持久化。
6. 服务器继续与会话绑定。
7. 现有三档权限继续有效。
8. Container 与 Host 混合执行方式继续有效。
9. 七个 Computer Tool 保持：
   exec、read_file、write_file、terminal、upload、download、open_port。
10. 不增加 EveryTalk 官方后端。
11. 不要求 VPS 安装常驻 Agent 服务。
12. 不限制用户 VPS 资源。

### 2.2 本次新增

1. AgentRun 增加等待远端执行结果的明确状态。
2. ComputerExecution 同时记录 Android 工具状态与 VPS 进程状态。
3. exec 的前台命令也由 VPS 保存状态和日志。
4. Android 可以按 Execution ID 查询、恢复、读取结果和取消命令。
5. App 重启后先查询 VPS，再决定成功、失败、继续等待或 UNKNOWN。
6. 当前 Workspace 的活动任务形成精简状态快照，供后续模型请求使用。
7. 原 AgentRun 可以在远端任务完成后继续，不重新执行已经发出的命令。

### 2.3 明确不做

1. 不引入 LangGraph、OpenHands、Hermes 或 Google ADK 的完整运行时。
2. 不把 Node.js 或 Pi npm Runtime 塞入 Android。
3. 不创建装入会话、Workspace、进程和模型参数的巨大 Session 表。
4. 不增加 process_status、process_logs、process_cancel 等模型可见 Tool。
5. 不让模型通过任意文件路径读取 Runtime 状态。
6. 不让模型自己反复执行 ps、cat 或 tail 来判断当前工具是否完成。
7. 不把普通 shell 的 cd、export 等临时状态伪装成跨命令持久状态。
8. 不自动删除用户已有服务、网站和非 EveryTalk 文件。

## 三、当前实现审查

### 3.1 已经具备的基础

| 能力 | 当前实现 | 结论 |
| --- | --- | --- |
| AgentRun 状态机 | [AgentModels.kt](../../app/src/main/java/com/android/everytalk/data/agent/AgentModels.kt) | 保留 |
| Agent 事件持久化 | [AgentRunStore.kt](../../app/src/main/java/com/android/everytalk/data/agent/AgentRunStore.kt) | 保留 |
| Agent Loop | [AgentLoop.kt](../../app/src/main/java/com/android/everytalk/data/agent/AgentLoop.kt) | 扩展等待与续接 |
| Computer 工具幂等 | [ComputerToolExecutor.kt](../../app/src/main/java/com/android/everytalk/data/computer/ComputerToolExecutor.kt) | 保留 |
| Execution Room 表 | [ComputerEntities.kt](../../app/src/main/java/com/android/everytalk/data/database/entities/ComputerEntities.kt) | 增加远端字段 |
| SSH 连接池 | [ComputerRepository.kt](../../app/src/main/java/com/android/everytalk/data/computer/ComputerRepository.kt) | 保留 |
| 单 Channel Envelope | [ComputerRuntimeEnvelope.kt](../../app/src/main/java/com/android/everytalk/data/computer/ComputerRuntimeEnvelope.kt) | 升级协议 |
| VPS 后台状态文件 | [runtime-wrapper.sh](../../app/src/main/assets/computer/runtime-wrapper.sh) | 复用并扩展 |
| 受限 Container Helper | [everytalk-containerctl.sh](../../app/src/main/assets/computer/everytalk-containerctl.sh) | 增加固定子命令 |
| UNKNOWN 审批恢复 | [AgentApprovalPersistenceTest.kt](../../app/src/test/java/com/android/everytalk/data/agent/AgentApprovalPersistenceTest.kt) | 保留为最终兜底 |

### 3.2 历史缺口与落地结果

以下五项是改造前的缺口记录，当前实现已经闭环。表格在本节末给出代码和自动验证对应关系。

#### 缺口一：计划与实现不一致

Pi Agent 计划写明 Tool RUNNING 时需要查询 ComputerExecution 和 VPS 侧状态。当前启动恢复逻辑会直接执行：

~~~text
STARTING / RUNNING
        ↓
     UNKNOWN
~~~

对应位置：

1. [ComputerDao.kt](../../app/src/main/java/com/android/everytalk/data/database/daos/ComputerDao.kt)
2. [ComputerRepository.kt](../../app/src/main/java/com/android/everytalk/data/computer/ComputerRepository.kt)

该逻辑无法区分以下情况：

1. 命令仍在 VPS 运行。
2. 命令已经成功，但结果尚未回到 Android。
3. 命令已经失败，并留下退出码。
4. 命令从未成功启动。
5. 状态文件损坏或被用户删除。

#### 缺口二：前台命令与 SSH Channel 绑定

当前 foreground exec 会等待 SSH Channel 返回 stdout、stderr 和退出码。

手机锁屏、切网、系统回收或 SSH 断开时：

1. VPS 命令可能仍在运行。
2. Android 本地 Coroutine 已经消失。
3. Room 只知道 RUNNING。
4. 重启后只能转成 UNKNOWN。

#### 缺口三：后台状态没有进入 Agent Loop

现有 Wrapper 已保存：

1. process_id
2. execution_id
3. pid
4. start_ticks
5. status
6. exit_code
7. updated_at
8. stdout.log
9. stderr.log

当前 Android 只在启动后台命令时接收 process_id、pid 和日志路径。它不会持续查询，也不会在任务完成后自动把结果补回原 Tool Call。

#### 缺口四：Tool 状态和远端进程状态混在一起

background=true 时，Tool Call 在“进程成功启动”后已经完成，但 VPS 进程仍然处于 RUNNING。

因此需要同时表达：

~~~text
Tool 状态：SUCCEEDED
Remote 状态：RUNNING
~~~

单独使用现有 ComputerExecution.status 无法准确表达这两个事实。

#### 缺口五：上下文只能记住历史，无法稳定携带活动任务

Tool Result 会进入模型上下文，但活动后台任务可能跨越：

1. 多次用户输入。
2. AgentRun 边界。
3. 上下文压缩。
4. App 进程重启。

活动任务状态需要从 Room 和 VPS 重新构建，不能只依赖旧文本。

当前落地对应：

| 历史缺口 | 当前实现 | 自动验证 |
| --- | --- | --- |
| 启动后只能把 RUNNING 改成 UNKNOWN | `ComputerExecutionReconciler` 按 VPS 状态转换 | `ComputerExecutionReconcilerTest` |
| 前台命令依赖 SSH Channel | Runtime V2 使用独立进程、状态文件和日志 | Wrapper/Helper 合约测试 |
| 后台状态不进入 Agent Loop | `ComputerSessionState` 在每轮模型请求前注入 | AgentLoop 定向编译与测试 |
| Tool 与远端状态混在一起 | `status` 与 `remoteStatus` 分开保存 | Reconciler 状态转换测试 |
| 活动任务无法跨 Run、压缩和重启 | Room 查询、启动恢复、Workspace 进入时对账 | Room 18→19 Migration Test |
| 状态文件缺少归属保护 | Wrapper/Helper 校验固定路径、普通文件、所有者和容器归属 | Helper 合约测试 |

仍未自动完成的只有真机、真实 VPS 和断线集成验收。自动验证不操作用户手机、App 数据或真实服务器。

## 四、外部项目审查

以下 Stars 为 2026-08-14 查询快照。Stars 只表示项目关注度，架构结论来自其文档和源码。

| 项目 | Stars | 相关设计 | 本文采用的结论 |
| --- | ---: | --- | --- |
| [Hermes Agent](https://github.com/NousResearch/hermes-agent) | 230,496 | SQLite Session、事件、异步委派状态、完成队列 | 会话与后台任务分别保存 |
| [OpenCode](https://github.com/anomalyco/opencode) | 197,425 | Session 持久化与 Shell 执行 | 只有 Session 仍可能在恢复后卡住 |
| [Pi](https://github.com/earendil-works/pi) | 90,290 | JSONL 事件树、分支、压缩、扩展状态 | 继续承担 Agent Loop 和上下文语义 |
| [OpenHands](https://github.com/OpenHands/OpenHands) | 84,020 | ConversationState、EventLog、Workspace 分层 | 作为整体分层的主要参考 |
| [LangGraph](https://github.com/langchain-ai/langgraph) | 39,678 | Checkpointer、Thread、可恢复 Task | 外部副作用需要独立检查点和幂等键 |
| [Google ADK](https://github.com/google/adk-python) | 21,111 | Session Event、Invocation、Long-running Tool | 长任务通过 Invocation ID 暂停和恢复 |

### 4.1 Hermes Agent

Hermes 使用 SQLite WAL 保存 Session、消息、Tool Call、模型配置和压缩信息。异步委派任务拥有独立状态，任务完成后通过完成队列重新进入会话。

EveryTalk 对应关系：

| Hermes | EveryTalk |
| --- | --- |
| Session | Conversation + AgentRun |
| Session Event | AgentEntry |
| Async Delegation | 远端 ComputerExecution |
| Completion Queue | 远端执行完成后的 AgentRun 续接 |

参考：

1. [Hermes Session Storage](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/developer-guide/session-storage.md)
2. [Hermes AGENTS.md](https://github.com/NousResearch/hermes-agent/blob/main/AGENTS.md)

### 4.2 Pi

Pi 的 Session JSONL 保存消息、Tool Result、压缩、分支和扩展状态。它能重建模型上下文和会话树。

Pi 的运行中 Tool 仍依赖当前进程。EveryTalk 继续吸收 Pi 的会话语义，同时补上 Android 与 VPS 之间的 Remote Execution。

参考：

1. [Pi Session Format](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/session-format.md)
2. [Pi Agent Session Runtime](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/src/core/agent-session-runtime.ts)

### 4.3 OpenHands

OpenHands 将 ConversationState、追加式 EventLog 和 Workspace 分开：

1. ConversationState 保存控制状态。
2. EventLog 保存 Action 和 Observation。
3. Workspace 执行真实命令并保存文件。

EveryTalk 的目标结构与它最接近：

1. AgentRun 对应 ConversationState。
2. AgentEntry 对应 EventLog。
3. ComputerExecution 对应 Action 与 Observation 的执行事实。
4. VPS Workspace 对应真实执行环境。

参考：

1. [OpenHands Conversation State](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/conversation/state.py)
2. [OpenHands Event Store](https://github.com/OpenHands/software-agent-sdk/blob/main/openhands-sdk/openhands/sdk/conversation/event_store.py)
3. [OpenHands Events](https://docs.openhands.dev/sdk/arch/events)

### 4.4 LangGraph

LangGraph 使用 thread_id 和 Checkpointer 保存工作流进度。文件写入、API 请求等副作用需要放进可恢复 Task，并使用幂等方式执行。

EveryTalk 对应规则：

1. toolCallId 与 requestHash 继续作为幂等键。
2. VPS Execution ID 成为远端检查点。
3. 已完成任务从远端状态恢复，禁止重复执行。
4. 状态无法验证时进入 UNKNOWN。

参考：

1. [LangGraph Durable Execution](https://docs.langchain.com/oss/javascript/langgraph/durable-execution)
2. [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)

### 4.5 Google ADK

Google ADK 使用 Session Event 保存会话状态，长任务通过 invocation_id 暂停和恢复。

EveryTalk 对应规则：

1. AgentRun ID 对应一次可恢复运行。
2. ComputerExecution ID 对应一次可恢复外部执行。
3. 远端结果到达后恢复原 AgentRun。

参考：

1. [Google ADK Sessions](https://google.github.io/adk-docs/sessions/)
2. [Google ADK Resume](https://google.github.io/adk-docs/runtime/resume/)

### 4.6 OpenCode 反例

OpenCode 已保存 Session，仍出现重启后停留在 Thinking 的问题。相关问题说明 Shell 进程状态和会话状态缺一不可：

1. [Issue 19023](https://github.com/anomalyco/opencode/issues/19023)
2. [Issue 7750](https://github.com/anomalyco/opencode/issues/7750)

## 五、Agent Session 定义

EveryTalk 的 Agent Session 定义为运行时聚合视图，不新增 AgentSessionEntity。

每次准备模型请求时，从以下来源组装：

~~~text
AgentSessionView
├── Conversation 消息
├── AgentRun / AgentEntry
├── 当前 Compaction
├── Provider Continuation
├── 当前 Computer 与 Workspace
└── 活动 Remote Execution 摘要
~~~

这样可以获得完整状态，同时避免：

1. 同一数据复制进多张表。
2. Session 对象无限变大。
3. Workspace 状态与模型上下文互相污染。
4. 一次状态更新需要修改巨大 JSON。
5. 远端任务和本地会话出现两个真相来源。

### 5.1 Agent 控制状态

继续由 AgentRun 和 AgentEntry 保存：

1. 当前模型请求序号。
2. Assistant 返回内容。
3. Tool Call 顺序。
4. Tool 执行开始事实。
5. Tool Result。
6. ApprovalRequest 与 ApprovalDecision。
7. 上下文压缩与 Usage。
8. 完成、失败、取消和中断。

### 5.2 Workspace 状态

继续由 VPS 文件系统和 Room Workspace 映射保存：

1. 项目文件。
2. Agent 生成的产物。
3. Container 环境。
4. 会话与服务器的稳定路径。

每次 exec 仍显式携带 cwd。普通 shell 的 cd 不跨命令保存。

### 5.3 远端执行状态

由 ComputerExecution 和 VPS 状态目录共同保存：

1. Android 保存执行引用和最后一次观察结果。
2. VPS 保存进程真实状态。
3. Android 在线时同步状态。
4. Android 离线时 VPS 继续运行。
5. 恢复后以 VPS 状态为执行事实来源。

### 5.4 活动任务上下文

每次模型请求前生成精简的 ComputerSessionState：

1. 只包含当前 Workspace。
2. 优先包含当前 AgentRun 正在等待的任务。
3. 包含仍为 STARTING、RUNNING、UNKNOWN 的任务。
4. 不重复加入完整命令和完整日志。
5. 不加入已经稳定完成的历史任务。
6. 大量活动任务时按上下文预算裁剪，并保留总数说明。

示例：

~~~text
当前 Workspace 活动任务：
- execution_abc，container，RUNNING，已运行 48 秒
- execution_def，host，UNKNOWN，需要确认状态
~~~

该快照由 App 生成，模型不能修改 Room 或伪造任务状态。

## 六、目标架构

~~~text
                         Android

                Chat UI / Execution Trace
                           |
                       AgentLoop
                           |
          +----------------+----------------+
          |                                 |
     AgentRunStore                  AgentContextManager
          |                                 |
   AgentRun / Entry               AgentSessionView
          |
   AgentToolRuntime
          |
   ComputerToolExecutor
          |
  ComputerExecutionReconciler
          |
   SSH Connection Pool
          |
          v
                         VPS

             Container Helper / Host Wrapper
                           |
                    Execution Directory
               +-----------+-----------+
               |           |           |
             state      stdout.log   stderr.log
               |
          PID / status / exit code
~~~

### 6.1 Android 职责

1. 生成不可重复的 Execution ID。
2. 在发出 SSH 命令前写入 ComputerExecution。
3. 发送经过校验的 Runtime Envelope。
4. 查询固定 Execution ID 对应的状态。
5. 对远端状态做严格解析。
6. 更新 Room 缓存。
7. 读取受控长度的输出。
8. 生成 Tool Result。
9. 恢复原 AgentRun。
10. 响应用户停止操作。

### 6.2 VPS 职责

1. 接收固定格式的 Runtime Envelope。
2. 原子创建 Execution 目录。
3. 启动与 SSH Channel 解耦的受管进程。
4. 保存 PID、进程起始标记和 requestHash。
5. 保存 stdout、stderr。
6. 原子更新状态和退出码。
7. 按 Execution ID 查询状态。
8. 校验归属后取消进程。
9. Workspace 删除时清理 EveryTalk 受管任务。

### 6.3 明确不存在的组件

1. EveryTalk 云端任务队列。
2. EveryTalk VPS 控制面。
3. 云端数据库。
4. 云端 SSH 凭据。
5. VPS 常驻 Agent daemon。
6. 第三方任务中转。

Runtime Wrapper 只在执行命令时启动。远端命令自身可以在 Android 离线期间继续。

## 七、状态模型

### 7.1 AgentRun 状态

在现有 AgentRunStatus 中增加：

~~~text
WAITING_REMOTE_EXECUTION
~~~

用途：

1. Assistant 已经完整返回 Tool Call。
2. Tool 已经在 VPS 启动。
3. Agent 正在等待该 Tool 的最终结果。
4. App 重启时保留该状态并进入远端查询。

目标状态机：

~~~text
CREATED
  ↓
PREPARING_CONTEXT
  ↓
WAITING_MODEL
  ↓
STREAMING_MODEL
  ↓
CHECKING_PERMISSION
  ├── WAITING_APPROVAL
  └── EXECUTING_TOOL
          ├── WAITING_REMOTE_EXECUTION
          ├── PERSISTING_RESULT
          └── CHECKING_PERMISSION
  ↓
COMPLETED / FAILED / CANCELLED / INTERRUPTED
~~~

### 7.2 ComputerExecution 两套状态

ComputerExecution.status 继续表示 Tool Call 生命周期：

| 状态 | 含义 |
| --- | --- |
| QUEUED | 本地已创建，尚未发出 |
| STARTING | 正在发送远端启动请求 |
| RUNNING | Tool 正在等待最终结果 |
| SUCCEEDED | Tool Result 已成功形成 |
| FAILED | Tool 已明确失败 |
| TIMED_OUT | Tool 等待超过协议时限 |
| CANCELLED | 用户明确取消 |
| UNKNOWN | 无法证明成功、失败或仍在运行 |

新增 remoteStatus 表示 VPS 进程生命周期：

| 状态 | 含义 |
| --- | --- |
| STARTING | Execution 目录已创建，进程正在启动 |
| RUNNING | 远端进程存活 |
| SUCCEEDED | 退出码为 0 |
| FAILED | 非 0 退出 |
| TIMED_OUT | VPS 端 timeout 终止 |
| CANCELLED | EveryTalk 明确取消 |
| STOPPED | Container 或 VPS 重启导致任务停止 |
| MISSING | 确认远端没有该 Execution |
| UNKNOWN | 状态文件缺失、损坏或无法验证 |

### 7.3 foreground 与 background

| exec 参数 | Tool 状态 | Remote 状态 | Agent 行为 |
| --- | --- | --- | --- |
| background=false | RUNNING | RUNNING | 等待最终结果 |
| background=false，远端完成 | SUCCEEDED/FAILED | SUCCEEDED/FAILED | 回填 Tool Result 并继续模型 |
| background=true，启动成功 | SUCCEEDED | RUNNING | 立即把进程引用返回模型 |
| background=true，后续完成 | SUCCEEDED | SUCCEEDED/FAILED | 更新活动任务状态，不主动调用模型 |

background=true 只表示 Agent 不等待最终结果。任务仍然必须可查询、可取消和可清理。

## 八、Room 数据模型

### 8.1 不新增巨大 Session 表

继续使用现有：

1. agent_runs
2. agent_entries
3. agent_requests
4. agent_request_usage
5. agent_context_snapshots
6. agent_compactions
7. provider_continuation_states
8. computer_executions

### 8.2 ComputerExecutionEntity 增加字段

Room 从版本 18 升级到版本 19。

computer_executions 增加以下可空字段：

| 字段 | 类型 | 作用 |
| --- | --- | --- |
| target | TEXT | CONTAINER 或 HOST |
| completionMode | TEXT | WAIT_FOR_RESULT 或 RETURN_HANDLE |
| remoteProcessId | TEXT | VPS 受管进程 ID |
| remoteStatePath | TEXT | App 生成并校验的固定状态目录 |
| remoteStatus | TEXT | 最后一次观察到的 VPS 状态 |
| remoteExitCode | INTEGER | VPS 最终退出码 |
| lastObservedAt | INTEGER | 最近一次成功查询时间 |

不把以下内容写入 Room：

1. SSH 密码。
2. SSH 私钥。
3. sudo 密码。
4. Workspace Secret。
5. 完整环境变量。
6. 完整 stdout、stderr。
7. 任意模型生成路径。

### 8.3 为什么复用 ComputerExecution

一次 exec Tool Call 当前只产生一个受管远端进程。Tool Call 与 Remote Execution 为一对一关系。

在同一实体增加独立 remoteStatus 可以：

1. 避免新增一张只做一对一映射的表。
2. 保留现有 toolCallId 唯一索引。
3. 继续复用 requestHash 幂等检查。
4. 兼容其他六个 Tool，新增字段保持 null。
5. 同时表达 background Tool 已完成、远端进程仍运行。

如果未来一个 Tool Call 需要管理多个并行子进程，再拆分独立 RemoteExecution 表。本文不提前增加该结构。

### 8.4 Migration 18 → 19

迁移要求：

1. 只执行 ADD COLUMN。
2. 所有新增字段允许 null。
3. 旧 ComputerExecution 保持原状态。
4. 不扫描或连接 VPS。
5. 不修改旧 Workspace。
6. 不删除旧后台任务目录。
7. Migration Test 必须覆盖升级前 Execution 数据保留。

## 九、VPS Runtime 协议

### 9.1 版本

1. Container Helper 从 v5 升级到 v7。
2. Runtime Envelope 升级为 V2。
3. v7 Helper 继续识别 v5 已创建的 Workspace 和 Container。
4. Wrapper 文件继续按内容哈希版本化安装。
5. Helper 升级不删除用户 Workspace 文件。

### 9.2 Execution ID

Execution ID 继续由 Android 创建：

~~~text
execution_<随机 UUID>
~~~

Remote Process ID 使用确定映射：

~~~text
process_<Execution ID>
~~~

同一个 Execution ID 重复启动时：

1. requestHash 相同：返回当前状态，不重复启动。
2. requestHash 不同：返回 IDEMPOTENCY_CONFLICT。
3. 状态目录存在但内容损坏：返回 EXECUTION_UNKNOWN。

### 9.3 目录

Container：

~~~text
Host:
~/.everytalk/workspaces/<workspaceId>/.everytalk/executions/<executionId>/

Container:
/workspace/.everytalk/executions/<executionId>/
~~~

Host：

~~~text
~/.everytalk/host-executions/<executionId>/
~~~

目录内容：

~~~text
state
stdout.log
stderr.log
~~~

命令、cwd、环境变量和 stdin 在进程完成准备后清理，不长期保留。

### 9.4 state 文件

状态文件使用固定 key=value 格式：

~~~text
protocol=2
execution_id=execution_...
process_id=process_execution_...
request_hash=<sha256>
target=CONTAINER
pid=1234
start_ticks=567890
status=RUNNING
exit_code=
started_at=...
updated_at=...
stdout_bytes=...
stderr_bytes=...
~~~

规则：

1. 使用临时文件加原子 mv 更新。
2. 文件权限为 0600。
3. 目录权限为 0700。
4. 每个字段都有最大长度。
5. 未知字段忽略，必需字段缺失视为损坏。
6. Execution ID、Process ID、requestHash 必须与本地预期一致。
7. Android 不接受状态文件提供的任意路径。

### 9.5 状态查询

Container Helper 增加固定子命令：

| 子命令 | 作用 |
| --- | --- |
| execution-status | 返回固定 Execution 的状态 |
| execution-result | 按固定偏移读取 stdout、stderr |
| cancel-execution | 校验归属后取消固定 Execution |
| list-executions | 只列出当前 Workspace 的 EveryTalk Execution |

所有子命令：

1. 固定参数数量。
2. 校验 Workspace ID 和 Execution ID。
3. 禁止传入任意路径。
4. 校验 Container 归属标签。
5. 禁止透传 Docker 参数。
6. 禁止执行任意 root 命令。

Host 模式使用用户目录中的版本化 Wrapper 完成同等查询。Host 查询不经过 Container Helper。

### 9.6 远端 timeout

foreground 命令的 timeout 必须由 VPS Runtime 执行。Android timeout 只负责等待和网络边界。

这样可以保证：

1. SSH 断开后 timeout 仍然有效。
2. Android 被杀后命令不会无限运行。
3. 最终状态可以写为 TIMED_OUT。

background=true 保持长期任务语义，不套用 foreground 等待时限。用户可以通过已有停止交互取消 AgentRun；明确返回的后台任务由后续命令或 Workspace 生命周期管理。

## 十、exec 完整流程

### 10.1 foreground 短命令

~~~text
模型返回 exec
    ↓
审批预检
    ↓
Room 写入 ComputerExecution.STARTING
    ↓
VPS 原子创建 Execution
    ↓
VPS 返回已启动
    ↓
Room 写入 status=RUNNING, remoteStatus=RUNNING
    ↓
Android 查询状态
    ↓
远端很快完成
    ↓
读取受控输出
    ↓
写入 ToolResult
    ↓
AgentLoop 继续
~~~

### 10.2 foreground 长命令

长命令与短命令使用同一协议。区别只在等待时间：

1. VPS 进程独立运行。
2. Android 使用连接池查询状态。
3. 查询间隔逐步退避。
4. 状态仍为 RUNNING 时不发起新模型请求。
5. 完成后一次性形成 Tool Result。
6. App 离线不影响 VPS 进程。

### 10.3 background 命令

1. VPS 创建受管进程。
2. Android 收到启动确认。
3. Tool Result 返回 execution_id、process_id、状态和日志引用。
4. AgentLoop 立即继续。
5. ComputerExecution.status 写为 SUCCEEDED。
6. ComputerExecution.remoteStatus 保持 RUNNING。
7. 后续请求通过 ComputerSessionState 看到活动任务。
8. 后台任务完成不会在没有用户输入时自动请求模型。

### 10.4 Host 命令

Host 与 Container 使用相同的 Execution 状态协议：

1. 权限预检规则保持不变。
2. Manual、Smart、Full 三档权限保持不变。
3. 命令继续完整展示给用户。
4. Host 禁止 Workspace Secret。
5. Host 状态目录固定在 SSH 用户 HOME 下。
6. 显式 sudo 仍属于命令内容。
7. Wrapper 只管理自己启动的进程组。
8. 取消前校验 PID、start_ticks、execution_id 和进程组。

Host 的 background=true 产品限制保持不变。需要长期运行的 Host 服务继续使用 systemd、tmux 或服务自身的守护方式。Host foreground 仍需具备断线可查询能力。

## 十一、恢复

### 11.1 App 启动

旧逻辑：

~~~text
STARTING / RUNNING → UNKNOWN
~~~

新逻辑：

~~~text
读取等待恢复的 AgentRun
    ↓
读取关联 ComputerExecution
    ↓
按 Computer 与 Workspace 分组
    ↓
连接对应 VPS
    ↓
查询 Execution ID
    ├── RUNNING：继续等待
    ├── SUCCEEDED：读取结果并续接
    ├── FAILED：读取错误并续接
    ├── TIMED_OUT：生成超时结果
    ├── CANCELLED：生成取消结果
    ├── MISSING：进入进一步确认
    └── 状态损坏：UNKNOWN
~~~

### 11.2 恢复触发范围

App 启动时只主动连接：

1. WAITING_REMOTE_EXECUTION 的 AgentRun。
2. 已有 Tool 开始事实但缺少 Tool Result 的 ComputerExecution。
3. 用户当前打开会话中的活动远端任务。

历史 background 任务不在启动时全部连接。进入对应会话或服务器页面时再刷新，避免多服务器同时建立 SSH。

### 11.3 恢复结果

| 远端情况 | Room 行为 | Agent 行为 |
| --- | --- | --- |
| 仍在运行 | 保持 RUNNING | 继续等待 |
| 已成功 | 写入 SUCCEEDED 和退出码 | 补写 Tool Result，继续模型 |
| 已失败 | 写入 FAILED 和退出码 | 补写错误 Tool Result，继续模型 |
| 已超时 | 写入 TIMED_OUT | 补写超时 Tool Result |
| 已取消 | 写入 CANCELLED | 补写取消 Tool Result |
| 确认没有启动 | 根据开始事实决定安全重试或失败 | 禁止直接伪造成功 |
| 无法确认 | 写入 UNKNOWN | 进入现有用户决定流程 |

### 11.4 崩溃窗口

#### Room 已写 STARTING，VPS 尚未收到

查询确定 Execution ID。远端目录不存在且启动请求确认未发出时，可以重新发送同一个 Execution ID。

#### VPS 已启动，Android 尚未收到确认

重新查询确定 Execution ID。requestHash 相同则接管现有进程。

#### VPS 已完成，Android 尚未保存 Tool Result

读取状态和日志，补写 Tool Result。AgentLoop 跳过重复执行。

#### Tool Result 已保存，模型尚未继续

复用当前 AgentRunStore 逻辑，从已保存 Tool Result 后继续下一次模型请求。

#### 状态文件损坏

禁止根据 PID 猜测成功。进入 UNKNOWN，由用户选择重新执行或保留未知状态。

### 11.5 VPS 重启

1. Container 继续保持 restart=no。
2. VPS 重启后历史 Container 不自动全部启动。
3. 原 RUNNING Execution 根据状态文件和 Container 状态变为 STOPPED。
4. Android 恢复时返回明确 Tool Result。
5. 不自动重放写操作。

## 十二、取消

### 12.1 用户停止 AgentRun

停止按钮处理顺序：

1. 停止新的模型请求。
2. 查询当前是否存在 WAITING_REMOTE_EXECUTION。
3. 对 foreground 受管任务发送 cancel-execution。
4. VPS 校验 PID、start_ticks、execution_id 和进程归属。
5. 先发送 TERM。
6. 等待固定宽限时间。
7. 仍未退出时发送 KILL。
8. VPS 写入 CANCELLED 和退出码。
9. Android 写入 Tool Result。
10. AgentRun 标为 CANCELLED。

### 12.2 App 进程死亡

App 进程死亡不等于用户取消：

1. 不向 VPS 发送取消。
2. 远端任务继续。
3. Room 保留 WAITING_REMOTE_EXECUTION。
4. 下次进入恢复流程。

### 12.3 background 任务

background=true 的 Tool Call 已经完成。停止后续 AgentRun不自动终止该任务，避免把“停止 AI 回复”解释成“删除用户已经启动的服务”。

需要终止后台任务时，模型仍可执行明确的停止命令，或由 Workspace 删除流程清理 EveryTalk 受管任务。

## 十三、输出与上下文

### 13.1 远端日志

1. stdout 和 stderr 分开保存。
2. VPS 保留完整日志。
3. Android 按偏移读取。
4. 每次 SSH 返回使用现有输出保护边界。
5. 只把与当前决策相关的片段发送给模型。
6. Tool Result 标记截断状态和完整日志引用。
7. 日志路径由 App 生成，模型不能指定。

### 13.2 Tool Result

foreground 完成后 Tool Result 至少包含：

~~~text
ok
execution_id
status
exit_code
stdout
stderr
stdout_truncated
stderr_truncated
duration_ms
~~~

background 启动后 Tool Result 至少包含：

~~~text
ok
execution_id
process_id
status=RUNNING
target
log_reference
~~~

### 13.3 上下文压缩

Tool Call 与最终 Tool Result 继续作为原子组。

正在运行的 foreground Tool 尚无最终 Tool Result时：

1. AgentRun 停留在 WAITING_REMOTE_EXECUTION。
2. 不发起上下文压缩请求。
3. 不发起下一次模型请求。
4. 恢复完成后再生成 Tool Result。

活动 background 任务由 ComputerSessionState 补充，不依赖旧 Tool Result 永久留在上下文尾部。

### 13.4 防止 Token 再次膨胀

1. 状态查询不进入模型上下文。
2. 每次轮询不创建 AgentEntry。
3. 只在状态发生变化时更新 Room。
4. 最终只生成一条 Tool Result。
5. stdout、stderr 按现有结果控制策略截断。
6. 完整日志不重复回填。

## 十四、UI 行为

### 14.1 执行过程

同一个 Tool Call 始终显示为同一条执行过程：

| 阶段 | 文案示例 |
| --- | --- |
| 启动 | 正在启动命令 |
| 运行 | 正在运行，已用时 01:24 |
| 重连 | 正在恢复远端任务 |
| 取消中 | 正在取消远端任务 |
| 完成 | 命令执行完成 |
| 失败 | 命令执行失败 |
| 取消失败 | 远端取消失败，等待恢复确认 |
| 超时 | 命令执行超时 |
| 未知 | 无法确认远端执行状态 |

轮询过程不新增多条“检查状态”步骤，避免执行链抖动。

### 14.2 App 重启

用户重新进入会话时：

1. 原执行过程仍在原位置。
2. 显示“正在恢复远端任务”。
3. 查询完成后更新同一条 Tool。
4. 远端完成时 Agent 自动继续回答。
5. UNKNOWN 时显示现有确认卡。

### 14.3 停止按钮

1. WAITING_REMOTE_EXECUTION 时保持停止按钮。
2. 单击后立即进入固定尺寸加载状态。
3. 远端确认取消后恢复普通发送按钮。
4. 取消失败时显示明确错误，不伪装为已停止。

### 14.4 background 任务

background=true 启动成功后，执行过程显示“后台任务已启动”。Agent 可以继续执行其他步骤。

输入框不增加新的标签、按钮或状态占位。

## 十五、性能

### 15.1 SSH

1. 继续复用 ComputerConnectionPool。
2. 启动与状态查询使用同一 SSH Transport 的不同 exec Channel。
3. 不为每次轮询重新握手。
4. 首轮模型思考期间的预连接继续保留。
5. Wrapper 继续按内容哈希持久化。

### 15.2 轮询

使用逐步退避：

1. 启动后的短时间快速确认。
2. 稳定 RUNNING 后降低查询频率。
3. 状态发生变化时立即读取最终结果。
4. App 进入后台且没有前台 AgentRun 时停止本地轮询。
5. 恢复时重新查询远端事实。

轮询不调用模型，不写 AgentEntry，不读取完整日志。

### 15.3 日志读取

1. 使用偏移量增量读取。
2. 状态查询只返回小型固定文本。
3. 最终结果读取遵守 maxOutputBytes。
4. 大日志只读取头部、尾部和必要错误片段。

## 十六、安全

### 16.1 信任边界

1. Android 生成 Execution ID。
2. 模型只能提供 exec 业务参数。
3. 模型不能提供状态目录。
4. Helper 只接受固定 ID。
5. VPS 状态文件只在 EveryTalk 受管目录。
6. SSH 凭据不写入 Runtime。
7. requestHash 用于确认恢复的仍是原请求。

### 16.2 PID 复用

取消进程前同时校验：

1. PID。
2. /proc start_ticks。
3. Execution ID。
4. Process ID。
5. 进程组。
6. Container 或用户归属。

任意校验失败时禁止 kill，并返回 EXECUTION_UNKNOWN。

### 16.3 路径

1. 禁止符号链接越界。
2. 禁止任意绝对路径参数。
3. Helper 根据 Workspace ID 推导路径。
4. Host Wrapper 根据 Execution ID 推导路径。
5. 状态和日志必须为普通文件。
6. 文件所有者必须符合预期。

### 16.4 Helper 权限

新增子命令继续遵守“遥控器”原则：

1. 只能查询 EveryTalk 受管 Execution。
2. 只能取消 EveryTalk 受管 Execution。
3. 不能执行任意 Docker 命令。
4. 不能读取其他 Workspace。
5. 不能删除系统文件。
6. 不能透传 shell 参数。

### 16.5 输出披露

命令输出可能发送给当前 AI 模型服务商。继续复用现有敏感信息过滤、Secret 隐藏和 Tool Result 截断逻辑。

## 十七、并发与幂等

### 17.1 唯一键

1. toolCallId 与 ComputerRequestContext 继续生成稳定 Tool Call Key。
2. requestHash 继续校验同一 Tool Call 是否被替换参数。
3. Execution ID 全局唯一。
4. VPS 状态目录使用原子创建。

### 17.2 重复恢复

重复执行恢复扫描必须满足：

1. 不创建第二个远端进程。
2. 不写入第二条 Tool Result。
3. 不重复恢复同一个 AgentRun。
4. 不重复显示审批卡。
5. 不重复读取完整日志。

### 17.3 多会话

1. 不同会话拥有不同 Workspace。
2. 不同 Workspace 的 Execution 目录隔离。
3. 同一 VPS 可以并行运行多个会话任务。
4. AgentRun 继续按现有文本流规则串行续接。
5. 远端状态同步可以并发，但同一 Execution 使用本地 Mutex。

## 十八、错误处理

新增错误码：

| 错误码 | 含义 |
| --- | --- |
| EXECUTION_STATE_INVALID | 状态文件损坏或字段不完整 |
| EXECUTION_NOT_FOUND | 已确认远端不存在 |
| EXECUTION_RESULT_UNAVAILABLE | 状态已完成但结果文件不可读 |
| EXECUTION_CANCEL_FAILED | 无法安全取消对应进程 |
| EXECUTION_CANCEL_REQUESTED | 已发出取消请求，VPS 终态尚未确认 |
| EXECUTION_REQUEST_HASH_CONFLICT | 同一 Execution ID 的请求身份不一致，禁止重试 |
| EXECUTION_PROTOCOL_MISMATCH | Helper 或 Wrapper 协议版本不兼容 |

继续使用：

1. EXECUTION_UNKNOWN
2. IDEMPOTENCY_CONFLICT
3. SSH_TIMEOUT
4. HELPER_INTEGRITY_FAILED
5. WORKSPACE_NOT_READY
6. COMPUTER_NOT_READY

错误规则：

1. 网络错误不能直接等于远端失败。
2. SSH 超时不能直接等于命令超时。
3. 日志读取失败不能覆盖已经确认的退出状态。
4. 状态损坏不能伪造成功结果。
5. 请求哈希冲突落为明确失败，不进入 UNKNOWN 重试卡。
6. UNKNOWN 写操作禁止自动重放。

## 十九、代码改造范围

### 19.1 新增文件

| 文件 | 责任 |
| --- | --- |
| data/computer/ComputerExecutionReconciler.kt | 查询 VPS 状态、更新 Room、恢复结果和处理取消 |
| data/computer/ComputerRemoteExecutionParser.kt | 严格解析固定状态与结果协议 |
| data/computer/ComputerExecutionReconcilerTest.kt | 覆盖远端状态对账和本地状态转换 |
| data/computer/ComputerRemoteExecutionParserTest.kt | 覆盖远端文本解析与无效输入 |

不新增顶层目录。

### 19.2 修改文件

| 文件 | 改动 |
| --- | --- |
| data/agent/AgentModels.kt | 增加 WAITING_REMOTE_EXECUTION |
| data/agent/AgentLoop.kt | 远端等待、完成续接、取消 |
| data/agent/AgentRunStore.kt | 查询等待远端的 Run，保持 Tool Result 幂等 |
| data/agent/AgentContextManager.kt | 注入精简 ComputerSessionState |
| data/computer/ComputerModels.kt | remoteStatus、completionMode 和错误码 |
| data/computer/ComputerToolExecutor.kt | foreground 与 background 统一远端执行协议 |
| data/computer/ComputerRuntimeEnvelope.kt | 启动、查询、读取结果与取消 |
| data/computer/ComputerRepository.kt | 启动恢复时先远端对账 |
| data/database/entities/ComputerEntities.kt | 增加远端执行字段 |
| data/database/daos/ComputerDao.kt | 活动执行查询和事务更新 |
| data/database/AppDatabase.kt | Room 19 与 MIGRATION_18_19 |
| statecontroller/api/ApiHandler.kt | 自动恢复 WAITING_REMOTE_EXECUTION |
| assets/computer/runtime-wrapper.sh | V2 状态协议和 Host 可恢复前台命令 |
| assets/computer/everytalk-containerctl.sh | v7 固定查询、结果、取消子命令 |
| data/computer/ComputerProvisioner.kt | Bootstrap 版本升级为 7 |

### 19.3 复用文件

1. AgentToolRuntime.kt
2. ComputerConnectionPool.kt
3. ComputerSshClient.kt
4. ComputerWorkspaceManager.kt
5. ComputerHostCommandPolicy.kt
6. AgentToolResultStore.kt

## 二十、实施顺序

### 阶段 1：冻结协议与数据模型

1. 定义 remoteStatus。
2. 定义 state V2。
3. 定义 Helper 固定子命令。
4. 定义 foreground、background 的双状态语义。
5. 新增 Room 19 Migration Test。

完成标准：

1. 状态表没有歧义。
2. 同一个 Execution 的本地与远端状态转换完整。
3. 旧数据库无损升级。

### 阶段 2：VPS Runtime v7

1. Wrapper 统一保存 foreground 和 background 状态。
2. Container Helper 增加查询、结果、取消。
3. Host Wrapper 增加可恢复 foreground。
4. timeout 移到远端执行。
5. 状态文件原子写入。
6. PID 复用防护。

完成标准：

1. SSH 断开后命令继续。
2. 重新连接能读取最终状态。
3. 相同 Execution ID 不重复启动。
4. 取消不会误杀其他进程。

### 阶段 3：Android ComputerExecution

1. 写入远端执行引用。
2. 实现状态解析。
3. 实现状态同步。
4. 实现结果读取。
5. 实现取消。
6. 替换无条件 UNKNOWN。

完成标准：

1. Room 能表达 Tool 与 Remote 两套状态。
2. App 重启能对账。
3. 日志和状态不会进入错误 Workspace。

### 阶段 4：AgentRun 续接

1. 增加 WAITING_REMOTE_EXECUTION。
2. AgentLoop 等待远端结果。
3. 完成后生成唯一 Tool Result。
4. 恢复后继续原 AgentRun。
5. 活动任务加入 ComputerSessionState。

完成标准：

1. 长脚本完成后 AI 能获得结果。
2. App 重启后 AI 能继续原任务。
3. 不重复执行工具。
4. 不重复累计 Tool Result 和 Token。

### 阶段 5：UI 与错误恢复

1. 同一执行过程原位更新。
2. 增加恢复、等待、取消状态。
3. 复用现有 UNKNOWN 确认卡。
4. 停止按钮控制 foreground 受管任务。

完成标准：

1. 用户始终知道 Agent 仍在运行。
2. 恢复期间不会出现假结束。
3. 取消结果明确。

### 阶段 6：验证与文档同步

当前状态：自动验证已完成，真机、真实 VPS 和断线集成验收由用户执行。

本轮审查补全（2026-08-15）：

| 审查项 | 当前实现 |
| --- | --- |
| 失败状态外层结果 | 正常执行、恢复执行和错误恢复统一输出 `ok`、`status`、`duration_ms`，远端失败不再覆盖为成功 |
| 重启恢复连接 | `READY`、`OFFLINE`、`DISCONNECTED` 都允许受控重连；其他配置或 Host Key 状态不会绕过修复流程 |
| 远端取消 | 停止按钮先落取消意图，Wrapper 按进程组执行 TERM、等待、KILL，未确认时继续对账 |
| 后台任务 | background 不套 120 秒超时，句柄和 Room 统一返回 `status=RUNNING` |
| 活动任务上下文 | `ComputerSessionState` 在每轮请求和压缩后重新注入，恢复查询按会话与 AgentRun 收窄 |
| UI 恢复与取消 | 原消息位置更新“正在恢复”“等待重连”“正在取消”“取消失败”等状态，取消期间输入按钮显示固定加载状态 |
| 请求哈希冲突 | 使用专用 `EXECUTION_REQUEST_HASH_CONFLICT`，落为明确失败，不进入 UNKNOWN 重试卡 |
| 路径与进程安全 | Wrapper/Helper 校验父目录、普通文件、所有者、PID、start_ticks、进程组和用户归属 |

本轮自动验证：`compileDebugKotlin`、远端解析/对账/Helper 合约测试、Room Migration、Agent 恢复测试、Wrapper 自检均通过。真实 SSH 断线、Container/Host 集成和真机行为仍需在隔离 VPS 与用户设备上验证。

1. 运行定向单元测试。
2. 运行 Room Migration Test。
3. 运行本地 Container 集成测试。
4. 更新 Pi Agent 计划的阶段 7 状态。
5. 更新服务器计划的 Execution 与恢复章节。

完成标准：

1. 自动测试覆盖所有状态转换。
2. 现有计划不再声称未实现能力已经完成。
3. 真机验收清单交给用户执行。

## 二十一、验证计划

### 21.1 单元测试

#### 状态解析

1. 合法 RUNNING。
2. 合法 SUCCEEDED。
3. 合法 FAILED 与负退出码边界。
4. 缺少必需字段。
5. 重复字段。
6. 非法 Execution ID。
7. requestHash 不一致。
8. 超长字段。
9. 未知协议版本。
10. 符号链接与非普通文件。

#### 状态转换

1. foreground STARTING → RUNNING → SUCCEEDED。
2. foreground RUNNING → FAILED。
3. foreground RUNNING → TIMED_OUT。
4. foreground RUNNING → CANCELLED。
5. background Tool SUCCEEDED + Remote RUNNING。
6. background Remote RUNNING → SUCCEEDED。
7. 网络错误保持最后远端状态。
8. 状态损坏转 UNKNOWN。

#### 幂等

1. 同 ID、同 requestHash 不重复启动。
2. 同 ID、不同 requestHash 冲突。
3. 重复恢复不重复 Tool Result。
4. 重复取消不误报成功。
5. 多次扫描不重复审批。

### 21.2 Room Migration Test

1. 从版本 18 创建旧数据库。
2. 插入 RUNNING、SUCCEEDED、UNKNOWN Execution。
3. 升级版本 19。
4. 验证旧字段完整。
5. 验证新增字段为 null。
6. 验证 toolCallId 唯一索引保持。
7. 验证外键级联保持。

### 21.3 Container 集成测试

1. 执行 100 毫秒短命令。
2. 执行 30 秒长命令。
3. 命令运行中关闭 SSH Channel。
4. 重新建立 SSH 并读取状态。
5. 命令完成后读取 stdout、stderr。
6. 执行失败并验证退出码。
7. 执行超时并验证 TIMED_OUT。
8. 取消进程并验证 PID 防护。
9. 重复发送 Execution ID。
10. Container 重启后状态变为 STOPPED。

### 21.4 Host 集成测试

1. 只读短命令。
2. 长时间系统查询。
3. SSH 中断后恢复。
4. 用户拒绝 Host 权限。
5. NOPASSWD sudo 命令。
6. 状态目录权限。
7. 取消当前 SSH 用户启动的进程。
8. 禁止取消非 EveryTalk 进程。

### 21.5 AgentLoop 测试

1. Tool Call 启动后进入 WAITING_REMOTE_EXECUTION。
2. RUNNING 时不发起下一次模型请求。
3. SUCCEEDED 后生成 Tool Result。
4. 失败结果继续交给模型分析。
5. App 重启后恢复原 Run。
6. Tool Result 已存在时跳过远端执行。
7. background=true 立即继续 Agent。
8. 活动 background 任务进入下一轮 ComputerSessionState。
9. 压缩不丢失活动任务引用。
10. 同一结果不重复计算 Token。

### 21.6 回归测试

1. 普通聊天不受影响。
2. MCP Tool 不受影响。
3. 联网搜索不受影响。
4. read_file、write_file、upload、download 不受影响。
5. 权限三档不受影响。
6. Agent 关闭时不连接服务器。
7. 服务器删除不删除用户已有网站。
8. Workspace 删除只处理 EveryTalk 受管资源。
9. Container 保持 restart=no。

### 21.7 真机验收边界

真机验收由用户执行。自动验证禁止：

1. adb install。
2. adb uninstall。
3. pm clear。
4. 删除 App 数据。
5. 替换用户当前安装包。
6. 操作用户真实 VPS。

自动验证只运行本地单元测试、Room Migration Test 和 Wrapper 自检；需要真实 Docker、SSH 断线和 Host 权限的步骤不在本机自动执行范围内。

## 二十二、性能验收

记录以下时间：

1. ComputerExecution 本地创建耗时。
2. 远端启动确认耗时。
3. 状态查询单次耗时。
4. 首次结果可见耗时。
5. SSH 断线恢复耗时。
6. App 重启到恢复 AgentRun 的耗时。
7. Tool Result 生成耗时。

验收要求：

1. 短命令不能因状态化执行明显变慢。
2. 轮询不能重复 SSH 握手。
3. 轮询不能触发模型请求。
4. 轮询不能持续写 AgentEntry。
5. 大日志不能全部载入 Android 内存。
6. 多会话任务不能阻塞服务器页面。

## 二十三、安全验收

1. Helper 固定子命令全部校验参数数量。
2. Helper 没有 docker $@ 一类透传。
3. 状态查询不能读取任意路径。
4. cancel-execution 不能接受任意 PID。
5. requestHash 不一致时拒绝接管。
6. 符号链接目录被拒绝。
7. Wrapper 状态写入使用原子替换。
8. Runtime 敏感文件完成准备后删除。
9. 状态和日志不会包含 SSH 凭据。
10. Host 与 Container 目录不能互相越界。

## 二十四、完成定义

全部满足后才将本文状态改为“已实施”：

1. 普通聊天、MCP、联网搜索和 VPS Agent 继续使用统一 AgentLoop。
2. foreground exec 在 SSH 断开后继续运行。
3. Android 恢复后能确认远端状态。
4. 长脚本最终结果能自动回填给 AI。
5. App 重启后原 AgentRun 可以继续。
6. 同一 Tool Call 不会重复执行。
7. background Tool 与远端进程状态可以同时表达。
8. UNKNOWN 只在远端状态确实无法验证时出现。
9. 用户停止可以安全取消 foreground 受管进程。
10. 活动任务在上下文压缩和新 AgentRun 中仍可被识别。
11. 七个 Computer Tool 数量不变。
12. SSH 凭据不离开手机。
13. 不新增 EveryTalk 云端后端。
14. 不新增 VPS 常驻 Agent 服务。
15. 不设置 VPS 资源配额。
16. Room 18 → 19 Migration Test 通过。
17. Wrapper 与 Helper 合约测试通过。
18. Container 断线恢复测试通过。
19. Host 断线恢复测试通过。
20. 真机验收由用户完成。

## 二十五、风险与取舍

### 25.1 状态目录占用磁盘

状态和日志会随 Workspace 保存。本文不设置磁盘配额，也不按时间自动删除。删除 Workspace 时只清理 EveryTalk 受管目录。

### 25.2 foreground 增加一次远端启动确认

状态化执行需要先确认 Execution 已创建。通过连接池、单 Channel Envelope 和快速状态查询控制额外耗时。

### 25.3 Host 恢复比 Container 复杂

Container 有固定 Helper 和受管目录。Host 必须同时处理 SSH 用户权限、显式 sudo 和进程组。Host 仍使用同一状态模型，但安全校验单独实现。

### 25.4 VPS 被用户手动修改

用户可以删除状态目录、停止 Container 或杀死进程。EveryTalk 在无法验证时进入 UNKNOWN，不猜测结果。

### 25.5 background 任务不会自动触发模型

后台服务可能在 AgentRun 完成后继续。没有新用户输入时不主动产生模型请求，避免耗费 Token 和打扰用户。

## 二十六、最终决策

1. 继续使用当前 Kotlin Pi Agent 架构。
2. Agent Session 使用聚合视图。
3. AgentRun、Workspace、ComputerExecution 和 VPS 状态分层。
4. 复用 ComputerExecution，不新增一对一 RemoteExecution 表。
5. ComputerExecution 增加独立 remoteStatus。
6. foreground exec 也使用远端持久状态。
7. background exec 保持立即返回。
8. App 重启先查询 VPS。
9. 模型不负责轮询。
10. UNKNOWN 保留为最后兜底。
11. 七个 Tool 保持不变。
12. 不引入云端后端。
13. 不引入 VPS 常驻 Agent 服务。
14. 不限制用户 VPS 资源。

该结构覆盖当前长脚本拿不到结果、App 重启丢失执行状态、后台任务无法续接和上下文无法稳定识别活动任务四类问题。
