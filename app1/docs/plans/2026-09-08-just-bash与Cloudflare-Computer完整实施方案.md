# EveryTalk：just-bash 与 Cloudflare Computer 完整实施方案

## 1. 结论与范围

本方案在 EveryTalk 现有 Agent、Workspace、Computer 和工具执行体系上增加两项能力：

1. 在 App 内提供始终可用的轻量 `local_bash`，底层使用 just-bash。
2. 把 Cloudflare 接入为一种新的 Computer Provider，与 SSH/VPS 平级。

本方案不重构现有服务器体系，不让 Cloudflare 取代 VPS，也不在第一阶段运行 Wrangler、Node.js、Python 或完整 Linux。

目标架构如下：

```text
EveryTalk Agent
├── 内置工具
│   ├── read / write / edit
│   └── local_bash -> just-bash -> Local Workspace
└── 当前绑定 Computer
    ├── SSH Provider -> VPS / Container
    └── Cloudflare Provider -> Workers / D1 / KV / R2
```

第一阶段只交付本地 just-bash 和 Cloudflare Workers 的基础管理能力。D1、KV、R2、Durable Objects、Queues、Cron 和 Temporary Worker 在基础链路稳定后逐步增加。

## 2. 产品交互

### 2.1 添加入口

Cloudflare 复用现有入口：

```text
设置 -> 服务器 -> 右上角添加 -> Cloudflare
```

添加对话框包含：

| 字段 | 说明 |
| --- | --- |
| 名称 | EveryTalk 中显示的名称，例如“个人 Cloudflare” |
| 登录按钮 | 启动 Cloudflare OAuth + PKCE |
| 登录身份 | 登录成功后显示 Cloudflare 用户邮箱或显示名 |
| Account | 从授权身份可访问的 Account 中选择一个 |
| 授权范围 | 展示本次申请的 Workers 相关权限 |
| 保存 | 完成校验后保存为一个 Cloudflare Computer |

登录成功后不能静默选择第一个 Account。用户有多个 Account 时，必须显式选择目标 Account。

### 2.2 多账号与多 Account

Cloudflare 登录身份、Cloudflare Account 和 EveryTalk Computer 是三个概念：

```text
一个 OAuth 身份
├── Cloudflare Account A -> EveryTalk Computer 1
└── Cloudflare Account B -> EveryTalk Computer 2
```

用户可以重复执行“添加 -> Cloudflare”，因此可以继续添加第一个 Cloudflare 账号，也可以添加多个 Cloudflare Account。

服务器列表中 Cloudflare 与 VPS 平级：

```text
我的服务器
├── 美国 VPS
├── 个人 Cloudflare
└── 公司 Cloudflare
```

### 2.3 详情页操作

Cloudflare Computer 详情页提供：

- 切换 Account：修改当前 Computer 绑定的 `accountId`。
- 重新授权：重新执行 OAuth，保留名称和资源绑定。
- 退出登录：撤销或清除本地授权，使依赖该授权的 Computer 进入未授权状态。
- 删除：删除 EveryTalk 的 Computer 记录和本地授权引用。
- 查看授权范围和连接状态。

退出登录和删除不会删除 Cloudflare 上的 Worker、D1、KV 或 R2。删除云端资源必须由单独的资源操作触发，并按现有敏感操作规则确认。

### 2.4 Agent 交互原则

Web 端模型不负责猜测 Android UI。工具返回结构化的结果和干预请求，App 根据结构化类型渲染按钮、授权页、选择器或确认框。

示例：

```json
{
  "ok": false,
  "code": "AUTHORIZATION_REQUIRED",
  "intervention": {
    "type": "OAUTH",
    "provider": "CLOUDFLARE",
    "scope": ["workers.read", "workers.write"]
  }
}
```

模型只需要理解“当前缺少 Cloudflare 授权并等待用户完成授权”，不需要输出 Android 控件名称或页面跳转指令。

## 3. 核心架构

### 3.1 Provider 抽象

现有 SSH 逻辑继续使用 `ComputerRepository`、`ComputerWorkspaceManager`、`ComputerToolExecutor` 和连接池。新增 Provider 层后，工具执行器根据当前 Computer 的 Provider 路由请求。

```text
ComputerToolExecutor
        |
        v
ComputerProviderRouter
   ├── SshComputerProvider
   └── CloudflareComputerProvider
```

Provider 负责目标能力、请求校验、执行、状态查询和错误映射。Agent Run、Tool Result、Suspension、Capability Grant 和恢复机制继续由现有 Agent 层负责。

建议的能力集合：

```text
local.workspace.read
local.workspace.write
local.shell.execute
computer_worker_list
computer_worker_read
computer_worker_create
computer_worker_update
computer_worker_deploy
computer_worker_status
computer_worker_logs
computer_worker_delete
computer_d1_*
computer_kv_*
computer_r2_*
```

Cloudflare 不暴露成拥有完整 Linux 的 `computer/bash`。模型只能看到当前 Provider 声明支持的结构化工具。

### 3.2 数据模型

现有 `Computer` 模型偏向 SSH，需要增加 Provider 区分并保留旧字段兼容。推荐逻辑模型如下：

```text
Computer
├── id
├── displayName
├── provider: SSH | CLOUDFLARE
├── permissionMode
├── status
├── workspaceBinding
└── providerConfigRef

CloudflareComputerConfig
├── computerId
├── authorizationId
├── accountId
├── accountName
└── capabilities

CloudflareAuthorization
├── authorizationId
├── provider
├── credentialReference
├── grantedScopes
├── issuedAt
├── expiresAt
├── revoked
└── generation
```

OAuth Token 明文只进入安全存储，Room 只保存引用、范围、时间和撤销状态。现有 `AgentStoredAuthorization`、`AgentOAuthState` 及其恢复机制可以作为实现基础。

`ComputerRunMode.DIRECT` 和 `CONTAINER` 继续表示 VPS 执行模式，不能用来表示 Cloudflare Worker。

### 3.3 Workspace 绑定

每个会话仍然只有一个明确的 Workspace。Cloudflare Computer 的 Workspace 是本地项目 Workspace，用于保存 Worker 源码、配置和部署前文件；它不是 Cloudflare 远程文件系统。

推荐使用：

```text
AgentRun
 -> conversationId
 -> workspaceId
 -> computerId
 -> provider
```

切换 Computer 时必须重新校验 Workspace、Computer 和 Provider 是否匹配。Cloudflare Worker 的部署包只能来自当前授权 Workspace，不能由工具参数传入任意 Android 路径。

## 4. just-bash 实施方案

### 4.1 运行方式

just-bash 以 TypeScript 构建为 JS Bundle，随 APK 打包。Kotlin 通过稳定的 `JustBashRuntime` 接口调用它：

```text
Kotlin Agent Tool
    -> JustBashRuntime
    -> Android JS Runtime
    -> just-bash
    -> Workspace File Bridge
```

第一版优先验证轻量 JS Runtime。WebView 只作为 PoC 运行时，不能作为长期后台执行的最终依赖。运行时选择不影响 Kotlin 外层接口。

### 4.2 文件系统

第一版采用“虚拟文件系统 + Kotlin 持久化桥接”：

- just-bash 只能看到当前 Workspace 的虚拟根目录。
- Kotlin 负责将文件读写到 App 私有目录。
- 任何路径先规范化，再校验是否仍位于当前 Workspace 根目录。
- 拒绝绝对路径、`..` 越界、符号链接越界和跨 Workspace 访问。
- 限制单文件大小、Workspace 总大小、递归深度和一次命令输出大小。

不要把整个 Android 文件系统挂载给 just-bash，也不要把真实路径暴露给模型。

### 4.3 第一版支持范围

第一版只启用浏览器类环境可用的 Shell 核心和内置命令：

```text
cat head tail grep find sed awk jq sort wc cut tr
echo printf pwd mkdir cp mv rm
管道、重定向、环境变量和退出码
```

Node-only Python、SQLite、真实磁盘 FS、网络访问、后台常驻进程和系统命令暂不接入。

### 4.4 local_bash 工具

工具输入至少包含：

```json
{
  "command": "grep -R \"TODO\" .",
  "cwd": ".",
  "timeout_ms": 10000,
  "max_output_chars": 20000
}
```

工具结果至少包含：

```json
{
  "ok": true,
  "exit_code": 0,
  "stdout": "...",
  "stderr": "...",
  "truncated": false
}
```

命令正文和输出要遵守现有上下文压缩、敏感信息过滤和 Prompt Injection 防护规则。Shell 输出是外部内容，不能直接改变 Agent 权限或触发任意授权表单。

## 5. Cloudflare 实施方案

### 5.1 OAuth + PKCE

Android 作为 public client，不在 APK 中保存 Client Secret。流程如下：

```text
添加 Cloudflare
    -> 生成 state、code_verifier、nonce
    -> 打开 Cloudflare 授权页
    -> 用户登录并授权
    -> Deep Link / App Link 回调
    -> 校验 state 和回调错误
    -> 用 code + verifier 换取 Token
    -> 保存安全存储引用
    -> 获取 Account 列表
    -> 用户选择 Account
    -> 创建 Cloudflare Computer
```

OAuth 状态必须短期有效、单次消费，并绑定当前 AgentRun 或添加流程。回调不能仅凭 `code` 创建连接。

### 5.2 Workers API

第一阶段实现：

- 列出当前 Account 的 Worker
- 读取 Worker 源码和元数据
- 创建或更新 Worker
- 部署 Worker
- 查询部署状态
- 读取受限的部署日志

Worker 工具统一返回 `execution_id`、状态、错误码和安全摘要，以便复用 Agent 的前台、后台、取消和恢复语义。Cloudflare API 的 HTTP 响应不能原样塞入模型上下文，应提取稳定字段并限制正文大小。

### 5.3 部署包

部署流程固定为：

```text
读取当前 Workspace
    -> 检查入口和配置
    -> 排除 Secret 与无关文件
    -> 生成文件清单和内容 hash
    -> 显示或记录部署摘要
    -> 调用 Cloudflare API
    -> 记录 deploymentId
    -> 查询最终状态
```

部署包规则：

- 入口文件必须明确。
- 默认排除 `.env`、私钥、Token、证书和系统目录。
- 限制单文件、总包和请求体大小。
- 同一 `requestHash` 不重复部署相同内容。
- 失败时保存可重试的安全摘要，不保存 Token 或完整敏感响应。

### 5.4 后续资源

D1、KV、R2 等功能都作为独立能力加入，不把它们混入 Workers 工具：

```text
computer_d1_query
computer_d1_migration
computer_kv_get
computer_kv_put
computer_r2_list
computer_r2_upload
```

每种资源都需要独立的 scope、资源 ID 校验、输出限制和危险操作确认。

## 6. 权限、安全与生命周期

### 6.1 权限分层

建议按三层处理：

1. Provider 能力：当前目标是否支持该操作。
2. 授权范围：Cloudflare OAuth 是否授予该操作。
3. 用户确认：部署、删除、写入 Secret、数据库变更等操作是否需要确认。

Workspace 内容、远端 Worker 内容和模型输出都属于不可信输入，不能注册新的能力、扩大 OAuth scope 或绕过确认。

### 6.2 状态

Cloudflare Computer 至少支持：

```text
DRAFT
AUTHORIZING
ACCOUNT_SELECTION_REQUIRED
READY
AUTHORIZATION_REQUIRED
OFFLINE
ACTION_REQUIRED
ERROR
DISCONNECTED
DELETING
DELETED
```

Token 过期、授权撤销、Account 不存在、权限不足、Worker 部署失败和网络超时应映射成稳定错误码，由 App 本地化展示。

### 6.3 退出与删除

退出登录：

- 撤销或清除本地 Token。
- 增加授权 generation，旧请求全部失效。
- 关联 Computer 标记为 `AUTHORIZATION_REQUIRED`。
- 保留名称、Account ID 和 Workspace 文件，方便重新授权。

删除 Computer：

- 先停止当前 Agent 对该目标的新操作。
- 等待或标记活动执行记录的最终状态。
- 删除本地 Computer 配置和授权引用。
- 保留云端资源，除非用户在具体资源操作中明确确认删除。

## 7. 端到端实施计划

每个阶段都必须完成“代码、迁移、交互、测试、文档”五类工作后才能进入下一阶段。阶段之间可以并行准备，但不能把未验证的 Provider 或运行时直接暴露给模型。

### Phase 0：冻结协议与技术验证

目标是先解决最容易导致返工的接口和运行时问题。

工作项：

- 定义 `ComputerProvider`、能力声明、Provider 配置引用和统一错误码。
- 定义 `local_bash`、Worker 工具和结构化 Intervention 的 JSON Schema。
- 确认 Cloudflare OAuth 应用、PKCE、Android 回调和申请的最小 scope。
- 验证 just-bash 在候选 Android JS Runtime 中的构建和运行。
- 验证虚拟 FS 的读、写、目录遍历、管道、退出码、超时和取消。
- 确定 Worker 单文件、总包、输出和日志上限。

交付物：协议文档、错误码表、Runtime PoC、Cloudflare 测试账号和最小集成测试。

完成标准：PoC 连续执行 100 次基础命令无资源泄漏；越界路径全部被拒绝；OAuth 回调的 state、过期和重复消费测试通过。

### Phase 1：Local Workspace 与 just-bash

目标是让没有 VPS 或 Cloudflare 的用户也能使用 Agent 的本地代码处理能力。

工作项：

1. 在 Android 工程中增加 JS Bundle 构建和 APK 资源打包流程。
2. 实现 `JustBashRuntime`，隔离 JS Runtime 选择，禁止业务层直接依赖 WebView 或 QuickJS API。
3. 实现 `WorkspaceFileBridge`，统一处理文件读写、目录列表、删除、文件大小和 hash。
4. 为每个会话映射独立的 App 私有 Workspace，兼容已有 Workspace 生命周期。
5. 注册 `local_bash`，接入 Agent Tool Registry、权限模式、执行记录、取消和恢复。
6. 实现 stdout/stderr 截断、退出码保留、错误摘要、超时清理和运行时重置。
7. 将命令输出标记为不可信外部内容，接入现有 Prompt Injection 防护。

第一版命令范围：`cat`、`head`、`tail`、`grep`、`find`、`sed`、`awk`、`jq`、`sort`、`wc`、`cut`、`tr`、`echo`、`printf`、`pwd`、`mkdir`、`cp`、`mv`、`rm`，以及管道、重定向、环境变量和退出码。

暂不支持网络、后台常驻进程、Python、SQLite、Node-only 模块和系统 Shell。

测试：Runtime 单元测试、文件边界测试、命令行为测试、超时取消测试、App 重启恢复测试和设备级最小自检。

完成标准：没有 Computer 时 Agent 能完成代码搜索、JSON 整理和文本修改；无法访问其他会话、其他 Workspace 或 Android 公共目录。

### Phase 2：Provider 抽象与旧链路适配

目标是在不改变 VPS 行为的情况下加入 Provider 路由。

工作项：

- 为现有 SSH/VPS 链路实现 `SshComputerProvider` 适配器。
- 在 `Computer` 中增加 Provider 字段和配置引用。
- 为旧 Room 数据提供默认 Provider 映射：已有记录全部映射为 `SSH`。
- 增加数据库迁移、序列化兼容和旧记录读写测试。
- 将 `ComputerToolExecutor` 的目标校验、Workspace 校验和权限判断前移到 Provider Router。
- 保留现有 SSH/SFTP/PTY、Container、Host 命令、取消、后台任务和恢复逻辑。
- 让 Provider 能力决定模型可见工具，避免所有 Computer 都展示同样的工具。

测试：旧数据迁移、SSH 回归、Provider 路由、错误码稳定性、Workspace/Computer 不匹配和 Agent 恢复测试。

完成标准：已有 VPS 的添加、连接、执行、文件传输、后台任务、取消和删除行为不变；Provider 不匹配请求在执行前被拒绝。

### Phase 3：Cloudflare 连接、账号和授权

目标是完成“设置 -> 服务器 -> 添加 -> Cloudflare”的完整连接体验。

工作项：

1. 在添加 Computer 对话框增加 Cloudflare 类型选择。
2. 增加名称、登录状态、Account 选择、授权范围和保存状态。
3. 使用 Authorization Code + PKCE，不在 APK 保存 Client Secret。
4. 使用随机 state、code_verifier 和 nonce；OAuth 状态短期有效、单次消费并绑定添加流程。
5. 回调时校验 state、redirect URI、错误码和过期状态。
6. Token 只保存到 Android 安全存储，Room 只保存 credential reference、scope、时间和 generation。
7. 获取 Account 列表；多 Account 时强制用户选择；Account 信息只保存必要字段。
8. 创建 `CloudflareComputerConfig`，将一个 Computer 绑定到一个 Account。
9. 支持同一 OAuth 身份创建多个 Cloudflare Computer。
10. 实现重新授权、切换 Account、退出登录和删除本地 Computer。

状态流：

```text
DRAFT -> AUTHORIZING -> ACCOUNT_SELECTION_REQUIRED -> READY
                    \-> ERROR
READY -> AUTHORIZATION_REQUIRED -> AUTHORIZING
READY -> DELETING -> DELETED
```

测试：取消登录、拒绝授权、重复回调、过期回调、多个 Account、同身份多 Computer、Token 失效、退出登录和删除隔离测试。

完成标准：用户能添加、查看、切换和删除多个 Cloudflare Computer；任何本地退出或删除操作都不会删除云端资源。

### Phase 4：Workers 基础管理

目标是让 Agent 能管理 Worker 的完整基础生命周期。

工作项：

- 实现 Cloudflare API Client，统一超时、重试、分页、HTTP 错误和响应大小限制。
- 实现 Worker 列表、读取、创建、更新、部署和状态查询。
- 实现 Worker 源码、模块格式、入口和环境配置的本地模型。
- 增加 Worker 资源选择器，避免让模型依赖自由文本拼接 URL。
- 将每次部署保存为执行记录，包含 `requestHash`、`deploymentId`、状态和安全摘要。
- 区分请求未发出、请求结果未知、部署失败和部署成功四种状态。
- 对写入、部署和删除工具使用现有 Permission Mode 与用户确认机制。
- 将 API 原始响应转换为稳定的本地化错误码和有限长度结果。

推荐工具：

```text
computer_worker_list
computer_worker_read
computer_worker_create
computer_worker_update
computer_worker_deploy
computer_worker_status
```

测试：分页、空列表、权限不足、Worker 不存在、网络中断、未知部署结果、重复 hash、部署状态恢复和输出截断。

完成标准：Agent 能在当前 Cloudflare Account 中读取、修改和部署 Worker；部署结果在 App 重启后仍能查询；重复执行相同请求不会产生无意义重复部署。

### Phase 5：本地项目到 Worker 的部署工作流

目标是把 just-bash 与 Cloudflare Provider 组成可靠的代码到部署链路。

工作流：

```text
用户提出部署需求
    -> local_bash 整理和检查 Workspace
    -> 识别入口、依赖和配置
    -> Secret 与文件安全检查
    -> 生成文件清单和 hash
    -> 用户确认部署摘要
    -> Cloudflare Provider 上传
    -> 保存 deploymentId
    -> 查询并展示最终状态
```

工作项：

- 定义 Worker 项目清单格式和入口约定。
- 默认排除 `.env`、私钥、证书、Token、构建缓存和系统目录。
- 检查文件路径、扩展名、大小、总包大小和编码。
- 支持模块 Worker 的多文件上传；不在 App 内运行完整 Wrangler。
- 将部署前 diff、文件清单和 hash 写入安全审计摘要。
- 部署前发现敏感文件时阻止上传并指出文件名。
- 部署失败时保留 Workspace，允许用户修复后重新部署。

测试：空项目、缺入口、多入口、敏感文件、超大文件、模块 Worker、相同内容重复部署、上传中断和恢复。

完成标准：用户可以从本地 Workspace 生成 Worker 并部署，且部署包边界可解释、可审计、可重试。

### Phase 6：Worker 日志、版本和运行状态

目标是提供部署后的可观察性，让 Agent 能判断 Worker 是否真正可用。

工作项：

- 增加部署历史、版本 ID、时间、状态和失败原因。
- 增加受限日志查询和时间范围过滤。
- 对日志做敏感字段过滤、行数限制和字符截断。
- 支持 Worker URL、健康检查结果和最近部署状态展示。
- 区分 Cloudflare API 状态、部署状态和 Worker 运行时状态。
- 为未知状态保留 deploymentId 和下一次查询时间，避免重复创建部署。

测试：日志为空、日志过大、敏感内容、部署后延迟、状态未知、权限不足和 App 重启后查询。

完成标准：用户和 Agent 能区分“上传成功但尚未生效”“部署失败”和“Worker 运行报错”。

### Phase 7：D1、KV、R2 与其他 Cloudflare 资源

目标是逐步扩展 Cloudflare Computer 的资源能力，每类资源独立设计、独立授权和独立验收。

#### D1

- 列出数据库、读取 schema、执行只读查询。
- 写入查询和 migration 必须单独确认。
- 限制查询行数、字段数、执行时间和结果大小。
- 迁移操作保存 migration hash，防止重复执行。
- 返回结构化列名、类型、行数和截断状态。

工具：`computer_d1_list`、`computer_d1_schema`、`computer_d1_query`、`computer_d1_migration`。

#### KV

- 支持 namespace 列表、key 列表、读取、写入和删除。
- 写入和删除需要资源级权限及确认。
- key、value 大小和列表分页必须限制。
- 默认隐藏疑似 Secret 的 value，除非用户明确查看。

工具：`computer_kv_list_namespaces`、`computer_kv_list_keys`、`computer_kv_get`、`computer_kv_put`、`computer_kv_delete`。

#### R2

- 支持 bucket 列表、对象列表、元数据读取、上传和删除。
- 大文件上传使用分段或明确失败，不在内存中无限缓存。
- 上传前检查路径、大小、类型和敏感文件。
- 删除对象必须确认并支持幂等结果。

工具：`computer_r2_list_buckets`、`computer_r2_list_objects`、`computer_r2_get_metadata`、`computer_r2_upload`、`computer_r2_delete`。

#### Durable Objects、Queues、Cron

这些能力依赖 Worker 绑定和项目配置，放在 D1/KV/R2 稳定后实现。先支持读取和状态查看，再增加写入、触发和删除操作。

完成标准：每种资源都能独立声明 capability、scope、资源绑定、确认规则、输出上限和恢复行为；新增资源不改变已有 Worker 和 SSH 工具。

### Phase 8：Temporary Worker

目标是让没有 Cloudflare 账号的用户先体验临时部署。

流程：

```text
生成项目
    -> 检查并打包
    -> 创建临时 Worker
    -> 返回临时 URL 和过期时间
    -> 用户测试
    -> 用户打开 Claim URL
    -> 转入用户 Cloudflare Account
```

工作项：

- 将临时部署明确标记为 `TEMPORARY`，不伪装成正式 Computer。
- 保存过期时间、临时部署 ID、Claim 状态和 URL 引用。
- 到期后停止展示并清理本地状态，不能把过期资源当作可用目标。
- Claim 过程必须在 Cloudflare 页面完成，App 只负责打开和刷新状态。
- Claim 成功后重新绑定到用户选择的 Cloudflare Computer。
- 临时 Worker 默认禁止敏感 Secret、私有网络和高风险资源操作。

测试：创建失败、过期、重复 Claim、Claim 取消、网络中断和转移后状态刷新。

完成标准：临时部署不会污染正式 Computer 数据模型；过期和 Claim 状态可恢复；用户能明确知道临时 URL 的生命周期。

### Phase 9：发布、迁移与收尾

目标是把功能从测试能力变成稳定产品能力。

工作项：

- 完成 Room migration、旧数据回读和降级错误处理。
- 完成中文文案、空状态、错误状态、未授权状态和无障碍标签。
- 增加 API 配置、调试日志和用户隐私说明中的 Cloudflare 数据说明。
- 增加远程功能开关，按阶段开放 Local Bash、Cloudflare Workers 和资源操作。
- 先对内部测试账号灰度，再逐步开放正式账号。
- 监控 OAuth 失败率、部署未知率、API 限流、Runtime 崩溃和 Workspace 超限。
- 在发现严重问题时关闭对应 capability，不删除已有本地数据。

发布门槛：核心测试、设备测试、真实测试账号端到端测试、数据迁移测试、隐私检查和回滚演练全部完成。

## 8. 失败场景与处理

| 场景 | 处理 |
| --- | --- |
| OAuth 回调被取消 | 返回可重试结果，保留添加对话框输入 |
| Token 过期 | 标记未授权，启动重新授权，不重复执行原写操作 |
| Account 被删除或不可见 | 标记需要操作，要求用户重新选择 Account |
| Worker 部署状态未知 | 保存 deploymentId，进入恢复和查询流程 |
| 网络中断 | 区分“请求未发出”和“结果未知”，避免盲目重试部署 |
| Workspace 含敏感文件 | 阻止打包并指出文件名，不能自动上传 |
| just-bash 超时 | 返回超时状态，释放运行时资源，保留 Workspace |
| App 被系统回收 | 通过 AgentRun 和执行记录恢复可恢复状态 |
| Cloudflare 不支持某命令 | 返回 Provider 能力错误，引导模型改用结构化 Worker 工具 |

## 9. 代码落点建议

实现时优先复用现有目录，不新增顶层目录：

```text
app1/app/src/main/java/com/android/everytalk/data/computer/
├── ComputerModels.kt                 # Provider、Cloudflare 配置和状态模型
├── ComputerProvider.kt               # Provider 能力与执行接口
├── SshComputerProvider.kt            # 现有 SSH 链路适配
├── CloudflareComputerProvider.kt     # Cloudflare API 与 Worker 操作
├── CloudflareOAuthClient.kt          # OAuth + PKCE
├── CloudflareApiClient.kt            # Workers API
└── JustBashRuntime.kt                # Kotlin 到 JS Runtime 的稳定接口

app1/app/src/main/java/com/android/everytalk/data/agent/
├── AgentToolExecutorRegistry.kt      # 注册 local_bash 与 Cloudflare 工具
└── AgentIntervention*.kt             # 复用现有授权和干预机制

app1/app/src/main/java/com/android/everytalk/ui/
└── .../computer/                    # 添加对话框、Account 选择和详情操作
```

具体文件名以当前代码结构为准，先搜索现有职责，避免重复创建 Repository、Executor 或授权存储。

## 10. 验收标准

- 未连接任何 Computer 时，`local_bash` 可以使用。
- just-bash 无法访问当前 Workspace 之外的文件。
- App 重启后 Workspace 和可恢复执行状态可正确对账。
- 添加 Cloudflare 复用“设置 -> 服务器 -> 添加”入口。
- OAuth 使用 PKCE，APK 不含 Client Secret。
- 一个身份可以绑定多个 Cloudflare Account。
- Cloudflare 与 VPS 在服务器列表中平级显示。
- Agent 只能调用当前 Provider 声明的能力。
- Worker 部署具有幂等键、状态查询和未知结果处理。
- 退出登录、删除本地 Computer 都不会意外删除云端资源。
- 所有 Token、私钥、Secret 和敏感 API 响应都不进入普通日志或模型上下文。
- 现有 SSH/VPS 相关行为和测试不回归。

## 11. 最终原则

1. just-bash 属于 EveryTalk，是本地 Workspace 的通用轻量工具。
2. Cloudflare 与 VPS 平级，都是 Computer 的不同 Provider。
3. Cloudflare Computer 提供结构化云资源能力，不伪装成完整 Linux。
4. 登录身份、Cloudflare Account 和 EveryTalk Computer 分开建模。
5. App UI 由结构化工具结果驱动，不能依赖模型猜测交互方式。
6. 本地 Workspace 负责整理和准备文件，Cloudflare Provider 负责上传和部署。
7. 所有高风险操作都经过能力、授权和用户确认三层校验。
