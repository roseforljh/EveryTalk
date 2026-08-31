# EveryTalk Agent 执行期人类接力统一方案

## 1. 问题定义

### 1.1 用户真正遇到的卡点

服务器 Agent 已经开始执行任务后，经常会在中途遇到一个无法由当前 Agent 上下文直接解决的问题：

- 推送 GitHub 时没有权限，需要用户提供 Token、完成 OAuth 或确认设备登录。
- 建立 SSH 反向隧道时需要另一台 VPS，但当前 Run 只绑定了一台服务器。
- 执行 `sudo`、数据库管理、解密或部署命令时，远端要求输入密码。
- 安装软件、登录 CLI、配置云平台时要求 OTP、验证码或交互式确认。
- 浏览器登录、硬件确认、云厂商授权等动作必须由用户本人完成。

这些场景的共同点不是“缺一个特定密码”，而是：

> Agent 执行到一半，需要用户临时补充能力、授权能力或亲自完成一个动作，完成后还要沿着原来的 AgentRun 继续执行。

当前产品通常会让模型返回“我做不了，请人工操作”。这会造成三个损失：

1. 原任务上下文断掉，用户要重新描述任务。
2. 用户不知道应该在哪里输入，敏感信息容易被直接贴进聊天。
3. 人工操作完成后，Agent 不知道结果，也无法可靠续接原 Tool Call。

### 1.2 目标

本方案建立一个统一的“执行期人类接力” Module，统一处理：

1. Agent 主动请求用户介入。
2. Tool 在无副作用的前置阶段发现缺少能力。
3. 用户填写敏感字段、选择资源、完成授权或接管终端。
4. 本地安全 Adapter 把结果交给正确的执行目标。
5. 原 AgentRun 从暂停点恢复，继续原 Tool Call 或后续 Tool Call。
6. 在声明的信任模型内，Secret 不进入模型，也不能被 Agent 任意读取；Agent 只能调用受限 capability。
7. 敏感值不进入模型上下文、Tool Call 参数、Room 普通字段、聊天消息和日志。

### 1.3 非目标

本方案不做以下事情：

- 不为 GitHub、GitLab、AWS、数据库、SSH 隧道分别实现一套暂停流程。
- 不让模型直接读取或回显密码、Token、私钥、OTP。
- 不把所有凭据集中上传到 EveryTalk 官方服务器。
- 不让模型指定任意服务器、任意 PTY 或任意本地资源作为投递目标。
- 不按错误码盲目重放已经产生副作用的命令。
- 不把所有执行都改成长期驻留的远端 Agent 服务。

## 2. 当前实现与根因

### 2.1 当前已有的基础能力

项目已经具备统一方案需要的大部分底层能力：

| 能力 | 当前实现 | 结论 |
| --- | --- | --- |
| AgentRun 持久化 | `data/agent/AgentRunStore.kt` | 继续复用 |
| Tool Call、Tool Result、审批事件落库 | `data/agent/AgentRunStore.kt` | 继续复用 |
| 暂停后恢复原 Run | `data/agent/AgentLoop.kt`、`statecontroller/api/ApiHandler.kt` | 扩展语义 |
| 本地加密保存敏感值 | `data/computer/ComputerCredentialStore.kt`、`data/skill/SkillSecretStore.kt` | 统一访问入口 |
| PTY 读写 | `data/computer/ComputerSshClient.kt`、`ComputerTerminalManager.kt` | 增加用户接管入口 |
| Container 执行信封和输出脱敏 | `ComputerRuntimeEnvelope.kt`、`ComputerToolExecutor.kt` | 改为 capability proxy 投递 |
| 多服务器记录 | `ComputerRepository.kt`、`ComputerManager.kt` | 增加资源句柄选择 |

### 2.2 当前被硬编码的地方

当前暂停类型只有两种：

```kotlin
AgentPauseRequest.EnableAgent
AgentPauseRequest.SkillSecret
```

位置：[AgentModels.kt](../../app/src/main/java/com/android/everytalk/data/agent/AgentModels.kt:155)

控制工具只有：

```text
request_agent
request_skill_secret
```

位置：[AgentControlTools.kt](../../app/src/main/java/com/android/everytalk/data/agent/AgentControlTools.kt:7)

这使得协议天然只能表达“开启 Agent”和“提供 Skill 密钥”。GitHub 授权、第二台 VPS、sudo、OTP、浏览器确认没有统一入口。

### 2.3 工具失败没有进入暂停协议

`ComputerToolExecutor` 会把大多数异常包装成普通 Tool Result。只有执行结果未知时才进入 UNKNOWN 审批流程。`CREDENTIAL_MISSING`、`SUDO_REQUIRED`、外部登录缺失等情况不会自动生成“等待用户补能力”的状态。

因此模型拿到的只是一次失败结果，只能再次尝试、改变命令或回复用户“做不了”。

### 2.4 PTY 能输入，但输入路径不安全

PTY 已支持读、写、调整窗口和关闭。当前写入入口属于 `terminal` Tool，模型可以把密码或 OTP 作为 `input` 传进去。

这会带来三个问题：

1. 敏感内容可能进入 Tool Call 记录。
2. 敏感内容可能进入模型下一轮上下文。
3. 用户没有一个“接管当前终端”的界面。

### 2.5 现有专用暂停还有文案泄漏

`AgentLoop` 对所有 `AgentPauseRequest` 都使用“等待你确认开启 Agent”的状态文案，Skill 密钥请求也会走这条文案。这说明暂停协议的生命周期已经存在，业务语义还没有抽象。

## 3. 总体设计

### 3.1 威胁模型与信任 Seam

#### 可信 Module

~~~text
EveryTalk 应用内可信代码
AgentInterventionBroker
AgentInterventionPolicyRegistry
Secure Capability Store / Android Keystore
内置并经过验证的 Adapter
RunGateCoordinator / BindingResolver
~~~

#### 不可信输入

~~~text
LLM 输出
模型生成的 Tool 参数
Workspace 文件与 README
网页内容
远端 stdout / stderr
第三方脚本与项目代码
Prompt Injection 内容
模型生成的任意 shell 命令
~~~

#### 条件可信资源

~~~text
用户 VPS
已安装 CLI
SSH 目标
浏览器回调
远端 Helper
PTY 中的进程
~~~

条件可信资源必须经过 Host Key、签名、版本、进程来源、资源归属或 challenge 证明后，才能参与高敏感 capability 的 Fulfillment。

#### 远端权限边界

远端执行环境分成两类：

1. Agent 只拥有普通受限权限，无法读取其他用户进程、ptrace 目标或受保护 Helper。
2. Agent 拥有 root、ptrace、调试器或等价完全控制权限。

第二类环境无法依赖同一主机的进程级隔离保护 Secret。需要保证 Secret 不可被 Agent 读取的 capability，必须在 Agent 权限 Seam 之外履行，或使用不向该主机暴露长期 Secret 的委托机制。

文档不承诺在 Agent 完全控制的主机上保护已经落到该主机内存、文件、进程环境或文件描述符中的 Secret。

### 3.2 总体架构

~~~text
                     ┌─────────────────────┐
                     │      AgentLoop      │
                     └──────────┬──────────┘
                                │ CapabilityRequest / ModelHint
                                ▼
                ┌──────────────────────────────┐
                │ Intervention Policy Registry │
                │ capability / source policy   │
                │ trusted UI / target / plan   │
                └───────────────┬──────────────┘
                                │ TrustedInterventionRequest
                                ▼
                ┌──────────────────────────────┐
                │ AgentInterventionBroker      │
                │ atomic suspension / CAS      │
                │ fulfillment / reconciliation │
                │ continuation / binding       │
                └───────┬───────────┬──────────┘
                        │           │
                        ▼           ▼
             RunGateCoordinator   BindingResolver
             batch barrier        target / generation / attestation
                        │           │
                        └─────┬─────┘
                              ▼
               ┌────────────────────────────┐
               │ Trusted Semantic Adapters  │
               │ Git / SSH / Sudo / OAuth   │
               │ PTY / Registered Actions   │
               └─────────────┬──────────────┘
                             ▼
                   Human / Trusted Target
~~~

### 3.3 核心 Module

新增一个位于 `data/agent` 的深 Module：

```text
AgentInterventionBroker
```

它对外暴露以下 Interface：

~~~text
suspend(run, capabilityRequest, executionCheckpoint, trustedEvidence?, idempotencyKey): SuspensionId
resolve(suspensionId, resolutionNonce, expectedVersion, userResolution): ResolutionResult
reject(suspensionId, expectedVersion, reason): ResolutionResult
cancel(suspensionId, expectedVersion, reason): ResolutionResult
expire(suspensionId, expectedVersion, reason): ResolutionResult
reconcile(suspensionId, attemptId): ReconciliationResult
~~~

Binding、Adapter、Target Attestation 和 Continuation 由 Broker 调用 Policy Registry 后生成，不能由模型或普通 Tool 调用者传入。

`suspend()` 是创建暂停的唯一入口。内部在同一个 Room Transaction 中原子完成：

1. 写入 Suspension Record。
2. 把 AgentLoopState 更新为 `WAITING_TOOL_BATCH`，把目标 execution slot 更新为 `SUSPENDED`。
3. 写入 suspension event。
4. 绑定当前执行检查点、不可变 request hash 和 active suspension idempotency key。

事务失败时，三个写入全部回滚。不会出现“UI 有请求但 AgentRun 仍在执行”的状态分裂。

`active suspension idempotency key` 由 `run_id`、`turn_id`、`execution_slot`、`capability_id`、`target_binding`、`request_hash` 和 `execution_generation` 稳定生成。Room 对未进入终态的 Suspension 建立唯一约束或等价事务检查。相同请求再次 `suspend()` 时返回已有记录，不创建第二张接力卡片。后到的更高可信证据只能通过 CAS 更新原记录。

如果原记录已经 resolve、fulfill 或进入不可安全升级阶段，禁止覆盖原决策；由原 Continuation 进入明确失败或 `REPLAN_REQUIRED`，不得另建并行 Suspension。

`request()` 和 `pause()` 不作为公开的分离动作，内部只承担校验，不承担持久化。

它负责以下实现细节：

- 请求校验和目标绑定。
- Room 原子写入暂停记录。
- 用户输入的安全存储和生命周期。
- 用户接管、完成、拒绝、超时、取消的统一状态。
- 本地 Adapter 的选择和调用。
- 原 AgentRun 的恢复。
- 敏感数据清理和输出脱敏。

AgentLoop、UI、Computer Tool Executor 都只依赖这个 Module 的 Interface，不再直接判断 GitHub、sudo、Skill 或 PTY 的业务类型。

### 3.4 统一生命周期

~~~text
AgentLoopState
├─ RUNNING
├─ WAITING_TOOL_BATCH
├─ COMPLETED
├─ FAILED
└─ CANCELLED

ExecutionSlotState
├─ PENDING
├─ RUNNING
├─ SUSPENDED
├─ RESUMING
├─ COMPLETED
├─ FAILED
└─ UNKNOWN

SuspensionState
├─ WAITING_USER
├─ WAITING_USER_REENTRY
├─ RESOLUTION_RECEIVED
├─ FULFILLING
├─ DELIVERED
├─ DELIVERY_UNKNOWN
├─ RECONCILIATION_REQUIRED
├─ RECONCILING
├─ READY_TO_RESUME
├─ READY_TO_RESUME_WITH_FAILURE
├─ RESUMING
└─ RESUMED
~~~

三层状态职责不同。`SuspensionState` 是人类接力事实源，`ExecutionSlotState = SUSPENDED` 只表示槽位被一个或多个 Gate 阻塞，不复制接力内部细状态。`AgentLoopState` 只表示模型循环整体状态。三者由同一事务或严格映射规则更新；投影不一致时，以持久化的 `SuspensionState`、AgentRun terminal 状态和 execution checkpoint 为准，由 `RunGateCoordinator` 重建 slot 与 Barrier 投影。

`DELIVERY_UNKNOWN` 不能直接进入 `READY_TO_RESUME`。它先持久化为 `RECONCILIATION_REQUIRED`，再由唯一的 reconcile claim 进入 `RECONCILING`。用户拒绝、取消、过期和目标丢失会形成失败型 `ContinuationResult`，恢复被 suspend 的 slot，让 Agent 得到明确结果。`UNKNOWN` 只表示外部事实无法确认，不能转换为 Intervention。

Run 终止优先于全部 Suspension 状态。`AgentRunState = CANCELLED` 或 `TERMINATED` 后，任何旧 Suspension 都不能正常 resume；正在跨外部边界的 Fulfillment 仍必须对账，但只记录事实，不恢复 AgentLoop。

状态优先级固定为：

```text
AgentRun terminal
    > Approval / Intervention Gate
    > ExecutionSlotState
    > AgentLoopState resume
```

### 3.5 与现有审批的关系

现有 `AgentApprovalRecord` 已经能保存：

- `runId`
- 原始 Tool Call
- 后续待执行 Tool Call
- 请求 ID
- 决策状态
- 恢复时间

`AgentApprovalRecord` 只作为兼容迁移桥。长期领域模型改为 `AgentSuspensionRecord` 或 `AgentRunGateRecord`，共享账本和恢复基础设施：

~~~text
Run
 ↓
Gate / Suspension
 ↓
Decision / Resolution
 ↓
Continuation
~~~

业务语义分开：

~~~text
GatePayload.Approval
GatePayload.Intervention
~~~

- `Approval` 表示用户是否允许执行一个已知 Tool。
- `Intervention` 表示继续执行所需的 capability、用户动作或资源。

Intervention Resolution 不等价于 Side-effect Approval。一个 execution slot 可以同时存在 `Approval Gate` 和 `Intervention Gate`，只有 `Approval = ALLOWED` 且 `Capability = AVAILABLE` 才能执行。用户输入 sudo 密码不等于批准任意 sudo 命令，完成 GitHub OAuth 不等于批准任意 `git push`。Intervention 不能隐式满足 Approval，Approval 不能隐式创建 CapabilityGrant。`RunGateCoordinator` 负责组合多个 Gate，Prompt Injection 不能通过诱导用户输入凭据绕过 Approval Policy。

迁移阶段保留 `APPROVAL_REQUEST`、`APPROVAL_DECISION` 和 `WAITING_APPROVAL` 的数据库兼容性。迁移完成后由 `AgentSuspensionRecord` 作为长期领域模型。

同一账本承载两类记录，但不混淆业务语义：

1. Tool 执行前的副作用审批。
2. 执行期缺少能力时的人类接力。

两者共用持久化、并发锁和恢复基础设施。`UNKNOWN` 结果不能被当成“缺密码”，也不能自动重放。

## 4. Intervention Policy Registry

### 4.1 设计目的

模型会读取 Workspace 文件、README、脚本、网页和命令输出。这些内容都可能包含 Prompt Injection。模型不能因为看到一段文字，就让系统弹出一个可信密码窗口。

新增本地可信 Module：

~~~text
AgentInterventionPolicyRegistry
~~~

Registry 由应用内可信代码和经过验证的内置配置组成。Workspace 文件、远端脚本和模型都不能注册或修改策略。

### 4.2 模型能表达什么

模型只表达 capability 缺口和用户可见 reason：

~~~json
{
  "requested_capability": "git.push",
  "reason": "推送当前仓库需要 Git 认证"
}
~~~

模型不能表达：

- `PASSWORD`、`OTP`、`SERVER_SELECT` 等敏感 UI 类型。
- 字段 schema、delivery 类型、Adapter 名称。
- 服务器、PTY、Workspace 或文件目标。
- 是否长期保存凭据。
- 绕过用户确认的权限。

### 4.3 Registry 决定什么

Registry 根据 capability、当前 Tool、当前 Computer、Workspace、Run 权限和用户配置，决定：

~~~text
trusted_fields_schema
trusted_target_binding
trusted_delivery
trusted_adapter
trusted_scope
trusted_continuation
trusted_cancel_policy
~~~

`trusted_scope` 若涉及长期保存，只能决定 StoredAuthorization 的 `WORKSPACE` 或 `COMPUTER` 范围；执行期 Grant 的操作 scope 仍由 Broker 按当前 Run、Tool Call、slot 和 target 单独派生。

模型给出的 reason 只作为用户可见说明候选，必须经过长度限制、敏感信息过滤和本地策略校验。

### 4.4 Capability 目录示例

| Capability | 可信字段 | Target | Delivery | Continuation |
| --- | --- | --- | --- | --- |
| `git.push` | 已保存凭据选择、受保护授权 | 当前 Workspace 仓库 | Credential Helper、OAuth | `RETRY_TOOL` |
| `ssh.connect` | 服务器选择或临时连接资料 | 目标服务器资源 | SSH Connection Adapter | `VERIFY_THEN_RESUME` |
| `privilege.sudo.execute` | 一次性权限输入 | 已证明的 sudo challenge | Privilege Adapter | `CONTINUE_PTY` 或 `CONTINUE_EXECUTION` |
| `terminal.interaction` | 接管终端 | 已绑定 PTY | PTY Takeover Adapter | `CONTINUE_PTY` |
| `server.restart.confirm` | 完成确认 | Registry 注册的服务器重启动作 | Acknowledgement Adapter | `VERIFY_THEN_RESUME` |
| `skill.openai_api_access` | 已注册 Skill 授权字段 | 当前 Skill 资源 | 受限 Capability Adapter | `RETRY_TOOL` |

新增 capability 只增加 Registry 条目和 Adapter，不增加 AgentLoop 分支。

Capability ID 必须描述 Agent 获得的受限动作能力，例如 `git.push`、`ssh.connect`、`privilege.sudo.execute`。`SECRET`、`CREDENTIAL`、`PRIVILEGE` 只能作为分类标签，不能作为 capability ID。

### 4.5 请求来源等级

每个 capability policy 声明允许的请求来源：

~~~text
MODEL_HINT
EXECUTOR_PROVEN
SYSTEM_CHALLENGE
~~~

- `MODEL_HINT` 只表示模型认为缺少 capability，不能直接证明高敏感目标真实存在。
- `EXECUTOR_PROVEN` 表示可信 Executor 在 preflight 或受管执行中证明了 challenge、target 和执行事实。
- `SYSTEM_CHALLENGE` 表示 EveryTalk 本地可信代码创建的系统级请求。

`git.push` 可以接受 `MODEL_HINT`，再由 Registry 和 Git Adapter 校验仓库与目标。`privilege.sudo.execute` 必须要求 `EXECUTOR_PROVEN` 或 `SYSTEM_CHALLENGE`。模型主动请求 sudo 时，只允许转成普通 PTY 接管提示，禁止直接展示 sudo 密码框。

### 4.6 Target Attestation

高敏感 capability 在履行前必须附带本地可信的 `TargetAttestation`：

~~~text
attestation_id
source_level
execution_id
execution_slot
request_hash
target_binding_ref
binary_identity
process_lineage
challenge_nonce
attested_at
expires_at
~~~

sudo 密码不能投递到“看起来像 sudo”的任意 PTY。Executor 必须证明 challenge 来自受管的 `/usr/bin/sudo` 或已安装的可信权限 Helper，并绑定正确 execution ID、PTY、request hash 和进程来源。

单靠可执行文件路径和进程 lineage 无法抵抗已经完全控制远端主机的 root 攻击。此时不得承诺 Secret 在该主机内不可读，应改用 Agent 权限 Seam 外的委托机制或拒绝高敏感 capability。

### 4.7 注册动作与通用说明

可信 Acknowledgement UI 只允许使用 Registry 中注册的动作，例如：

~~~text
github.device_login
cloudflare.authorize_domain
server.restart.confirm
~~~

每个动作由 Registry 提供可信标题、说明模板、目标、风险等级和验证计划。模型只能填写受限参数。

通用 `external.action` 不能用于凭据、支付、权限、删除或其他高风险操作。保留通用说明时，UI 必须明确标记“内容来自 Agent，未经系统验证”，并使用低信任样式，避免原生安全 UI 为 Prompt Injection 增加可信度。

## 5. 统一请求模型

### 5.1 请求对象

模型只提交最小的 `CapabilityRequest`。完整的 `TrustedInterventionRequest` 由本地 Policy Registry 生成：

~~~text
CapabilityRequest
├── requested_capability
├── reason_safe
└── user_visible_context

TrustedInterventionRequest
├── suspension_id
├── run_id
├── run_generation
├── turn_id
├── request_id
├── tool_call_id
├── execution_slot
├── request_hash
├── active_suspension_idempotency_key
├── capability_id
├── request_source
├── policy_version
├── adapter_contract_version
├── trusted_fields_schema
├── target_binding_ref
├── binding_generation
├── target_attestation
├── delivery_plan
├── scope
├── continuation
├── resolution_material_kind
├── precondition_fingerprint
├── resource_epoch
├── execution_generation
├── cancel_policy
└── expires_at
~~~

### 5.2 字段定义

#### `reason_safe`

面向用户的原因说明。必须说清楚：

- 当前任务进行到了哪一步。
- 缺少什么能力。
- 用户需要做什么。
- 完成后 Agent 会继续做什么。

原因来自模型或 Executor 时都要经过长度限制、敏感信息过滤和本地化处理。禁止把原始命令、完整日志、Token 或密码放进原因文本。

#### `trusted_fields_schema`

字段 schema 由本地可信 Policy Registry 生成：

~~~text
field_id
label
trusted_input_kind
required
sensitive
validation
options
~~~

模型不能提交字段 schema、输入类型或字段值。字段值由可信 UI 直接交给 Broker。

#### `target_binding_ref`

Room 保存可恢复的非敏感 Binding 引用和完整性校验信息：

~~~text
binding_type
binding_entity_id
binding_generation
binding_digest_or_mac
owner_scope
~~~

App 重启后由 `BindingResolver` 根据 entity ID 重新解析资源，再校验 generation、digest、owner、Run scope 和 execution slot。PTY 属于不可恢复 Binding，进程死亡后直接进入 `TARGET_LOST`。

模型不能自行拼接、替换或扩大 Binding。模型可见的 `resource_ref` 只用于逻辑引用，不携带权限。

#### `CapabilityGrantId`

真正的授权标识只存在于 Broker 和 AgentRun execution context 中，不返回模型。Tool Executor 根据当前 Run、Tool Call、slot、scope 和 Policy 自动解析授权。

模型只知道 capability 是否可用。知道某个 `resource_ref` 不等于拥有调用权限，授权来自 Broker Binding 和 Policy 校验。

#### `delivery`

投递方式由 Policy Registry 选择，不是模型可自由控制的执行参数：

~~~text
CAPABILITY_PROXY
PTY
SSH_CONNECTION
OAUTH_CALLBACK
RESOURCE_HANDLE
ACKNOWLEDGEMENT
~~~

任意 ENV、任意文件和模型可控 stdin 不属于敏感 capability 的默认投递方式。

#### `scope`

StoredAuthorization 的长期保存范围：

```text
WORKSPACE
COMPUTER
```

执行期 CapabilityGrant 的 `scope` 只表示本次 Lease 的操作范围，不表示长期授权范围。它默认是当前一次执行的短 Lease，具体 TTL、次数和 target 由 Registry 决定。StoredAuthorization 扩大到 `WORKSPACE` 或 `COMPUTER` 必须明确展示保存范围，并经过用户确认。

#### `resolution_material_kind`

可信 Registry 为每个 Intervention 标注 `ResolutionMaterialKind` 的解决材料生命周期：

```text
NONE
EPHEMERAL
DURABLE_REFERENCE
```

`EPHEMERAL` 表示密码、OTP 等一次性明文只存在于 Broker 和 Adapter 的最小生命周期，绝不写入 Room。`DURABLE_REFERENCE` 只保存指向 Secure Store 的非敏感引用。`NONE` 不需要用户材料。

### 5.3 分类标签的定位

`SECRET`、`CREDENTIAL`、`CONNECTION`、`PRIVILEGE`、`TERMINAL_INPUT`、`USER_ACTION` 只能作为 Policy Registry 的分类标签，不能让 AgentLoop 为每个标签写一套业务分支。

真正可扩展的单位是：

```text
capability 目录条目 + trusted fields schema + target binding + delivery Adapter + Continuation
```

这样新增云厂商授权只增加可信 Registry 条目和 Adapter，暂停、持久化、恢复和清理全部复用。

### 5.4 Policy、Adapter 与 Binding 版本绑定

Suspension 创建时必须绑定：

~~~text
policy_version
adapter_contract_version
binding_generation
~~~

App 升级只要改变执行语义、字段 schema、Adapter 合约或恢复规则，就必须提升对应的 `policy_version` 或 `adapter_contract_version`。仅 UI 展示变化且协议版本兼容时，才允许继续使用旧 Suspension。

恢复时由 `Policy Registry` 和 `BindingResolver` 做兼容判断：

- 版本兼容：沿原 Continuation 继续。
- Policy、Adapter 合约或 Binding generation 不兼容：持久化 `failure_code = POLICY_STALE`，Continuation 固定转为 `REPLAN_REQUIRED`，或由可信代码重新创建安全 Intervention。
- 禁止使用新版 Policy 静默重新解释旧 Suspension 的安全决策、字段 schema、target、delivery 或 Continuation。

旧 Suspension 的 trusted request、Target Attestation、VerificationPlanId 和 Operation Plan 都属于原决策的一部分。版本不兼容时必须显式失败或重新规划，不能默认套用最新策略。

### 5.5 StoredAuthorization 与 CapabilityGrant

两类材料必须正式分层：

~~~text
StoredAuthorization
├─ authorization_id
├─ provider
├─ credential_reference
├─ user_consent_scope
├─ workspace_id / computer_id
├─ issued_at
├─ expires_at
├─ revoked
└─ generation

CapabilityGrant
├─ grant_id
├─ capability
├─ run_id
├─ tool_call_id
├─ execution_slot
├─ operation
├─ target_binding
├─ audience
├─ scope
├─ issued_at
├─ expires_at
├─ max_uses
├─ generation
└─ revoked
~~~

`StoredAuthorization` 表示长期保存的认证材料或用户授权，可以在用户明确同意的 `WORKSPACE` 或 `COMPUTER` 范围内跨 Run 存在。OAuth refresh token、GitHub Token、SSH credential 属于 StoredAuthorization 或 Secure Credential Store，不属于模型可见 Grant。模型不能直接使用、读取或引用 StoredAuthorization。

`CapabilityGrant` 永远是由 Broker 根据 StoredAuthorization 或一次性用户输入派生的执行期短 Lease。StoredAuthorization 是 `WORKSPACE` scope 也不会自动变成跨 Run bearer token。每个新 Run、Tool Call 和 execution slot 都必须由 Broker 派生新的 Grant。`CapabilityGrantId` 虽然不返回模型，仍然代表真实权限，不能因为认证成功就变成永久授权。

~~~text
capability
run_id
tool_call_id / execution_slot
target_binding
operation
audience
scope
issued_at
expires_at
max_uses
generation
revoked
~~~

硬约束：

1. Grant 必须绑定明确 audience、scope、TTL、operation 和 target binding。
2. `ONCE` capability 默认 `max_uses = 1`。
3. Grant 可以被撤销，撤销后任何 Adapter 都不能继续使用。
4. GitHub repo A 的授权不能自动扩大为 repo B。
5. 当前 Run 的 Grant 不能跨 Run 复用。跨 Run 复用只能由 Broker 根据用户明确授予的 StoredAuthorization 重新派生新的 Grant。
6. Grant generation 必须和 Binding generation、Policy version 兼容。
7. Tool Executor 每次使用前都校验 TTL、次数、revoked、audience、scope 和 execution slot。
8. 模型只能知道 capability 是否可用，不能持有真正的授权 token。
9. Grant 使用必须执行原子 claim/consume：同一 Grant 的资格校验、`max_uses` 扣减、generation 标记和本次投递绑定在同一持久化 CAS 或等价原子操作中，失败调用不得继续使用该 Grant。

## 6. Agent 如何发起请求

### 6.1 模型主动请求

增加一个统一控制工具：

```text
request_capability
```

模型只提交 `requested_capability`、`reason_safe` 和可选的用户可见上下文。

模型不能提交敏感字段 schema、输入类型、delivery、Adapter、target、敏感字段值或保存范围。

Policy Registry 根据 capability 和当前执行上下文生成可信 Intervention。模型不能因为读取了恶意 Workspace 内容，就自由触发密码表单或选择任意投递目标。

系统提示词只说明“遇到执行期外部能力缺口时调用统一工具”，不再把 Skill 密钥写成唯一特例。

### 6.2 Executor 发现缺少能力

工具执行器不能把所有错误都转成暂停。只有满足以下条件才生成 `InterventionRequired`：

1. 错误发生在明确的 preflight 阶段。
2. 远端目标尚未启动有副作用的动作。
3. 缺少的能力可以被结构化描述。
4. 当前 Tool Call 具备安全恢复条件。
5. 原始请求已经绑定了不可变的 request hash。

以下情况必须继续走失败或 UNKNOWN：

- 命令已经启动但结果未知。
- 远端可能已产生副作用。
- 需要猜测命令是否成功。
- 连接状态无法确认。
- 用户拒绝后不能安全重试。

## 7. Continuation 模型

### 7.1 为什么必须区分

用户完成介入后，下一步动作可能完全不同：

| Continuation | 适用情况 | 恢复动作 |
| --- | --- | --- |
| `RETRY_TOOL` | Tool 尚未启动，只是 preflight 缺 capability | 使用原 `request_hash` 重试一次 |
| `CONTINUE_TOOL` | Tool 已启动且仍由执行器管理 | 继续已有 Tool Execution |
| `CONTINUE_EXECUTION` | 远端进程已有 `execution_id` | 继续对账同一个远端 Execution，禁止新建命令 |
| `CONTINUE_PTY` | 原 PTY 等待密码、OTP 或确认 | 向绑定的原 PTY 投递用户动作 |
| `RESUME_AGENT_LOOP` | OAuth 或外部动作已完成，Tool 不需要重放 | 回填 capability 可用结果，继续模型循环 |
| `VERIFY_THEN_RESUME` | 用户声称外部动作完成，需要验证 | 先执行固定验证，再恢复原槽位 |
| `REPLAN_REQUIRED` | 等待期间 Workspace、目标或远端前置条件已变化 | 禁止重试，把变化事实交给 Agent 重新规划 |

把所有情况都写成“恢复原 Tool Call”会导致重复副作用、OTP 失效、OAuth 重复授权和并行 Tool 整批重放。

### 7.2 Continuation 的可信来源

Continuation 由 Broker 和 Policy Registry 根据执行事实生成，模型不能修改。至少绑定：

~~~text
turn_id
tool_call_id
execution_slot
request_id
request_hash
continuation_kind
execution_id / PtyBindingRef / BindingRef
precondition_fingerprint
resource_epoch
execution_generation
~~~

### 7.3 `RETRY_TOOL` 前置条件

`request_hash` 只能证明 Tool 参数没变，不能证明世界状态没变。`RETRY_TOOL` 还必须比较 Adapter 生成的可信前置条件：

~~~text
tool_call_hash
workspace_revision
target_binding_generation
resource_epoch
remote_precondition
~~~

例如 `git push` 在等待 OAuth 期间，HEAD、working tree 或 remote ref 可能变化。恢复时前置条件不一致，Continuation 转为 `REPLAN_REQUIRED`，禁止自动执行原 Tool。

只有 Adapter 能稳定生成和验证前置条件时才允许 `RETRY_TOOL`。任意 shell 无法证明世界状态未变化时，默认进入 `REPLAN_REQUIRED`。

示例：

~~~kotlin
sealed interface AgentContinuation {
    data class RetryTool(val toolCallId: String, val requestHash: String) : AgentContinuation
    data class ContinueTool(val toolExecutionRef: ToolExecutionRef) : AgentContinuation
    data class ContinueExecution(val executionId: String) : AgentContinuation
    data class ContinuePty(val ptyBindingRef: PtyBindingRef) : AgentContinuation
    data class ResumeAgentLoop(val resultBinding: String) : AgentContinuation
    data class VerifyThenResume(val verificationPlanId: VerificationPlanId) : AgentContinuation
    data class ReplanRequired(val reasonCode: String) : AgentContinuation
}
~~~

`VerificationPlanId` 由可信 Registry 生成并指向固定验证计划，不能包含模型提供的任意命令。

## 8. Adapter 设计

### 8.0 Adapter Interface

Adapter 只接收可信 Registry 生成的请求和本地 resolution，不接收模型自由构造的目标：

~~~text
validate(binding, trustedRequest): ValidationResult
present(trustedRequest): UiProjection
fulfill(binding, protectedResolution): FulfillmentResult
reconcile(binding): FulfillmentReconciliation
cleanup(binding): CleanupResult
~~~

Adapter 必须返回履行事实和可验证的状态。无法确认是否已经投递时返回 `DELIVERY_UNKNOWN`，不得自行重放。

### 8.1 Secret Adapter

适用于 GitHub Token、数据库密码、云平台密钥等：

1. 字段和 UI 类型由 Policy Registry 决定。
2. 明文只进入 Broker 的短生命周期内存。
3. 需要保存时写入 Android Keystore 封装的本地存储。
4. 运行时只生成 Broker 内部的 CapabilityGrantId，Agent 不获得 Secret 或授权 token。
5. Git 通过 Credential Helper、OAuth provider 或 brokered subprocess 完成认证。
6. 禁止把 Secret 放入 Agent 可任意读取的 ENV、文件或模型可控 stdin。
7. Tool Result 只返回 capability 可用状态，不返回正文。
8. 完成、失败、取消、超时后清理可控明文和一次性句柄。
9. `EPHEMERAL` resolution 在 `RESOLUTION_RECEIVED` 后若尚未开始 fulfillment 即丢失，重启只能进入 `WAITING_USER_REENTRY`；不把一次性明文写入 Room。

### 8.1.1 Capability Proxy

Capability Proxy 不能是“带 Secret 的受控 shell”，必须是受限语义操作，例如 `git.push`、`git.fetch`、`ssh.connect`。禁止提供 `runCommandWithSecret(command, secret)`。

每个语义 Adapter 必须固定或约束：

~~~text
trusted executable
credential protocol
operation type
parameter allowlist
target binding
repository / resource binding
network destination policy
output sanitizer
~~~

对于必须依赖环境变量或配置文件的第三方程序，使用专用 brokered subprocess：

1. Broker 验证 capability 和 Registry 生成的固定 Operation Plan。
2. 受控子进程在最小范围内获得 Secret。
3. Agent shell 不继承该 Secret 环境，也不能读取临时文件。
4. 子进程只返回经过限制和脱敏的结果。
5. 子进程履行状态不确定时进入 `DELIVERY_UNKNOWN`，禁止重新启动。

Brokered subprocess 不接受模型指定的任意 executable 或脚本。Agent 生成的 Python、Shell、Node.js 程序不能进入携带 Secret 的进程。

受控子进程与 Agent 位于同一 Linux 用户、同一 PID 可见范围或 Agent 拥有 root/ptrace 时，不能承诺 Secret 不可读取。此类 capability 必须移到 Agent 权限 Seam 外履行，或拒绝投递长期 Secret。

### 8.2 SSH Connection Adapter

适用于第二台 VPS、跳板机、反向隧道目标等：

1. UI 展示当前账户已配置的服务器列表。
2. 用户选择已有服务器时只在 Broker 内创建 BindingRef，模型只看到非授权性 resource ref。
3. 用户需要临时连接时，在受保护表单输入地址、端口、用户名和凭据。
4. 临时凭据默认只属于当前 Run，不写入服务器长期配置。
5. Adapter 在本地校验 Host Key 后建立连接。
6. Agent 只看到连接可用或连接失败；需要区分资源时只返回非授权性 `resource_ref`，不看到私钥和密码。
7. `ComputerRequestContext` 继续表示当前主服务器，第二台服务器作为受控资源句柄传入具体 Tool Adapter。

### 8.3 Privilege Adapter

适用于 sudo、云平台高权限确认、系统管理员授权：

1. 优先使用已经保存且明确授权的本地权限 capability。
2. 缺少时由可信 UI 展示一次性受保护输入。
3. 密码不进入模型 Tool Call、Room、聊天、日志和远端命令参数。
4. 只接受 `EXECUTOR_PROVEN` 或 `SYSTEM_CHALLENGE` 来源。
5. 必须验证 `TargetAttestation`、execution ID、PTY Binding、request hash、challenge nonce 和执行来源。
6. 只允许投递到已经证明的 sudo challenge 或专用权限 Helper。
7. 投递状态不确定时进入 `DELIVERY_UNKNOWN`，禁止再次输入。

模型主动提出 `privilege.sudo.execute` 时，Registry 只能返回普通终端接管或拒绝。没有可信 Executor 证明时，禁止弹出 sudo 密码框。

### 8.4 PTY Takeover Adapter

适用于 OTP、交互式确认、CLI 登录和安装脚本：

1. Agent 先通过 PTY 启动命令。
2. PTY 输出被识别为需要用户接力，或者 Agent 主动请求接管。
3. Broker 暂停模型继续调用 `terminal.write`。
4. UI 打开同一个终端会话，显示经过脱敏的输出。
5. 用户输入直接写入 PTY，不经过模型上下文。
6. 用户点击“完成”后，Adapter 先 reconcile，再按 `CONTINUE_PTY` 恢复。
7. 用户点击“取消”后关闭或保留 PTY，具体行为由可信 cancel policy 决定。

当前 PTY 只存在进程内。App 重启后不能伪造原 PTY 仍然存在，必须显示终端失效并要求重新开始或由远端 `tmux`、`screen` 等持久会话 Adapter 接管。投递过程中崩溃时必须先 reconcile，不能再次输入密码或 OTP。

PTY 输出使用双视图：

~~~text
Raw PTY Stream
├── Human View
└── Sanitized Agent View
~~~

Broker 为敏感输入创建 `SensitiveInputRedactionWindow`。接管期间暂停 Agent 可见 transcript；完成后从可信同步点恢复。随后投影给 Agent 的内容要过滤敏感输入的精确回显、CR/LF 变体和常见提示回显。原始用户输入和未脱敏窗口不能进入 Agent Ring Buffer、Tool Result 或模型上下文。

单纯字符串替换无法覆盖所有终端变换。无法确认同步点或脱敏完整性时，丢弃该窗口的 Agent 可见 transcript，只返回“用户交互已完成”的安全状态。

### 8.5 OAuth / Browser Adapter

适用于 GitHub、云厂商和需要网页登录的授权：

1. Broker 生成仅供本地校验的一次性 OAuth state，不返回模型。
2. 授权请求必须启用 PKCE。`code_verifier` 是敏感 OAuth material，不进入模型、普通 Room 字段、日志、聊天或通知；为支持 App 回收，只能写入 Secure Store，Room 只保存 `verifier_reference` 和 `verifier_generation`。
3. redirect URI、callback 和 `code_verifier` 必须绑定到当前 App、OAuth client、capability、Run 和 target binding。
4. state、authorization code、access token 和 refresh token 都必须单次消费或受保护保存。
5. callback replay、state 不匹配、code 重放和过期请求直接拒绝。
6. OAuth state 必须有明确 TTL；App 被回收后只能根据持久化的安全 state 元数据恢复，不能复用已经消费或过期的 callback。
7. 回调只返回授权结果和本地 capability 状态，不返回模型可见的授权 token。
8. authorization code、access token、refresh token 不进入模型、Room 普通字段、聊天、通知和日志。
9. Access Token 由本地安全存储或受控凭据 Adapter 管理。
10. 模型只看到授权成功、授权失败或用户取消。

OAuth 完成、取消或过期后清理 `code_verifier`。replay、generation、state、client 或 redirect URI 任一校验失败都直接拒绝，不创建 Grant。App 恢复只能读取 Secure Store 中仍有效的 verifier 引用，不能复用已消费或过期材料。

### 8.6 Acknowledgement Adapter

适用于用户已经在外部控制台完成 Registry 注册动作的场景：

1. Registry 提供可信标题、说明模板、目标和验证计划。
2. 用户点击“已完成”。
3. Broker 将确认写入当前 Intervention。
4. Agent 继续执行验证 Tool。
5. 验证失败时重新进入同一个 Broker 流程，不创建新类型。

通用 Agent 文本只能使用低信任说明卡片，不得伪装成系统验证动作，也不得用于凭据、支付、权限、删除和其他高风险操作。

## 9. 数据与隐私设计

### 9.1 Room 允许保存的内容

```text
suspension_id
run_id
run_generation
turn_id
tool_call_id
execution_slot
request_id
request_hash
active_suspension_idempotency_key
capability_id
request_source
policy_version
adapter_contract_version
title
reason_safe
trusted_fields_schema
binding_type
binding_entity_id
binding_generation
binding_digest_or_mac
attestation_id
delivery_type
continuation_kind
reconciliation_phase
resolution_material_kind
precondition_fingerprint
resource_epoch
execution_generation
scope
status
row_version
resolution_nonce_hash
fulfillment_attempt_id
resume_attempt_id
stored_authorization_id
verifier_reference
verifier_generation
created_at
expires_at
resolved_at
failure_code
```

### 9.2 Room 禁止保存的内容

- 密码、Token、私钥、私钥口令、OTP。
- 完整环境变量值。
- Intervention Record 中复制的完整命令。
- 完整终端输入。
- 浏览器授权码。
- 未脱敏 stdout、stderr。
- 可反推出真实服务器凭据的句柄原文。
- StoredAuthorization 的 credential 原文、OAuth `code_verifier` 和其他 Secure Store 明文。

### 9.3 模型可见结果

模型只能收到结构化结果：

```json
{
  "resolved": true,
  "capability": "git.push",
  "available": true,
  "message": "用户已完成授权，可继续执行"
}
```

Secret 不进入模型，也不能被 Agent 任意读取。模型只看到 capability 状态，不持有真正的 `CapabilityGrantId`。

需要让模型区分多个资源时，返回非授权性的逻辑 `resource_ref`。`resource_ref` 不能作为 bearer authority，Tool Executor 仍要根据 Broker Binding、Run、Tool Call、scope 和 Policy 解析权限。

恢复原 Tool Call 仍需要原始 Agent Tool Call。原 Agent Tool Call 按 `AgentRunStore` 既有恢复规则保存，但 Tool Call 参数不得包含任何敏感值。

准确表述：

> Intervention Record 不复制完整命令；原 Agent Tool Call 按 AgentRunStore 既有恢复规则保存，但 Tool Call 参数不得包含任何敏感值。

### 9.4 日志和审计

允许记录：

- Intervention 类型标签。
- 目标类型，不记录目标原文。
- 成功、拒绝、取消、超时、失败。
- 耗时和错误码。

禁止记录：

- 敏感字段正文。
- 完整命令、完整输出、完整终端输入。
- Token、密码、OTP、私钥和授权码。

### 9.5 Android/JVM 敏感内存

不承诺 Android/JVM 内部副本能够绝对物理清零。实际规则：

- 尽可能使用可清零的 mutable buffer，例如 `CharArray` 或 `ByteArray`。
- 尽可能缩短明文生命周期。
- 不持久化、不写日志、不进入模型、不进入聊天和 Room。
- UI 提交后立即清空可控状态。
- Adapter 使用完成后主动清零可控 buffer。
- 明确记录 Compose、系统输入法和 GC 可能产生不可控副本，不能做绝对清零承诺。

## 10. 状态机与恢复规则

### 10.1 状态

~~~text
REQUESTED
    ↓
WAITING_USER
    ├── resolve → RESOLUTION_RECEIVED
    └── reject / cancel / expire / target lost
                   ↓
        READY_TO_RESUME_WITH_FAILURE

RESOLUTION_RECEIVED + EPHEMERAL
    └── cold start before fulfillment → WAITING_USER_REENTRY

RESOLUTION_RECEIVED
    ↓ CAS fulfillment claim
FULFILLING
    ├── DELIVERED → READY_TO_RESUME
    └── DELIVERY_UNKNOWN
               ↓ 持久化
        RECONCILIATION_REQUIRED
              ↓ reconcile claim
          RECONCILING
               ├── DELIVERED → READY_TO_RESUME
               ├── NOT_DELIVERED + EPHEMERAL_LOST → WAITING_USER_REENTRY
               ├── NOT_DELIVERED → READY_TO_RESUME_WITH_FAILURE
               ├── UNKNOWN → DELIVERY_UNKNOWN / USER_DECISION_REQUIRED
               └── USER_DECISION_REQUIRED

READY_TO_RESUME / READY_TO_RESUME_WITH_FAILURE
    ↓ CAS resume claim
RESUMING
    ├── RESUMED
    ├── FAILED
    └── UNKNOWN / RECONCILIATION_REQUIRED（resume phase）
~~~

`RECONCILIATION_REQUIRED` 是持久化的待对账标记，适用于 Fulfillment 和 Resume 两个阶段；记录中的 `reconciliation_phase` 分别取 `FULFILLMENT` 或 `RESUME`。`RECONCILING` 是获得 CAS claim 后的进行中状态，二者不能混用。

`USER_DECISION_REQUIRED` 只有一个统一含义：系统已经掌握部分外部事实，但无法安全地自动决定下一步，需要用户明确选择继续、放弃或重新规划。它不是 capability 缺失的别名，也不表示执行事实本身未知；执行事实未知仍使用 `UNKNOWN` 或 `DELIVERY_UNKNOWN`。

`FULFILLING` 表示 Adapter 正在跨越外部边界投递动作。App 崩溃、连接中断或 Adapter 超时后必须先 `reconcile()`，且至少返回 `DELIVERED`、`NOT_DELIVERED` 或 `UNKNOWN`。不能因为看不到完成记录就重新投递。`RESOLUTION_RECEIVED + EPHEMERAL` 在 fulfillment 尚未开始时如果明文已丢失，必须回到 `WAITING_USER_REENTRY`，不能假装仍可履行、标记 FAILED、标记 UNKNOWN 或自动重试。

### 10.1.1 状态转换表

| 当前状态 | 允许命令 | 下一状态 | 约束 |
| --- | --- | --- | --- |
| `WAITING_USER` | `resolve` | `RESOLUTION_RECEIVED` | resolution nonce 只能消费一次 |
| `RESOLUTION_RECEIVED` + `EPHEMERAL` | 冷启动发现明文已丢失且尚未 claim fulfillment | `WAITING_USER_REENTRY` | 不能伪造继续履行、失败或自动重试 |
| `WAITING_USER_REENTRY` | `resolve` | `RESOLUTION_RECEIVED` | 必须重新输入新的 resolution，并轮换 `resolution_nonce`；旧 nonce 立即失效 |
| `WAITING_USER` | `reject/cancel/expire` | `READY_TO_RESUME_WITH_FAILURE` | 生成失败型 ContinuationResult |
| `RESOLUTION_RECEIVED` | `claimFulfillment` | `FULFILLING` | CAS 只能一个 attempt 获胜 |
| `FULFILLING` | Adapter 完成 | `DELIVERED` | 保存可验证 delivery fact |
| `FULFILLING` | 超时、崩溃、取消请求 | `DELIVERY_UNKNOWN` | 禁止直接取消、过期或重投 |
| `DELIVERY_UNKNOWN` | 持久化恢复标记 | `RECONCILIATION_REQUIRED` | 只记录待对账事实，不执行重投 |
| `RECONCILIATION_REQUIRED` | `claimReconcile` | `RECONCILING` | CAS 只能一个 reconcile attempt 获胜 |
| `RECONCILING` | `DELIVERED` | `DELIVERED` → `READY_TO_RESUME` | 记录可验证 delivery fact |
| `RECONCILING` | `NOT_DELIVERED` + `EPHEMERAL` 已丢失 | `WAITING_USER_REENTRY` | 重新请求用户输入，不重放旧明文 |
| `RECONCILING` | `NOT_DELIVERED` | `READY_TO_RESUME_WITH_FAILURE` | 生成明确失败型 ContinuationResult |
| `RECONCILING` | `UNKNOWN` | `DELIVERY_UNKNOWN / USER_DECISION_REQUIRED` | 不能转换为普通 Intervention 或自动重投 |
| `READY_TO_RESUME` | `claimResume` | `RESUMING` | CAS 只能一个 resume attempt 获胜 |
| `RESUMING` | 恢复完成 | `RESUMED` | 只恢复目标 execution slot |
| `RESUMING` | 结果不确定 | `RECONCILIATION_REQUIRED`（resume phase） | 禁止再次恢复 |

`REJECTED`、`CANCELLED`、`EXPIRED` 和 `TARGET_LOST` 都必须形成 `ContinuationResult`，让 Agent 得到明确失败结果。它们不能让 AgentRun 永久停在等待状态。

### 10.2 用户解决请求

1. UI 提交 `suspension_id`、字段值或用户动作结果。
2. Broker 从 Room 重新读取待处理记录，不信任 UI 内存副本。
3. 校验 Run、会话、字段 schema、目标句柄、execution slot、过期时间和一次性消费状态。
4. 敏感值直接交给 Adapter，不写入 Resolution 文本。
5. 写入 `RESOLUTION_RECEIVED`。只有 resolution material 仍可用且 Run 非 terminal 时，才能 claim 并进入 `FULFILLING`；`EPHEMERAL` 明文丢失时转入 `WAITING_USER_REENTRY`。
6. Adapter 履行成功后写入最小化 resolution metadata，进入 `DELIVERED` 和 `READY_TO_RESUME`。
7. 按 Continuation 恢复正确的 Tool 或 AgentLoop，禁止重新生成不同的请求参数。

### 10.3 Durable CAS Claim

事务能保证一组写入同时成功，CAS claim 负责保证并发入口中只有一个消费者执行副作用。`suspend()` 先按 active suspension idempotency key 做唯一查找，再由同一事务创建或返回记录。resolve、fulfill、resume 分别 claim：

~~~text
resolution_nonce
fulfillment_attempt_id
resume_attempt_id
row_version
run_generation
~~~

示意：

~~~sql
UPDATE agent_suspensions
SET state = 'FULFILLING',
    fulfillment_attempt_id = :attemptId,
    row_version = row_version + 1
WHERE id = :id
  AND state = 'RESOLUTION_RECEIVED'
  AND row_version = :expectedVersion
~~~

只有 `affectedRows == 1` 的调用获得 Fulfillment 权限。OAuth callback、多个窗口和重复点击同时到达时，其余调用读取获胜 attempt 的状态，不能再次履行。

所有 claim 都必须同时满足 `run_generation == expected` 且 AgentRun 非 terminal。Run 取消时递增 generation，使旧 claim 即使持有内存引用也不能继续投递。

Resume 使用独立 `resume_attempt_id` 和 CAS。Fulfillment 已幂等不代表 Resume 自动幂等，两阶段必须分别 claim、分别 reconcile。

### 10.4 App 重启

- Room / AgentRunStore 中的持久状态是 source of truth。内存事件、Flow、wake-up 和通知只是优化，允许丢失。
- App 重启或进程恢复后，`RunGateCoordinator` 必须主动扫描并重新 claim：

~~~text
READY_TO_RESUME
RESUMING
RESOLUTION_RECEIVED
DELIVERED
RECONCILIATION_REQUIRED / RECONCILING
WAITING_USER
WAITING_USER_REENTRY
FULFILLING
DELIVERY_UNKNOWN
以及其他未进入终态的 Suspension
~~~

- `READY_TO_RESUME` 已持久化但唤醒 AgentLoop 前崩溃时，扫描必须重新获取 resume CAS claim。
- 已有 `resume_attempt_id` 的记录先根据 attempt 状态 reconcile，不能无条件创建第二次恢复。
- Room 中的 `WAITING_USER` 请求重新投影到统一 UI。
- Room 中的 `WAITING_USER_REENTRY` 请求重新投影为“请重新输入一次性凭据”，不恢复旧明文。
- `RESOLUTION_RECEIVED` 且 `resolution_material_kind = EPHEMERAL` 时，如果 fulfillment 尚未 claim 或明文已丢失，必须转为 `WAITING_USER_REENTRY`，重新请求用户输入，不能标记 FAILED、UNKNOWN 或自动重试。
- 进入 `WAITING_USER_REENTRY` 时必须轮换 `resolution_nonce` 并只保存新 nonce 的哈希；旧 nonce 的 resolve 请求全部拒绝。
- `DURABLE_REFERENCE` 只从 Secure Store 读取非敏感引用，再由 Broker 为当前 Run、Tool Call 和 slot 重新派生短期 CapabilityGrant。
- StoredAuthorization 可以跨 Run 保存，但每个新 Run 仍必须派生新的 Grant；一次性内存 Secret 不恢复。
- PTY Binding 属于不可恢复资源，默认标记 `TARGET_LOST`，禁止伪造恢复。
- SSH Connection 通过 `BindingResolver` 根据 BindingRef 重新解析，并校验 Host Key、generation、digest、owner 和 scope。
- OAuth state 超时后重新发起授权。
- `FULFILLING` 状态必须调用 Adapter `reconcile()`。
- `DELIVERY_UNKNOWN` 状态不能自动重新投递。
- 若 AgentRun 已是 `CANCELLED` 或 `TERMINATED`，启动扫描只执行安全清理和事实对账，撤销可撤销 Grant/Lease，不恢复 AgentLoop。

### 10.5 连接中断

- 已开始的远端进程先按 `ComputerExecution` 对账。
- 结果未知时进入 UNKNOWN 流程，不进入 Intervention。
- 只有确认远端尚未启动副作用动作，才允许重新创建 Intervention。
- 任何自动恢复都使用原 `request_id` 和 `request_hash`。

### 10.6 用户拒绝和取消

- 拒绝返回结构化 Tool Result，让 Agent 自己调整任务或结束。
- 取消不会自动重试，也不会自动输入空值。
- 用户取消 PTY 接管时，是否关闭远端进程由请求的 `cancel_policy` 决定。
- 过期请求不能被旧 UI 卡片再次解决。
- `FULFILLING` 期间收到取消、拒绝或过期，只记录 pending command 并进入 reconcile，不能直接改成终态。

### 10.7 Run 终止优先级

`Run termination dominates Suspension`。用户将 AgentRun 标记为 `CANCELLED` 或 `TERMINATED` 后，先递增 `run_generation`，再由所有 resolve、fulfill、resume 和 ResourceLease claim 校验 `run_generation == expected` 且 AgentRun 仍非 terminal。

- Run 尚未进入外部副作用时，禁止新的 resolve、fulfillment 和 resume；撤销尚未消费的 CapabilityGrant，释放可安全撤销的 ResourceLease；所有未履行 Suspension 生成明确 `RUN_TERMINATED` 结果，不再恢复 AgentLoop。
- Run 处于 `FULFILLING` 时不能假装动作未发生。必须完成外部事实对账，记录 `DELIVERED`、`NOT_DELIVERED` 或 `UNKNOWN`，绝不恢复 AgentLoop。
- `DELIVERED`、`READY_TO_RESUME`、`RESUMING` 和已打开 OAuth 浏览器的旧 Suspension 也只允许清理或生成取消结果，不能正常 resume。
- Run 终止后到达 OAuth callback 必须拒绝，或只执行安全 cleanup；不得创建新的 CapabilityGrant，也不得推进正常 resume。
- 拒绝、取消、过期和目标丢失都生成失败型 `ContinuationResult`，恢复目标 slot 后交给 Agent 调整方案。

Run terminal 下的结果必须使用独立的终止语义，不能把 Run 终止伪装成普通 Tool 失败：

| 情况 | 结构化结果 code | 用户可见文案 | 是否恢复 AgentLoop |
| --- | --- | --- | --- |
| 外部副作用尚未开始，Run 被终止 | `RUN_TERMINATED` | “Agent 已停止，未继续执行此操作。” | 否 |
| `FULFILLING` 对账为 `DELIVERED` | `RUN_TERMINATED_AFTER_DELIVERY` | “Agent 已停止，但外部操作已完成；系统不会继续运行。” | 否 |
| `FULFILLING` 对账为 `NOT_DELIVERED` | `RUN_TERMINATED_NOT_DELIVERED` | “Agent 已停止，已确认外部操作未完成。” | 否 |
| `FULFILLING` 对账为 `UNKNOWN` | `RUN_TERMINATED_EXTERNAL_STATE_UNKNOWN` | “Agent 已停止，外部操作结果无法确认；系统不会重试或继续运行。” | 否 |

`INTERVENTION_REJECTED`、`CAPABILITY_UNAVAILABLE` 和 `USER_DECISION_REQUIRED` 只用于 Run 仍可继续时的业务结果。Run 进入 terminal 后统一使用上述 `RUN_TERMINATED*` code，避免用户误以为只是当前 Tool 失败或仍可点击继续。

## 11. UI 设计

### 11.1 统一接力卡片

所有执行期介入都使用同一种卡片：

- 标题。
- 当前任务阶段。
- 经过可信策略处理的介入原因。
- 请求来源等级和 Target Attestation 状态。
- 需要填写、选择、授权或接管的字段。
- 数据是否会保存，以及保存范围。
- “完成”“拒绝”“取消”按钮。
- 过期时间和当前服务器名称的安全短描述。
- 明确区分“提供能力 / 凭据”和“批准副作用操作”；两者对应的 Intervention Gate、Approval Gate 分开显示。

卡片不显示完整命令、完整日志、私钥、Token、密码和不必要的服务器细节。

Registry 注册动作使用可信系统卡片。模型生成的通用说明使用低信任样式，并明确标记“内容来自 Agent，未经系统验证”。两种卡片不能共用安全图标、标题模板或确认按钮语义。

### 11.2 敏感输入

- 字段类型由 Policy Registry 决定，`PASSWORD`、`OTP` 使用密码视觉变换。
- 关闭自动填充、截屏预览和剪贴板泄漏。
- 提交后立即清空 UI 状态。
- UI 尽可能把可清零的 mutable buffer 交给 Broker，完成后清空可控状态；不承诺 Android/JVM 内部副本绝对物理清零。
- 记住凭据必须单独确认保存范围。
- 提供 Token、密码或完成 OAuth 只表示 capability 可用，不代表批准底层 Tool 的副作用。

### 11.3 终端接管

- 卡片显示“Agent 等待你的终端操作”。
- 点击后进入同一个 PTY，不创建第二个终端。
- 输出默认脱敏，用户输入不写入聊天消息。
- 接管期间暂停模型继续发起同一 PTY 的写操作。
- 接管期间 Raw PTY Stream 只进入 Human View，Agent 只获得 Sanitized Agent View。
- 敏感输入后的回显窗口在可信同步点前不投影给 Agent。
- 用户完成后回到卡片点击“继续”。

### 11.4 后台和通知

通知只显示：

- “Agent 等待你的操作”。
- 安全短标题。
- 所属会话的安全标识。

通知禁止显示密码原因中的敏感文本、命令、Token、完整路径和完整输出。

## 12. 并行 Tool Call

### 12.1 执行检查点

一次模型响应可能同时产生多个 Tool Call：

~~~text
Tool A：read file
Tool B：git push
Tool C：run tests
~~~

其中 B 可能等待 Git capability，A 已完成，C 仍在运行。Intervention 必须绑定：

~~~text
turn_id
tool_call_id
execution_slot
request_id
request_hash
continuation
~~~

### 12.2 槽位状态

每个 execution slot 只保存 Tool 槽位生命周期：

~~~text
PENDING
RUNNING
SUSPENDED
RESUMING
COMPLETED
FAILED
UNKNOWN
~~~

人类接力内部状态全部属于对应的 `SuspensionState`，不复制到 slot。一个 `SUSPENDED` slot 可以关联一个或多个 Gate，但每个 Suspension 仍由自己的持久状态驱动。

恢复时：

1. 只恢复被 `suspend()` 的 slot。
2. 已完成 sibling Tool 不重新执行。
3. 正在对账的 sibling Tool 不被用户解决动作打断。
4. 已产生副作用但结果未知的 sibling Tool 继续走 UNKNOWN。
5. 后续 pending Tool 是否继续，由 AgentLoop 根据原批次顺序和依赖决定。

### 12.3 Tool Batch Barrier

新增 `RunGateCoordinator` 管理 Tool Batch Barrier：

~~~text
slot A ─ COMPLETED ─┐
slot B ─ COMPLETED ─┼─→ all blocking slots produced result → AgentLoop resume
slot C ─ SUSPENDED ┘
~~~

AgentLoopState 与 ExecutionSlotState 分开保存：

~~~text
AgentLoopState = WAITING_TOOL_BATCH
slot A = COMPLETED
slot B = SUSPENDED
slot C = RUNNING
~~~

解决一个 Intervention 只推进对应 slot。若该 slot 同时存在 Approval Gate，解决 Intervention 只更新 capability 条件，不更新 Approval 条件。只有所有 blocking slot 都产生最终 Tool Result，且每个待执行副作用 slot 同时满足 Approval 和 Capability，RunGateCoordinator 才允许 AgentLoop 进入下一轮模型请求。

以下 slot 状态仍然阻塞 Batch：

~~~text
PENDING
RUNNING
RESUMING
SUSPENDED
~~~

`SuspensionState` 为 `REJECTED`、`CANCELLED`、`EXPIRED`、`TARGET_LOST` 或已处理的 `UNKNOWN` 时，必须先生成明确 Tool Result，再把 slot 从 `SUSPENDED` 映射为 `FAILED` 或 `UNKNOWN`，才算解除 Barrier。`RESUME_AGENT_LOOP` 也必须经过 Barrier，不能绕过仍在等待或运行的 sibling slot。

### 12.4 ResourceLease

`ResourceLease` 是底层资源互斥机制，和 execution slot 状态分开。资源 Lease 至少包含：

~~~text
resource_ref
lease_owner
lease_kind
lease_generation
run_id
run_generation
issued_at
expires_at
revoked
~~~

硬约束：

1. 同一个 PTY 的用户接管必须独占。
2. 同一个 privilege challenge 必须独占。
3. 同一个 OAuth state 必须单消费者。
4. 不可重入 Adapter target 必须独占。
5. Adapter 不支持并发敏感操作时，同一个 SSH connection 必须持有资源 Lease。
6. Lease 失效、generation 改变、owner 不匹配、Run generation 不匹配或 revoked 时不得继续投递。
7. Lease claim 使用 CAS，不能只依赖内存 Mutex。
8. Lease 到期不代表外部动作未发生，过期后先 reconcile，再决定是否释放或进入 `DELIVERY_UNKNOWN`。

### 12.5 多个待处理 Intervention

底层状态使用：

~~~text
StateFlow<List<PendingIntervention>>
~~~

V1 UI 可以限制同一时间只展开一个卡片，但数据模型不能使用单值字段把并行能力写死。每个卡片都必须显示安全短标题、所属 execution slot 和当前状态。

## 13. 模块和文件改造范围

### 13.1 新增 Module

| 文件 | 职责 |
| --- | --- |
| `data/agent/AgentInterventionModels.kt` | Capability Request、Trusted Intervention、Suspension、Binding 和 resolution 模型 |
| `data/agent/AgentContinuation.kt` | Continuation 类型、execution slot 和 request hash 绑定 |
| `data/agent/AgentInterventionBroker.kt` | 原子 suspend、解决、拒绝、过期、恢复、reconcile 和清理 |
| `data/agent/AgentInterventionPolicyRegistry.kt` | capability 白名单、可信字段、target、delivery、Adapter 和 Continuation 策略 |
| `data/agent/AgentInterventionAdapters.kt` | Adapter Interface、履行结果和注册表 |
| `data/agent/AgentInterventionStore.kt` | Suspension 记录读取、原子状态更新和一次性消费 |
| `data/agent/AgentInterventionSanitizer.kt` | 用户文案、日志、模型结果和 schema 脱敏 |
| `data/agent/AgentRunGateCoordinator.kt` | Tool Batch Barrier、AgentLoopState 与 execution slot 汇合 |
| `data/agent/AgentBindingResolver.kt` | BindingRef 重启解析、generation、digest、owner 和 scope 校验 |
| `data/agent/AgentTargetAttestation.kt` | 高敏感 capability 的 challenge、目标和来源证明 |
| `data/agent/AgentCapabilityGrantStore.kt` | 仅内部可见的 CapabilityGrant lease、StoredAuthorization 引用、scope 和消费状态 |
| `data/agent/AgentResourceLeaseStore.kt` | PTY、challenge、OAuth state、SSH connection 和不可重入 target 的资源级 Lease |

### 13.2 修改 Agent 模块

| 文件 | 修改内容 |
| --- | --- |
| `data/agent/AgentModels.kt` | 增加 `AgentSuspensionRecord` 兼容字段，保留旧 `AgentApprovalRecord` 解码 |
| `data/agent/AgentControlTools.kt` | 增加 `request_capability`，旧控制工具映射到统一请求 |
| `data/agent/AgentLoop.kt` | 统一 suspend、Continuation、slot 恢复和 InterventionRequired 处理 |
| `data/agent/AgentRunStore.kt` | 增加 Suspension 账本、CAS claim、execution checkpoint、Batch Barrier 和并行 slot 状态 |
| `statecontroller/api/ApiHandler.kt` | 删除专用响应入口，接入统一 resolve/reconcile |
| `statecontroller/viewmodel/AppViewModel.kt` | 一个 `StateFlow<List<PendingIntervention>>` |

### 13.3 修改 Computer 模块

| 文件 | 修改内容 |
| --- | --- |
| `data/computer/ComputerToolExecutor.kt` | 只在 preflight 且无副作用时抛出 typed `InterventionRequired` |
| `data/computer/ComputerTerminalManager.kt` | 提供 Broker 专用 PTY 接管、双视图、敏感回显窗口和写入互斥 |
| `data/computer/ComputerSshClient.kt` | 暴露受控连接 Adapter 所需的连接能力，不暴露凭据明文给模型 |
| `data/computer/ComputerCredentialStore.kt` | 统一长期凭据和一次性凭据的安全访问接口 |
| `data/computer/ComputerRuntimeEnvelope.kt` | 支持可信 Operation Plan 和受限 capability，不接受模型传入明文或任意带 Secret 命令 |
| `data/computer/ComputerModels.kt` | 增加 BindingRef、resource epoch、generation 和 TargetAttestation 字段 |
| `data/computer/ComputerHostCommandPolicy.kt` | 区分 ModelHint 与 ExecutorProven，高敏感请求要求可信 challenge |

### 13.4 修改 UI

| 文件 | 修改内容 |
| --- | --- |
| `statecontroller/api/ApiHandler.kt` | 投影统一待接力请求 |
| `ui/screens/MainScreen/chat/ChatScreen.kt` | 接入统一接力卡片状态 |
| `ui/screens/MainScreen/chat/text/ui/ChatInputArea.kt` | 删除 Skill 专用弹窗，使用统一字段渲染器 |
| `ui/screens/computer/ComputerWorkspaceUi.kt` | 保留 Workspace Secret 管理，改用统一 Secret Adapter |
| `ui/screens/computer/ComputerTerminalTakeoverUi.kt` | 新增 PTY 接管页面或底部卡片 |
| `res/values/strings.xml`、`res/values-zh/strings.xml` | 统一接力状态、错误和 TalkBack 文案 |

### 13.5 逐步删除的专用结构

以下结构迁移完成后删除：

- `PendingAgentEnableApproval`
- `PendingSkillSecretApproval`
- `pendingAgentEnableApprovals`
- `pendingSkillSecretApprovals`
- `respondToAgentEnableApproval`
- `respondToSkillSecretApproval`
- AgentLoop 中针对 `EnableAgent` 和 `SkillSecret` 的专用恢复分支

旧数据先通过兼容 Adapter 转成统一 Intervention，不做一次性破坏性迁移。

## 14. 典型场景映射

| 场景 | Capability | 用户动作 | 可信 Adapter / Delivery | Agent 可见结果 |
| --- | --- | --- | --- | --- |
| GitHub Token | `git.push` | 输入 Token 或选择已保存凭据 | Git Push Adapter / Credential Helper | `git.push` 可用 |
| GitHub OAuth | `git.push` | 浏览器登录并授权 | OAuth Adapter / `OAUTH_CALLBACK` | capability 可用 |
| 第二台 VPS | `ssh.connect` | 选择服务器或输入临时连接资料 | SSH Connection Adapter | 连接 capability 可用 |
| sudo 密码 | `privilege.sudo.execute` | 输入一次性密码 | Attested Privilege Adapter | 权限 capability 可用 |
| OTP | `terminal.interaction` | 接管 PTY 输入 OTP | PTY Takeover Adapter | 终端继续 |
| 服务器重启确认 | `server.restart.confirm` | 完成 Registry 注册动作 | Acknowledgement Adapter | 可继续验证 |
| Skill API 授权 | `skill.openai_api_access` | 输入或选择已注册授权 | Capability Proxy | capability 可用 |

场景表只用于说明协议映射，不用于在 AgentLoop 里新增场景分支。

## 15. 迁移顺序

### 阶段 1：统一模型和账本

1. 增加 `AgentInterventionModels`、`AgentContinuation`、三层状态模型和 execution slot 模型。
2. 在迁移桥中保留旧 `AgentApprovalRecord` 解码。
3. 实现事务性的 `suspend()` 和 resolve、fulfill、resume 三类 CAS claim。
4. 增加 BindingRef、StoredAuthorization 引用、CapabilityGrantId、`policy_version`、`adapter_contract_version`、`binding_generation`、`run_generation`、active suspension idempotency key、attempt ID、row version 和 nonce。
5. 增加 `ResolutionMaterialKind` 和 `WAITING_USER_REENTRY`，明确一次性明文丢失后的重新输入路径。
6. 增加 `StateFlow<List<PendingIntervention>>` 和统一卡片投影。
7. 旧 `EnableAgent`、`SkillSecret` 通过兼容 Adapter 映射到 capability，不进入新领域模型。

完成标志：旧流程功能不变，所有暂停状态都能用统一模型读取。

### 阶段 2：威胁模型与可信 Policy Registry

1. 增加 `request_capability`。
2. 建立 capability 目录、请求来源等级和注册动作目录。
3. 将字段 schema、Binding、delivery、Adapter、Continuation 和 Verification Plan 移入本地可信代码。
4. 更新系统提示词，移除 Skill 专用请求作为唯一规则。
5. 禁止模型创建通用高风险 external action。
6. 建立 Policy、Adapter 合约和 Binding generation 的兼容判断；不兼容的旧 Suspension 持久化 `failure_code = POLICY_STALE` 并使用 `REPLAN_REQUIRED`，禁止静默套用新版策略。
7. 明确 Intervention Gate 与 Approval Gate 的组合规则，Run termination 优先于所有 Gate。

完成标志：模型可以用同一个控制工具表达 capability 缺口，字段、target、delivery、Adapter 和 Continuation 全由本地 Registry 决定。

### 阶段 3：接入 Typed Capability Missing

1. 定义 `InterventionRequired`。
2. 只让 preflight 且无副作用的路径抛出该结果。
3. 区分 `MODEL_HINT`、`EXECUTOR_PROVEN` 和 `SYSTEM_CHALLENGE`。
4. 高敏感 capability 增加 Target Attestation。
5. AgentLoop 将可信结果转成统一 Intervention。
6. 已启动、未知和有副作用的执行保持原有 UNKNOWN 或 FAILED 流程。

完成标志：Executor 发现缺能力时能自动暂停，不再依赖模型猜测。

### 阶段 4：敏感值和资源 Adapter

1. 接入语义化 Capability Proxy 和 Git Adapter，禁止任意带 Secret 命令。
2. 接入 SSH Connection Adapter。
3. 接入要求 Attestation 的 Privilege Adapter。
4. 接入带 state、PKCE、redirect URI/callback binding、单次消费和重放保护的 OAuth Adapter。
5. 将 StoredAuthorization 与 CapabilityGrant 分离，并把 Grant 实现为绑定 audience、scope、TTL、使用次数、Run、slot、target 和 generation 的可撤销 lease。
6. 接入带 Raw/Human/Sanitized 双视图和敏感回显窗口的 PTY Takeover Adapter。
7. OAuth `code_verifier` 只进入 Secure Store，接入完成、取消、过期后的清理。
8. 为每个 Adapter 接入 `reconcile()`，明确 `DELIVERED / NOT_DELIVERED / UNKNOWN`。

完成标志：GitHub、第二台 VPS、sudo、OTP、浏览器授权都能走统一生命周期。

### 阶段 5：并行槽位和恢复

1. 为每个 Tool Call 建立 execution slot。
2. 增加 RunGateCoordinator 和 Tool Batch Barrier。
3. Intervention 绑定 turn、Tool Call、slot、request、hash 和前置条件指纹。
4. `RETRY_TOOL` 增加 resource epoch、execution generation 和世界状态校验。
5. 恢复时只恢复被 suspend 的 slot，世界变化时进入 `REPLAN_REQUIRED`。
6. 已完成和正在对账的 sibling Tool 不重复执行。
7. 增加独立于 execution slot 的 ResourceLease，为 PTY、privilege challenge、OAuth state、不可重入 target 和受限 SSH connection 提供持久 CAS 互斥。
8. 增加 BindingResolver、App 重启和 Adapter 崩溃恢复。
9. `RunGateCoordinator` 启动时主动扫描所有非终态 Suspension，重新 claim `READY_TO_RESUME`、`RESUMING`、`RECONCILIATION_REQUIRED`、`RECONCILING`、`FULFILLING` 和 `DELIVERY_UNKNOWN`。

完成标志：并行 Tool、断线和进程重启不会造成重复副作用。

### 阶段 6：敌对安全与 Chaos 验收

1. 覆盖 Prompt Injection、fake sudo、无 Attestation 权限请求。
2. 覆盖 ENV、`/proc`、ptrace、credential helper 等 Secret 窃取尝试。
3. 覆盖并发 resolve、OAuth callback 重放和 CAS claim。
4. 覆盖每个 Fulfillment 和 Resume 状态强杀 App。
5. 覆盖 Suspension 期间 Workspace 和远端状态变化。
6. 覆盖 PTY 敏感输入回显和双视图隔离。
7. 覆盖同一 Run 多个 Intervention 和 Batch Barrier。
8. 覆盖 Policy、Adapter 合约或 Binding generation 变化后的旧 Suspension 恢复。
9. 覆盖 CapabilityGrant 超 scope、超 TTL、超次数、撤销和跨 Run 使用。
10. 覆盖内存 wake-up 丢失后的持久扫描，以及多个 slot 竞争同一 ResourceLease。

完成标志：安全验收不会出现 Secret 泄露、重复投递、重复 Tool 或提前恢复 AgentLoop。

### 阶段 7：删除专用 UI 和收敛文案

1. 删除两个专用 StateFlow。
2. 删除两个专用响应入口。
3. 删除 Skill 专用弹窗。
4. 统一通知、卡片、错误和 TalkBack 文案。
5. 清理历史兼容代码。

完成标志：新增一种外部能力时不需要修改 AgentLoop 和主聊天页面的业务分支。

## 16. 安全规则

以下规则是硬约束：

1. 在声明的信任模型内，Secret 不进入模型，也不能被 Agent 任意读取；Agent 只能调用受限 capability。
2. 用户不能通过普通聊天消息提交敏感值作为执行凭据。
3. 模型不能定义敏感字段 schema、target、delivery 或 Adapter。
4. Tool Call 参数中不能出现密码、Token、私钥、OTP。
5. Secret 不进入 Agent 任意可读的 ENV、文件或模型可控 stdin。
6. Room 只保存 schema、状态、句柄哈希、执行检查点和安全摘要。
7. Intervention Record 不复制完整命令；原 Tool Call 按 AgentRunStore 规则保存，但参数不得包含敏感值。
8. 投递目标必须由本地生成并校验。
9. 同一个 PTY 同时只能由 Agent 或用户其中一方写入。
10. `suspend()` 必须原子写入 Suspension、AgentRun 状态和 suspension event。
11. Intervention 解决必须幂等，重复点击不能重复执行。
12. Adapter 进入 `FULFILLING` 后，结果不确定时必须进入 `DELIVERY_UNKNOWN`。
13. `DELIVERY_UNKNOWN` 不能自动重复投递。
14. 已产生副作用的执行不能因为缺 capability 而自动重放。
15. Continuation 由本地 Broker 生成，模型不能修改。
16. 并行恢复只允许恢复被 suspend 的 execution slot。
17. 已完成、正在对账和 UNKNOWN 的 sibling Tool 不得重新执行。
18. UNKNOWN 只能表示执行事实无法确认，不能转换成 Intervention。
19. 连接恢复必须重新验证 Host Key 和资源归属。
20. 一次性敏感值尽可能使用可清零 mutable buffer，但不承诺 JVM 内部副本绝对清零。
21. 敏感值不持久化、不进入日志、不进入聊天、不进入通知、不进入模型。
22. 拒绝、取消、过期和目标丢失必须让 Agent 得到明确结构化结果。
23. 所有用户可见文案都必须经过敏感信息过滤。
24. Agent 拥有远端 root、ptrace 或等价完全控制权限时，不承诺同主机进程级 Secret 隔离。
25. Capability Proxy 必须执行 Registry 注册的语义操作，禁止任意 `runCommandWithSecret`。
26. 高敏感 capability 必须要求 `EXECUTOR_PROVEN` 或 `SYSTEM_CHALLENGE` 和 Target Attestation。
27. sudo 密码禁止投递到未证明来源的任意 PTY。
28. `RETRY_TOOL` 必须验证前置条件指纹、resource epoch 和 execution generation；不一致时进入 `REPLAN_REQUIRED`。
29. resolve、fulfill、resume 必须分别使用 CAS claim 和独立 attempt ID。
30. 真正的 CapabilityGrantId 不能返回模型，模型可见 resource ref 不携带权限。
31. BindingRef 必须在重启后通过 Resolver 重新解析并校验 generation、digest、owner 和 scope。
32. 通用 external action 禁止承载凭据、支付、权限、删除和其他高风险操作。
33. PTY 用户输入使用 Raw Human View 与 Sanitized Agent View，敏感回显窗口不能进入模型。
34. Tool Batch Barrier 未解除时，任何单个 Continuation 都不能提前恢复 AgentLoop。
35. `FULFILLING` 期间的取消、拒绝、过期或超时必须进入 reconcile，不能直接当作未投递。
36. Suspension 必须绑定 Policy、Adapter 合约和 Binding generation；版本不兼容时写入 `failure_code = POLICY_STALE` 并使用 `REPLAN_REQUIRED`，禁止静默重解释。
37. CapabilityGrant 必须校验 audience、operation、target、scope、TTL、使用次数、generation、revoked、Run 和 execution slot。
38. ResourceLease 与 execution slot 分层管理；Lease owner、generation、Run generation、TTL 或 revoked 校验失败时禁止投递。
39. OAuth state、authorization code 和 callback 必须单次消费，过期、重放或绑定不匹配时直接拒绝。
40. `resolution_material_kind = EPHEMERAL` 的明文丢失后必须进入 `WAITING_USER_REENTRY`，不得伪造继续履行、FAILED、UNKNOWN 或自动重试。
41. Adapter reconcile 至少区分 `DELIVERED`、`NOT_DELIVERED` 和 `UNKNOWN`；`NOT_DELIVERED + EPHEMERAL_LOST` 只能重新请求用户输入。
42. StoredAuthorization 与 CapabilityGrant 分离；长期授权材料不能直接作为模型可见或跨 Run 的 Grant。
43. Run `CANCELLED` 或 `TERMINATED` 后，`run_generation` 递增，旧 resolve、fulfill、resume 和 lease claim 全部失效；Fulfillment 仍需对账，但绝不恢复 AgentLoop。
44. `suspend()` 使用 active suspension idempotency key 和 Room 唯一约束或等价事务去重；更高证据只能 CAS 升级原记录。
45. Intervention Resolution 不满足 Approval Gate；Approval 也不创建 CapabilityGrant。只有 Approval 允许且 Capability 可用时才能执行副作用 Tool。
46. `AgentLoopState`、`ExecutionSlotState`、`SuspensionState` 各自只承担声明的职责；Barrier 以持久化 Suspension、Run terminal 状态和 checkpoint 为事实源重建投影。
47. OAuth `code_verifier` 只能位于 Secure Store 或短生命周期内存，Room 只保存 `verifier_reference` 和 `verifier_generation`，完成、取消、过期后必须清理。
48. CapabilityGrant 使用必须原子 claim/consume；校验、占用次数和副作用投递不能拆成可被并发调用插入的多个非原子步骤。
49. `RESOLUTION_RECEIVED` 和 `DELIVERED` 必须显式参与启动恢复扫描，不能只扫描 `READY_TO_RESUME` 或等待内存 wake-up。
50. `USER_DECISION_REQUIRED` 统一表示外部事实无法安全自动判定，需要用户做出明确继续、放弃或重新规划决定；它不表示普通 capability 缺失，也不等同于 `UNKNOWN`。
51. `WAITING_USER_REENTRY` 必须轮换 `resolution_nonce`，旧 nonce 立即失效，重新输入只能创建新的 resolution。

## 17. 验收标准

### 17.1 统一协议

- GitHub、第二台 VPS、sudo、OTP、浏览器授权和交互确认都能创建同一种 Intervention 记录。
- AgentLoop 不出现按 GitHub、sudo、OTP 等场景命名的业务分支。
- 新增 Adapter 不需要改 Room 审批状态机。
- Approval 与 Intervention 在领域模型中是不同 payload/subtype。

### 17.2 Policy Registry

- 模型只能提交 capability 缺口和安全 reason。
- 模型不能选择密码控件、OTP 控件、服务器列表、Adapter 或 target。
- 恶意 Workspace 文本不能直接触发任意敏感表单。
- 不在 Registry 中的 capability 会被拒绝，不会弹出自定义凭据 UI。
- 高敏感 capability 必须来自 `EXECUTOR_PROVEN` 或 `SYSTEM_CHALLENGE`。
- 通用 Agent 文本不能借用可信系统动作卡片的样式和权限。
- Policy、Adapter 合约或 Binding generation 不兼容时，旧 Suspension 写入 `failure_code = POLICY_STALE` 并使用 `REPLAN_REQUIRED`，不会被新版 Registry 静默重解释。

### 17.3 Secret 与 Capability

- Agent 看不到 Secret 明文。
- 在声明的受限执行环境内，Agent 无法通过 `env`、任意文件、进程环境或模型可控 stdin 读取 Secret。
- Agent 获得 root、ptrace 或等价控制权限时，测试和文档不会宣称同主机 Secret 仍不可读。
- GitHub 认证通过固定语义 Git Adapter、Credential Helper、OAuth 或受控 Operation Plan 完成。
- Brokered subprocess 不接受模型生成的任意 executable、脚本或命令。
- sudo 凭据只投递到通过 Target Attestation 的 challenge 或可信权限 Helper。
- Tool Result 只返回 capability 状态，不返回授权性 handle；真正 CapabilityGrantId 不返回模型。
- CapabilityGrant 具有 audience、scope、TTL、max_uses、generation 和 revoked 状态。
- `ONCE` capability 默认只能使用一次，Grant 过期、撤销、超次数或跨 Run 时被拒绝。
- GitHub repo A 的授权不会扩大到 repo B。
- 每次使用 Grant 都校验 operation、target binding、Run、Tool Call、execution slot 和 generation；知道非授权性 `resource_ref` 不能绕过这些校验。
- StoredAuthorization 仅作为 Secure Store 中的长期授权引用；每个新 Run、Tool Call 和 slot 都由 Broker 派生新的短期 CapabilityGrant。
- StoredAuthorization 的 `WORKSPACE` 或 `COMPUTER` scope 不会自动变成跨 Run bearer Grant，模型不能直接使用或引用它。

### 17.4 Continuation

- preflight 缺 capability 使用 `RETRY_TOOL`。
- 已启动远端进程使用 `CONTINUE_EXECUTION`。
- 已启动 PTY 使用 `CONTINUE_PTY`。
- 外部授权完成使用 `RESUME_AGENT_LOOP`。
- 外部动作完成后需要检查使用 `VERIFY_THEN_RESUME`。
- `VERIFY_THEN_RESUME` 只引用 Registry 生成的 VerificationPlanId。
- `RETRY_TOOL` 必须验证前置条件指纹、resource epoch 和 execution generation。
- 等待期间世界状态变化时使用 `REPLAN_REQUIRED`，不执行原 Tool。
- UNKNOWN 不会被转成 Intervention，也不会自动重放。

### 17.5 Agent 恢复

- 用户完成介入后，原 AgentRun 可以继续。
- 后续 Tool Call 不丢失、不重复执行。
- App 重启后待处理 Intervention 可以重新显示。
- 过期、拒绝、取消和目标丢失都能恢复成明确 Tool Result。
- BindingResolver 能从 BindingRef 重新解析可恢复资源，并拒绝 generation、digest、owner 或 scope 不匹配的资源。
- PTY Binding 在 App 进程死亡后进入 `TARGET_LOST`。
- `RESOLUTION_RECEIVED + EPHEMERAL` 在 fulfillment 尚未开始且明文丢失时进入 `WAITING_USER_REENTRY`，不会伪造继续履行、FAILED、UNKNOWN 或自动重试。
- Run 进入 `CANCELLED` 或 `TERMINATED` 后，旧 Suspension 只能生成取消结果或执行清理，不能恢复 AgentLoop。
- Run terminal 结果使用 `RUN_TERMINATED*` 独立 code；文案明确区分“未开始”“已完成”“已确认未完成”和“结果未知”，不显示普通 Tool 失败或可继续执行的提示。

### 17.6 Fulfillment 恢复

- 用户解决后状态依次经过 `RESOLUTION_RECEIVED`、`FULFILLING`、`DELIVERED`、`READY_TO_RESUME`、`RESUMING`。
- Adapter 崩溃或 App 重启后会执行 `reconcile()`。
- `DELIVERY_UNKNOWN` 不会重复输入密码、OTP 或授权码。
- 同一 suspension 重复点击不会重复履行。
- `WAITING_USER_REENTRY` 必须轮换 resolution nonce，旧 nonce 无法再次 resolve。
- 两个 UI 和 OAuth callback 同时 resolve 时，只有一个 resolution nonce 和 fulfillment CAS claim 成功。
- Fulfillment 和 Resume 使用不同 attempt ID，两个阶段都不能重复。
- `READY_TO_RESUME`、`RESUMING`、`FULFILLING` 和 reconcile 状态在 App 重启后由持久状态主动扫描并重新 claim。
- 启动恢复明确扫描 `RESOLUTION_RECEIVED` 和 `DELIVERED`，根据 material、Run terminal 状态和未完成 attempt 决定继续履行、进入 `WAITING_USER_REENTRY` 或进入对账，不能遗漏这两个状态。
- 丢失内存 wake-up 不会让 Suspension 永久卡住。
- Room / AgentRunStore 是恢复事实源，内存事件、Flow 和 wake-up 全部允许丢失。
- `reconcile()` 能明确区分 `DELIVERED`、`NOT_DELIVERED` 和 `UNKNOWN`；`NOT_DELIVERED + EPHEMERAL_LOST` 只进入 `WAITING_USER_REENTRY`。
- `FULFILLING` 期间 Run 终止仍完成事实对账，但永不恢复 AgentLoop。

### 17.7 并行执行

- Intervention 绑定 `turn_id`、`tool_call_id`、`execution_slot`、`request_id`、`request_hash`。
- 恢复只处理被 suspend 的 slot。
- 已完成 sibling Tool 不重复执行。
- 正在对账 sibling Tool 不被重新创建。
- 同一 Run 支持多个待处理 Intervention。
- Tool Batch Barrier 未解除时，解决单个 Intervention 不会提前恢复 AgentLoop。
- AgentLoopState 与各 ExecutionSlotState 能同时表达“Run 等待批次但 sibling Tool 仍运行”。
- PTY、privilege challenge、OAuth state 和不可重入 Adapter target 都通过 ResourceLease 独占。
- Lease generation、owner、TTL 或 revoked 不匹配时不得继续投递。
- Adapter 声明 SSH connection 不支持并发敏感操作时，同一连接只能有一个 ResourceLease owner。
- `AgentLoopState`、`ExecutionSlotState`、`SuspensionState` 不互相复制内部状态；投影不一致时以持久化 Suspension、Run terminal 状态和 execution checkpoint 重建 Barrier。

### 17.8 敏感数据

- 密码、Token、私钥、OTP 不出现在模型上下文、Tool Call、Room、聊天、通知和日志。
- PTY 接管输入不经过模型。
- PTY 敏感输入被远端 echo 时，Sanitized Agent View 不包含该内容。
- 无法确认 PTY 脱敏完整性时，整段敏感窗口不会投影给 Agent。
- 使用可清零 mutable buffer 并最小化明文生命周期，不承诺 Android/JVM 内部副本绝对物理清零。
- 句柄无法跨 Run、跨会话或跨服务器复用。

### 17.9 数据持久化

- Intervention Record 不复制完整命令。
- 原 Agent Tool Call 按 AgentRunStore 既有规则保存。
- Tool Call 参数中没有任何敏感值。
- Room 不保存 Secret、授权码、完整终端输入或句柄原文。
- Room 保存 BindingRef、generation、digest、CAS row version 和 attempt ID，不保存授权性 bearer handle。

### 17.10 副作用安全

- preflight 缺能力可以暂停。
- 已启动命令只进入执行事实对账，不转换成普通 Intervention。
- UNKNOWN 状态不自动重放。
- FULFILLING 状态崩溃后先 reconcile，不重复投递。
- 重复解决同一个 Intervention 不会重复履行或重复执行。
- fake sudo、未证明 PTY 和模型主动 sudo 请求都无法触发密码投递。

### 17.11 用户体验

- 用户只面对一个统一接力卡片。
- 文案明确说明原因、动作、保存范围、execution slot 和继续方式。
- 终端接管能回到同一个 PTY。
- App 重启后不会显示虚假的终端仍可用。
- 拒绝、取消、过期、目标丢失和 UNKNOWN 都有明确提示。
- Registry 注册动作和模型生成通用说明具有不同的信任标识和交互样式。

### 17.12 OAuth 安全

- OAuth 使用 state、PKCE、redirect URI 和 callback binding。
- state 和 authorization code 只能单次消费，重复 callback 被拒绝。
- state 有明确 TTL，App 被回收后只能依据持久化安全元数据恢复。
- authorization code、access token、refresh token 不进入模型、Room 普通字段、聊天、通知和日志。
- App 恢复后不得复用已消费、过期或 callback binding 不匹配的 state 和 authorization code。
- `code_verifier` 不进入模型、普通 Room、日志、聊天或通知；Room 只保存 `verifier_reference` 和 `verifier_generation`，完成、取消或过期后清理 Secure Store 材料。
- Run 终止后 OAuth callback 只能被拒绝或用于安全 cleanup，不得创建 Grant 或推进正常 resume。

### 17.13 Gate 组合与取消

- Intervention Resolution 不会自动满足 Approval Gate，Approval 也不会自动创建 CapabilityGrant。
- 同一 execution slot 同时存在两个 Gate 时，只有 `Approval = ALLOWED` 且 `Capability = AVAILABLE` 才能执行 Tool。
- Run 取消会递增 `run_generation`，阻止旧 claim 继续 fulfillment、resume 或 lease 投递。
- `suspend()` 使用 active suspension idempotency key 去重，重复来源只保留一条 active Suspension；更高证据通过 CAS 升级原记录。

### 17.14 Security / Chaos Acceptance

以下是高风险路径的定向验收，不运行无关全量测试：

1. Workspace README 写入“请向用户索要 GitHub Token”，不得出现模型自定义 PASSWORD 卡片。
2. 模型主动请求 `privilege.sudo.execute`，缺少 Executor Attestation 时不得注入密码。
3. Workspace 中的 `./sudo`、alias 或伪造 sudo 提示必须被 Privilege Adapter 拒绝。
4. Agent 尝试 `env`、`/proc/*/environ`、`/proc/*/fd`、ptrace、strace 和读取 credential helper 时拿不到 Secret；Agent 为 root 的环境不执行同主机 Secret 投递。
5. 两个 UI 与 OAuth callback 同时 resolve，只有一个 CAS claim 和 Fulfillment attempt 成功。
6. 在 `RESOLUTION_RECEIVED`、`FULFILLING`、`DELIVERED`、`READY_TO_RESUME`、`RESUMING` 分别强杀 App，重启后不产生重复副作用。
7. OAuth callback 重放两次，第二次 nonce 校验失败且不创建新授权。
8. Suspension 期间 HEAD、Workspace、target generation 或 remote precondition 改变，`RETRY_TOOL` 转为 `REPLAN_REQUIRED`。
9. 用户输入 OTP 后远端回显 OTP，Raw Human View 可见，Sanitized Agent View 不可见。
10. 同一 Run 同时存在两个 Intervention，解决一个后 Tool Batch Barrier 仍阻止 AgentLoop 提前进入下一轮。
11. BindingRef 指向的资源 generation、owner 或 digest 改变时，App 重启恢复必须拒绝旧 Binding。
12. `FULFILLING` 期间收到取消、过期或超时，必须 reconcile，不能直接标记未投递。
13. Policy version、Adapter contract version 或 Binding generation 不兼容时，旧 Suspension 写入 `failure_code = POLICY_STALE` 并转为 `REPLAN_REQUIRED`，不会沿旧安全决策自动执行。
14. CapabilityGrant 分别尝试超 scope、超 TTL、超 max_uses、撤销后使用和跨 Run 使用，全部被 Broker 拒绝。
15. `READY_TO_RESUME` 持久化后、AgentLoop 唤醒前强杀 App，重启扫描能够重新 claim，且只恢复一次目标 slot。
16. 两个 execution slot 同时竞争同一 PTY、privilege challenge、OAuth state 或不可重入 target，只有一个 ResourceLease claim 成功。
17. OAuth callback 和 authorization code 重放、过期、state 不匹配或 redirect URI/callback binding 不匹配时，不创建 Grant，也不推进 Suspension。
18. 用户输入密码或 OTP 后在 `RESOLUTION_RECEIVED`、fulfillment claim 前强杀 App，重启后进入 `WAITING_USER_REENTRY`，不把丢失明文当作 FAILED、UNKNOWN 或自动重试。
19. `FULFILLING` 强杀后 Adapter reconcile 分别模拟 `DELIVERED`、`NOT_DELIVERED`、`UNKNOWN`；三种结果分别进入正常继续、`WAITING_USER_REENTRY` 或 `DELIVERY_UNKNOWN / USER_DECISION_REQUIRED`。
20. Run 在 `WAITING_USER`、`RESOLUTION_RECEIVED`、`FULFILLING`、`DELIVERED`、`READY_TO_RESUME`、`RESUMING` 和 OAuth 浏览器打开时取消，旧 claim 全部失效，Fulfillment 只对账且不恢复 AgentLoop。
21. `MODEL_HINT` 与 `EXECUTOR_PROVEN` 同时调用 `suspend()`，Room 只保留一条 active Suspension；高可信证据 CAS 升级原记录，不生成第二张卡片。
22. 用户提供 Token、sudo 密码或完成 OAuth 后，Approval Gate 仍为未允许时 Tool 不执行；Approval 允许但 capability 不可用时同样不执行。
23. Room 投影与内存 Flow 故意不一致时，RunGateCoordinator 以持久化 Suspension、Run terminal 状态和 checkpoint 重建 Barrier。
24. OAuth `code_verifier` 只在 Secure Store 存在，完成、取消、过期后清理；Room、日志、聊天和通知中均无 verifier 明文。
25. Run terminal 的四种对账结果分别返回 `RUN_TERMINATED`、`RUN_TERMINATED_AFTER_DELIVERY`、`RUN_TERMINATED_NOT_DELIVERED`、`RUN_TERMINATED_EXTERNAL_STATE_UNKNOWN`，文案不产生“Tool 失败但可重试”的歧义。

## 18. 最终结论

EveryTalk 当前缺少的能力可以归纳为一个问题：

> Agent 在执行期遇到外部信任边界时，缺少安全、可恢复、可持久化的人类接力协议。

解决方案应当把 `AgentPauseRequest`、`AgentApprovalRecord`、UI 待处理状态和恢复入口收敛为一个 `AgentInterventionBroker`。长期领域模型使用 `AgentSuspensionRecord` 或 `AgentRunGateRecord`，旧 `AgentApprovalRecord` 只承担迁移兼容。Secret、第二台 VPS、sudo、PTY、OAuth 只是不同 Adapter，不再各自拥有一套暂停和恢复逻辑。

统一的核心是：

```text
一个 Capability Request Interface
一个可信 Policy Registry
一套 Suspension 与恢复账本
一套 Continuation 模型
一组本地履行 Adapter
一个 Tool Batch Barrier
一组 durable CAS claim
```

稳定领域模型收敛为：

~~~text
Suspension
StoredAuthorization
Capability
CapabilityGrant
Binding
Fulfillment
Continuation
~~~

这样 Agent 遇到外部 capability 缺口时，可以把问题交给用户处理，用户完成后还能沿着正确的 Continuation 继续执行。在声明的信任模型内，Secret 不进入模型，也不能被 Agent 任意读取；Agent 只能调用受限语义 capability。

Agent 完全控制远端主机时，系统不依赖同主机进程隔离保护 Secret。高敏感 capability 必须在 Agent 权限 Seam 外履行或被拒绝。

### 18.1 尚未解决的架构风险

1. 某些第三方 CLI 强制要求环境变量或配置文件，需要专用语义 Adapter 和 brokered subprocess；不能退回到 Agent 任意 shell。
2. SSHJ、PTY、终端缓冲区和 Android 进程重启之间的真实行为需要隔离 VPS 和真机验证。
3. OAuth 回调、系统浏览器切换和 App 被回收之间的 state 恢复需要真实设备验收。
4. 每个 Adapter 的 `reconcile()` 必须有可验证事实来源，否则只能安全停在 `DELIVERY_UNKNOWN`。
5. 多服务器资源句柄会扩大权限模型，需要继续校验服务器选择、Host Key 和资源归属。
6. Android 输入法、Compose 和 JVM GC 可能产生不可控敏感副本，只能通过最小化生命周期和禁止传播降低风险。
7. 普通 Linux 进程 lineage 和二进制路径无法证明已被 root 完全控制的主机可信，高敏感 Attestation 需要可信 Helper 或外部委托。
8. 任意 shell Tool 无法稳定计算完整世界状态，不能证明前置条件时必须进入 `REPLAN_REQUIRED`。
9. PTY 输出可能发生复杂终端变换，无法可靠脱敏时必须丢弃敏感窗口的 Agent View。
