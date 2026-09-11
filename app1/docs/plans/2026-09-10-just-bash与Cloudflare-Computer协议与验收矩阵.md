# just-bash 与 Cloudflare Computer 协议与验收矩阵

本文是
[2026-09-08-just-bash 与 Cloudflare Computer 完整实施方案](2026-09-08-just-bash与Cloudflare-Computer完整实施方案.md)
的实现对照文档。它记录当前代码已经固定的边界、工具协议、稳定错误码和验证证据，避免只通过修改提示词约束 Agent。

## 1. 架构边界

```text
AgentLoop
  -> AgentToolRuntime
    -> AppToolExecutor
      -> ComputerManager
        -> ComputerProviderRouter
           -> SSH 既有 ComputerToolExecutor
           -> CloudflareComputerProvider

无远程 Computer：
AgentToolRuntime -> local_bash -> JustBashRuntime -> App 私有 Workspace
```

`Computer.provider` 决定远程工具目录和执行 Provider。Cloudflare Computer 不获得 `exec`、PTY、SFTP 或任意系统 Shell；SSH Computer 不获得 Cloudflare 工具。`ComputerRunMode` 只描述 SSH 的容器/直连模式。

## 2. 稳定错误码

| 错误码 | 含义 | 是否可重试 |
| --- | --- | --- |
| `COMPUTER_NOT_FOUND` | Computer 不存在 | 否 |
| `PROVIDER_MISMATCH` | 工具与当前 Provider 不匹配 | 否 |
| `PROVIDER_CONFIG_NOT_FOUND` | Provider 配置缺失 | 否 |
| `COMPUTER_CONTEXT_MISMATCH` | Computer、Workspace、Account 或请求快照不一致 | 否 |
| `AUTHORIZATION_REQUIRED` | Token 缺失、过期、撤销或授权不存在 | 重新授权后 |
| `PERMISSION_DENIED` | Computer capability 或 OAuth scope 不足 | 补充授权后 |
| `CONFIRMATION_REQUIRED` | 写操作尚未获得用户确认 | 用户确认后 |
| `APPROVAL_MISMATCH` | 批准内容与当前请求、Account、generation 或 hash 不一致 | 重新确认 |
| `RESULT_UNKNOWN` | 写请求已可能发出但结果无法确认 | 先对账 |
| `REQUEST_TIMEOUT` | 只读请求超时 | 是 |
| `RATE_LIMITED` | Cloudflare 限流 | 延迟后 |
| `RESOURCE_NOT_FOUND` | 远端资源不存在 | 否 |
| `RESPONSE_TOO_LARGE` | 响应超过本地上限 | 否 |
| `SENSITIVE_OR_TOO_LARGE` | 上传内容包含敏感材料或超过限制 | 修复项目后 |
| `WORKSPACE_NOT_FOUND` | 当前 Workspace 不存在 | 否 |
| `UNSUPPORTED_OPERATION` | Cloudflare 官方接口不支持该动作 | 否 |
| `FEATURE_DISABLED` | 对应功能开关关闭 | 开启功能后 |
| `FEATURE_NOT_CONFIGURED` | 必需的外部 Gateway 或 OAuth 配置不存在 | 配置后 |

Cloudflare 原始响应正文不会直接进入模型上下文。日志、Queue Peek、Worker 配置和资源列表都带有长度/行数限制，并标记为不可信外部数据。

## 2.1 当前实现核验（2026-09-11）

本次实现已在代码层固定以下架构边界，并通过 Android JVM 定向测试：

- `ComputerProviderRouter` 按 `SSH` / `CLOUDFLARE` 路由，Cloudflare 不进入 SSH、PTY、SFTP 或容器工具链。
- `local_bash` 与 `local_file_save` 在 Cloudflare Worker 会话中仍可见，便于先检查本地 Workspace 再部署；保存仍必须经过独立确认。
- 外部 Worker、资源、日志和本地 Shell 输出统一先脱敏再限长；远端 JSON 的 `ok`、`error_code` 和 `intervention` 字段不能伪造 App 执行协议。
- R2 上传审批和实际上传复用同一份受限内存字节；文件在审批后变化会要求重新批准。
- Worker 上传在创建正式 deployment 前保存远端版本 ID；取消、超时和状态查询失败会进入可恢复对账状态，冷启动不盲目重复上传。
- D1 多语句写入在 API 返回业务错误时保留 `RESULT_UNKNOWN`，避免把部分执行误判为未发送。
- Queue Worker binding 使用可信索引中的 queue ID 校验，并在部署协议中转换为 Cloudflare 要求的 queue name。
- Worker 状态查询使用 Cloudflare 官方 workers.dev 子域名接口生成可验证 URL。

本次 JVM 定向验证命令：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "com.android.everytalk.data.computer.*" --console=plain
```

该验证覆盖 Provider 路由、输出脱敏、Workspace 越界与配额、OAuth 状态、Cloudflare 资源操作、Worker 部署对账、Temporary Worker 状态机和迁移相关的已有测试。它不替代真实 Cloudflare OAuth、真实账号、Android 后台回收或弱网设备联调；这些需要配置测试账号和设备环境后才能验收。

## 3. 工具协议

### 3.1 `local_bash`

```json
{
  "command": "grep -R \"TODO\" .",
  "cwd": ".",
  "timeout_ms": 10000,
  "max_output_chars": 20000
}
```

```json
{
  "ok": true,
  "exit_code": 0,
  "stdout": "...",
  "stderr": "",
  "truncated": false,
  "persisted": false,
  "workspace_changes_discarded": true,
  "files_changed": 1,
  "changed_files": "src/index.ts",
  "deleted_files": ""
}
```

just-bash 先接收相对路径文件快照，在 AndroidX `JavaScriptSandbox` 中运行。命令产生的文件变化只存在于本次内存快照，不会自动写入持久 Workspace；需要保存时必须调用 `local_file_save`，由用户确认后通过 `LocalWorkspaceFileBridge` 统一验证路径、符号链接、文件数量、单文件大小、总大小并原子写入。Workspace 根目录属于 App 私有目录；运行时没有网络、系统命令或宿主路径访问能力。

### 3.2 Cloudflare Worker

```text
computer.worker.list
computer.worker.read
computer.worker.create
computer.worker.update
computer.worker.deploy
computer.worker.status
computer.worker.logs
computer.worker.health
computer.worker.delete
```

部署记录以 `computerId + accountId + workerName + requestHash` 为幂等边界，保存本地 deployment ID、远端 deployment ID、version ID、状态和安全摘要。`REQUEST_NOT_SENT`、`REQUEST_ACCEPTED`、`DEPLOYMENT_PENDING`、`DEPLOYMENT_SUCCEEDED`、`DEPLOYMENT_FAILED`、`RESULT_UNKNOWN` 不互相混淆。

### 3.3 资源工具

```text
computer.d1.list / schema / query / migration
computer.kv.list_namespaces / list_keys / get / put / delete
computer.r2.list_buckets / list_objects / get_metadata / upload / delete
computer.durable_objects.list / list_objects
computer.queues.list / get / metrics / peek / create / delete
computer.cron.list / update / trigger
```

D1 写查询、migration、KV 写入/删除、R2 上传/删除、Queue 创建/删除和 Cron 修改都必须经过 Provider 审批与执行前 generation 校验。`computer.cron.trigger` 保留稳定工具名，但当前 Cloudflare 官方 API 没有可确认的立即触发接口，因此固定返回 `UNSUPPORTED_OPERATION`，不拼接不存在的请求。

R2 当前采用明确失败边界：单次上传超过 32 MiB 直接拒绝，不把大文件无限读入内存。后续若接入分段上传，必须先增加独立协议和恢复账本。

## 4. 结构化干预

Provider 模型定义以下 UI 干预类型，App 根据类型渲染界面，模型不负责猜测 Android 控件或页面路径：

```text
OAUTH
ACCOUNT_SELECTION
REAUTHORIZATION
RESOURCE_SELECTION
CONFIRMATION
```

Cloudflare 错误结果在 `AUTHORIZATION_REQUIRED` 时包含 `intervention_type=REAUTHORIZATION`，资源未选择时包含 `intervention_type=RESOURCE_SELECTION`。添加流程仍固定为“设置 -> 服务器 -> 添加 -> Cloudflare”，Account 列表来自本次 Token 的 Cloudflare API 响应，多 Account 时不得默认选择第一个。D1、KV、R2、Durable Objects 和 Queue 的资源 ID 必须先由当前 Computer/Account 的列表接口写入短期本地索引，再由 App 选择；模型直接猜测的资源 ID 会被拒绝。

## 5. OAuth 安全协议

- APK 不保存 Client Secret。
- 每次授权生成随机 `state`、PKCE `code_verifier`、`code_challenge` 和独立 `nonce`。
- state 绑定添加流程或目标 Computer，短期有效且只能消费一次。
- 回调校验 redirect URI、错误参数、state、过期状态和目标绑定。
- 回调携带 nonce 时严格匹配；Cloudflare 不返回 nonce 时仍必须通过 state + PKCE。
- access token/refresh token 只进入 Android 安全存储；Room 只保存引用。
- 授权 generation 变化后，旧审批和旧执行请求失效。

## 6. 功能开关与真实状态

| 开关 | 默认值 | 作用 |
| --- | --- | --- |
| `local_bash_enabled` | 开启 | 本地 just-bash 和本地 Workspace 工具 |
| `cloudflare_enabled` | 关闭 | Cloudflare Provider 总开关 |
| `cloudflare_worker_write_enabled` | 关闭 | Worker 创建、更新、部署、删除和 Cron 写入 |
| `cloudflare_resource_tools_enabled` | 关闭 | D1/KV/R2/DO/Queue/Cron 资源工具 |
| `temporary_worker_enabled` | 关闭 | Temporary Worker 功能 |

Temporary Worker 的状态机和 Room 恢复已实现，但 Cloudflare 没有公开的匿名临时 Worker 创建接口；未配置真实 Gateway 时统一返回 `FEATURE_NOT_CONFIGURED`，禁止伪造 URL、deployment ID 或声称端到端可用。

## 7. 验收矩阵

| 验收项 | 当前证据 |
| --- | --- |
| Provider/能力隔离 | `ComputerProviderModelsTest`、`ComputerToolCallSafetyTest`、Router 路由校验 |
| OAuth state/PKCE/nonce/重复回调 | `CloudflareOAuthClientTest`、`CloudflareSettingsOAuthFlowTest`、`CloudflareSettingsOAuthStoreTest` |
| Account 归属和同身份多 Computer | `CloudflareComputerManagerTest`、Room DAO 原子条件更新 |
| Workspace 越界/符号链接/配额 | `LocalWorkspaceFileBridgeTest`、`WorkerPackageBuilderTest` |
| just-bash 命令、隔离和不自动落盘 | `tools/just-bash-runtime/src/runtime.test.ts`、`LocalBashToolExecutorTest` |
| Cloudflare 资源选择和 Account 隔离 | `CloudflareResourceIndexTest`、Provider 路由校验 |
| Worker 上传、deployment 和 UNKNOWN | `CloudflareApiClientTest`、`WorkerDeploymentManager` |
| D1/KV/R2/Queue/Cron API 边界 | `CloudflareApiClientTest`、Provider 统一 capability/scope/审批校验 |
| APK 不含 Client Secret | Debug APK 字符串扫描 |
| 真机 Sandbox、后台回收、弱网和真实 Cloudflare | 当前环境无 `adb` 设备和 Cloudflare 联调凭据，尚未完成 |

## 8. 发布前仍需外部验证

以下不是可以通过本地单元测试伪造完成的项目：

1. 配置正式 Cloudflare OAuth Client ID、回调域名和实际 scope。
2. 使用测试 Cloudflare Account 完成真实 OAuth、Account 选择、Worker 部署、D1/KV/R2 API 联调。
3. 在实体 Android 设备验证 JavaScriptSandbox、后台执行、系统回收恢复、大 Workspace 和弱网恢复。
4. 配置真实 Temporary Worker Gateway 后再打开 `temporary_worker_enabled`。
5. 解决工作区当前既有的 9 个 Agent/Compose 全量单元测试失败，再执行发布门禁。
