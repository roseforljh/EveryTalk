# 给 AI 一台服务器：Android 本地直连完整功能实施计划

## 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | 已确认，实施中 |
| 版本 | v2.2 |
| 日期 | 2026-08-12 |
| 目标仓库 | EveryTalk |
| Android 工程 | `app1/` |
| 核心架构 | Android 直接通过 SSH 连接用户 VPS |
| 替代文档 | 本文完整替代 v1.1 独立控制面方案 |

### 当前实施状态

Android 本地直连主链路已经进入完整功能收尾阶段。当前代码已经包含多服务器管理、Host Key 固定、Keystore 本地凭据、Direct 与 Container、会话级选服、自动 Workspace、七个 Tool、服务器详情、Secret、Private Preview、Public Preview、删除与本地审计。

v2.2 已同步以下实现：

1. Container helper 升级为 v3，旧版服务器会进入 `CONFIGURATION_REQUIRED` 并由详情页修复。
2. `exec(background=true)` 在 VPS 保存 `process_id`、PID、进程起始标记、状态、Exit Code、日志和更新时间。
3. 删除 Workspace 前根据状态文件停止已记录后台进程；Direct 模式同时核对 PID、起始标记、Session ID 和进程参数，防止 PID 复用导致误杀。
4. PTY 与 Private Preview 使用统一的 Channel 建立重试边界，端口转发纳入活跃 Channel 计数。
5. `PREVIEW_STOPPED`、审计结果和安全摘要具备独立中英文显示。
6. Public Preview 的 Container 到期由 VPS 端 `timeout` 执行，手机离线后仍会停止公网代理。

以下项目仍属于发布验收，当前文档不把它们标记为完成：

1. Ubuntu、Debian、amd64、arm64 的真实 VPS 矩阵。
2. Android 真机的四类 SSH 凭据、切网、锁屏、系统回收与 WebSocket Preview。
3. 抓包、云安全组、VPS 防火墙和 Host Key 更换的真实端到端验证。
4. CI 中的容器化 sshd、Container 安全断言、镜像扫描与 Release 制品。

## 1. 完整范围声明

本文是一份完整功能实施计划。全部里程碑共同组成最终交付范围，里程碑只表示依赖顺序。

本功能完成后，用户可以在 EveryTalk Android App 中添加和管理多台 Linux 服务器。每个聊天保存一台当前服务器，用户可以随时切换。开启 Agent 后，当前会话里的模型可以通过手机与 VPS 之间的 SSH 连接执行命令、读写文件、使用交互终端、上传和下载文件、运行后台服务并打开网页预览。

所有 Computer 功能只在以下两个位置运行：

1. EveryTalk Android App。
2. 用户自己控制的 VPS。

功能禁止依赖 EveryTalk 官方控制面、EveryTalk 中转服务器、PostgreSQL、Redis、BullMQ、官方凭据仓库和官方预览网关。SSH 凭据、命令、文件和预览流量不得发送给 EveryTalk 运营方。

资源策略已经确定：

1. 不设置 CPU 配额。
2. 不设置内存配额。
3. 不设置磁盘配额。
4. 不设置 PID 配额。
5. 不按 VPS 配置缩减 Agent 能力。
6. 不提供资源套餐、计费档位或 Container 规格选择器。
7. CPU、内存、磁盘和负载只用于状态展示与故障诊断。
8. 单次 Tool 返回长度、Android 文件读取大小和 SSH 握手时间仍有协议边界。这些边界只保护手机进程和模型上下文。
9. Container 不限制资源，用户需要承担任务耗尽自己 VPS 资源的风险；产品文案统一称为“Container 隔离环境”。

## 2. 冻结的产品决定

1. 设置页三点菜单增加“服务器”，进入独立服务器页面。
2. 服务器页面支持多张服务器卡片，左上角加号打开添加服务器悬浮卡片。
3. 服务器与会话关联，不写入模型配置。
4. 一个会话同时保存零台或一台当前服务器，用户可以随时切换。
5. 每个“会话 × 服务器”由系统自动创建唯一持久化 Workspace。
6. Workspace 没有独立 AI。模型、参数和 Agent Loop 继续由 Android 会话控制。
7. 关闭 Agent 只停止 Computer Tool 注入，保留服务器选择与所有 Workspace。
8. 切换模型不改变 Agent 开关、服务器选择和 Workspace。
9. 输入区加号功能面板增加 Agent。
10. Agent 使用 `drawable/ic_gpt_terminal.xml` 和独立青绿色。
11. 短按 Agent 负责开启或关闭。
12. 长按 Agent 负责为当前会话选择服务器。
13. 不可用服务器显示为不可选，点击后显示明确错误。
14. 输入框只显示紧凑 `Agent` 标签，禁止显示服务器名称和状态。
15. 不支持 Tool Call 的模型不能启用 Agent。
16. Android 直接连接用户 VPS，项目方不部署 Computer 后端。
17. SSH 凭据只在手机本地加密保存。
18. App 内私有预览通过 SSH 本地端口转发完成。
19. 公开预览直接暴露用户 VPS 端口，必须由用户逐次确认。

## 3. 一句话定义

> EveryTalk 在手机本地把用户自己的 VPS 接入当前会话的 Agent 工具循环。

技术定义：

> Android SSH 客户端负责控制与传输，VPS 上的 Docker 提供可选隔离，持久化 Workspace 保存成果，七个 Computer Tool 接入现有模型工具循环。

## 4. 完整用户路径

```text
用户已有一台或多台 Linux 服务器
          │
          ▼
从设置页三点菜单进入“服务器”页面
          │
          ▼
左上角加号填写 Host、端口、用户名和一次性登录凭据
          │
          ▼
Android 直接读取 SSH Host Key
          │
          ▼
用户确认 Host、IP、算法和 SHA256 指纹
          │
          ▼
Android 直接验证登录并安装当前服务器的专用 SSH Key
          │
          ▼
探测系统，配置 Direct SSH 或 Container 隔离环境
          │
          ▼
服务器卡片显示“已就绪”
          │
          ▼
在聊天输入区开启 Agent 并选择服务器
          │
          ▼
系统自动创建或恢复“会话 × 服务器”的 Workspace
          │
          ▼
模型通过手机本地执行器使用 VPS
```

体验要求：

1. 日常操作全部在手机完成。
2. 用户无需复制初始化 Shell 命令。
3. 用户无需部署 EveryTalk 服务。
4. 用户无需开通 EveryTalk 账号。
5. 用户无需把 SSH 凭据交给项目方。
6. VPS 不运行第二套 Agent Loop。
7. VPS 不保存模型 API Key、模型参数或聊天正文。
8. VPS 不安装监听公网端口的 EveryTalk daemon。
9. App 重启、手机网络切换或 VPS 重启后可以重新连接并恢复持久 Workspace。

## 5. 本地架构

### 5.1 数据流

```text
┌──────────────────────────────────────────────┐
│ EveryTalk Android                            │
│                                              │
│ Chat UI                                      │
│   │                                          │
│ Existing Provider Tool Loop                  │
│   │                                          │
│ ComputerToolExecutor                         │
│   │                                          │
│ ComputerManager                              │
│   ├── Android Keystore Credential Vault      │
│   ├── Room Computer / Workspace Metadata     │
│   ├── SSH Connection Pool                    │
│   ├── SFTP / PTY / Port Forward              │
│   └── Foreground Connection Service          │
└───┼──────────────────────────────────────────┘
    │ SSH
    ▼
┌──────────────────────────────────────────────┐
│ 用户 VPS                                     │
│                                              │
│ sshd                                         │
│   ├── Direct SSH Workspace                   │
│   │      ~/.everytalk/workspaces/<id>        │
│   └── Docker                                 │
│          └── EveryTalk Workspace Container   │
│                 /workspace                   │
└──────────────────────────────────────────────┘
```

### 5.2 Android 职责

1. 收集并本地校验服务器连接信息。
2. 在发送登录凭据前读取并确认 Host Key。
3. 使用 Android Keystore 加密保存 SSH 凭据和 Workspace Secret。
4. 建立、复用、检测和恢复 SSH 连接。
5. 通过 SFTP、PTY 和 SSH 端口转发实现七个 Tool。
6. 通过 SSH 配置 Docker、Helper、网络和 Workspace。
7. 保存服务器安全元数据、会话选择、Workspace 映射和执行摘要。
8. 把 Tool Schema 注入现有四类 Provider 工具循环。
9. 在活动连接期间运行前台服务，降低系统回收连接的概率。
10. App 被终止后重新探测远端状态，禁止伪造旧终端恢复。

### 5.3 VPS 职责

1. 通过现有 sshd 接收 Android 连接。
2. 保存 Workspace 文件。
3. 运行用户代码和后台进程。
4. 在容器模式下运行 Workspace Container。
5. 在用户确认公开预览时直接暴露指定端口。

### 5.4 明确不存在的组件

以下组件不会被创建：

1. `services/vps-computer-control-plane/`。
2. Computer REST API。
3. Computer 专用 TypeScript 服务。
4. Computer PostgreSQL 数据库。
5. Computer Redis 或任务队列。
6. 官方 SSH Credential Vault。
7. 官方 Preview Gateway。
8. Computer 后端环境变量。
9. 通配符预览域名。

## 6. 信任边界与隐私

| 数据 | 保存位置 |
| --- | --- |
| SSH 密码、私钥、私钥口令 | Android `noBackupFilesDir` 中的 Keystore 加密文件 |
| Android Keystore 主密钥 | Android 系统安全区，禁止导出 |
| Host Key Blob 与指纹 | Android Room |
| 服务器 Host、端口、用户名 | Android Room |
| 会话当前服务器 | Android Room |
| Workspace 映射 | Android Room 与用户 VPS |
| Workspace Secret | Android Keystore 加密文件，使用时短暂传入 VPS 进程 |
| Workspace 文件 | 用户 VPS |
| Tool 原始输出 | 当前模型 Tool Context |
| Tool 展示摘要 | Android 聊天消息与本地审计记录 |

Computer 功能禁止向 EveryTalk 项目方发送 SSH 凭据、sudo 密码、Workspace Secret、SSH 命令、输出、文件、Host Key、Host 和 Username。

模型 Provider 会接收模型主动调用 Tool 所需的命令、必要结果和文件片段，这是现有 Agent Loop 的工作方式。SSH 凭据、真实 Host、Username 和 Host Key 永远不进入模型上下文。所有运行模式首次启用 Agent 时都显示该数据流说明。

Android Keystore 保护静态凭据。App 建立 SSH 连接时会在当前进程内短暂取得明文凭据。设备已 Root、App 进程被注入或系统安全区失陷时，无法承诺凭据安全。

## 7. 支持矩阵

| 系统 | 架构 | Direct SSH | 已安装 Docker | 自动安装 Docker | Container 隔离环境 |
| --- | --- | --- | --- | --- | --- |
| Ubuntu 20.04、22.04、24.04 | amd64 | 支持 | 支持 | 支持 | 支持 |
| Ubuntu 20.04、22.04、24.04 | arm64 | 支持 | 支持 | 支持 | 支持 |
| Debian 11、12、13 | amd64 | 支持 | 支持 | 支持 | 支持 |
| Debian 11、12、13 | arm64 | 支持 | 支持 | 支持 | 支持 |

其他 Linux 在具备 OpenSSH Server、POSIX Shell 和基础命令时支持 Direct SSH。已经安装兼容 Docker 时允许探测 Container 隔离环境。探测失败后保留 Direct SSH 路径。

本地直连支持公网与局域网 IPv4、IPv6、域名、主机名和自定义端口。Host 字段禁止 URL、userinfo、路径、换行和 Shell 片段。

## 8. Android SSH 技术方案

### 8.1 SSH 库

新增成熟 Java SSH 客户端依赖，优先采用 SSHJ，并在锁定版本前完成以下 Android 真机自检：

1. 密码与 keyboard-interactive 认证。
2. RSA、ECDSA、Ed25519 私钥与带口令 OpenSSH 私钥。
3. Host Key 捕获和自定义验证器。
4. SFTP、exec、PTY Shell 和本地端口转发。
5. Keepalive、断线和重连。

如果 SSHJ 在 minSdk 27 或目标 ABI 上存在不可修复兼容问题，改用 Apache MINA sshd。禁止自研 SSH、SFTP 和密钥解析协议。

### 8.2 连接池

App 按 `computer_id` 管理连接：

```text
ComputerConnection
  ├── SSH transport
  ├── exec sessions
  ├── SFTP session
  ├── PTY sessions
  └── local port forwards
```

规则：

1. 同一台服务器复用握手和认证。
2. 每次连接都验证固定 Host Key Blob。
3. Keepalive 检测死连接。
4. 新 Channel 失败后丢弃旧连接并重连一次。
5. 空闲连接可以关闭，不改变服务器已配置状态。
6. Agent 关闭后允许关闭空闲连接，Workspace 和会话选择继续保留。
7. 连接池禁止在日志中输出凭据和完整 Host。

### 8.3 前台连接服务

以下活动期间启动 Android Foreground Service：

1. 模型正在执行 Computer Tool。
2. 交互终端处于打开状态。
3. App 内预览正在使用 SSH 转发。
4. 添加服务器或 Docker 配置仍在进行。

通知显示“Agent 正在使用服务器”，提供停止连接动作。全部活动结束后自动停止。只在命令执行和流式传输期间持有必要的局部 WakeLock。

系统强制停止 App 后，SSH、PTY 和本地端口转发立即失效。只有通过 `exec(background=true)` 启动的后台任务、已经独立运行的服务和正在运行的 Container 可以继续。结果尚未确认的前台命令进入 `UNKNOWN`，禁止自动重放；原 PTY 返回 `TERMINAL_LOST`，Private Preview 进入 `STOPPED`。后台任务的 PID、日志和状态文件保存在 VPS，App 下次启动后重新连接并对账。

PTY 和 Private Preview 在新 Channel 尚未启动时允许丢弃旧 Transport 并安全重连一次。Channel 已启动后禁止自动重放。端口转发创建成功后计入连接的活跃 Channel，网络切换、Disconnect 和前台通知停止动作都会关闭旧转发。

## 9. SSH Host Key 验证

### 9.1 首次连接

1. Android 只完成 DNS、TCP 和 SSH Key Exchange。
2. 此阶段不提交密码、私钥或私钥口令。
3. App 捕获完整 Host Key Blob、算法和 `SHA256:<base64>` 指纹。
4. UI 显示用户输入 Host、当前解析 IP、端口、算法与指纹。
5. 用户点击“确认并登录”。
6. App 重新建立连接，要求完整 Host Key Blob 与刚确认的值一致。
7. 匹配后才尝试认证。

### 9.2 再次连接

1. Room 保存完整 Host Key Blob、算法与指纹。
2. 所有重连都比较完整 Key Blob。
3. 任何变化都进入 `HOST_KEY_CHANGED`。
4. 该服务器无法被 Agent 选择，已有 Tool Call立即停止。
5. App 禁止静默接受新 Key。

### 9.3 合法换 Key

详情页显示旧指纹、新指纹和服务器商控制台核对说明。用户明确确认后更新本地固定 Key并生成本地审计事件。确认前不尝试认证。

## 10. 本地凭据生命周期

### 10.1 支持的输入

1. Password。
2. keyboard-interactive 密码。
3. SSH Private Key。
4. SSH Private Key 与可选 Passphrase。

### 10.2 一次性输入

1. 输入控件关闭自动填充、截屏预览和无关日志。
2. 原始输入不写入 Room、SharedPreferences、聊天和崩溃日志。
3. 登录成功后优先升级成 Computer 专属 SSH Key。
4. sudo 密码只存在于当前配置协程内，结束后清理引用。

### 10.3 每台服务器专用 Key

```text
手机本地生成当前 Computer 专属 Ed25519 Keypair
          │
          ▼
私钥由 Android Keystore 加密保存到 noBackupFilesDir
          │
          ▼
通过已认证连接追加公钥到 authorized_keys
          │
          ▼
建立第二条连接验证专用私钥
          │
          ├── 成功：清除原始凭据
          └── 失败：回滚新增公钥并保存加密回退凭据
```

`authorized_keys` 更新要求：

1. `umask 077`。
2. `~/.ssh` 为 0700。
3. `authorized_keys` 为 0600。
4. 使用精确公钥去重。
5. 使用临时文件与原子替换。
6. 保留已有 Key 和注释。
7. 注释固定为 `everytalk:<computer_id>`。

### 10.4 Android Keystore 信封加密

1. Keystore 生成不可导出的 AES-256-GCM 主密钥。
2. 每条 Credential 和 Workspace Secret 使用独立随机数据密钥。
3. 数据密钥由 Keystore 主密钥包装。
4. 加密文件保存 ciphertext、nonce、auth tag、wrapped key、版本与资源绑定 AAD。
5. 文件写入采用临时文件、同步关闭和原子替换。
6. 删除先销毁 wrapped key，再删除加密文件。
7. 全部文件位于 `noBackupFilesDir`，备份规则继续排除应用数据。

Keystore 丢失或 App 重装后，旧凭据无法解密。用户需要重新添加服务器，并在 VPS 控制台移除失效公钥。

## 11. 核心本地数据模型

### 11.1 Computer

```text
computer_id
display_name
host
port
username
resolved_address
host_key_algorithm
host_key_blob
host_key_fingerprint
auth_kind
credential_state
run_mode
status
capabilities
os_release
architecture
bootstrap_version
sandbox_image
allow_private_network
last_connected_at
last_error_code
created_at
updated_at
```

### 11.2 Workspace

```text
workspace_id
computer_id
conversation_id
run_mode
host_path
container_name
container_image
status
created_at
last_used_at
```

约束：

1. `(computer_id, conversation_id)` 唯一。
2. 用户不手动创建、命名、共享或选择 Workspace。
3. Workspace 不包含 Model、Provider、温度和系统提示词字段。
4. 切换服务器只切换当前映射，旧 Workspace 继续保留。

### 11.3 Execution

本地 Execution 摘要包含 `execution_id`、`tool_call_id`、Computer、Workspace、Tool、request hash、状态、时间、exit code、错误码和安全摘要。完整 stdout、stderr、命令环境变量、文件内容和 Secret 值不写入 Room。

### 11.4 Preview

Preview 保存 Workspace、远端端口、本地端口、公开端口、协议、可见性、状态和有效期。本地端口只在当前连接存活期间有效。

## 12. 状态机

### 12.1 Computer

```text
DRAFT
  → RESOLVING_HOST
  → HOST_KEY_PENDING
  → AUTHENTICATING
  → PROBING
  → CONFIGURATION_REQUIRED
  → PROVISIONING
  → VERIFYING
  → READY
```

异常和用户状态：`OFFLINE`、`HOST_KEY_CHANGED`、`ACTION_REQUIRED`、`ERROR`、`DISCONNECTED`、`DELETING`、`DELETED`。

### 12.2 Workspace

```text
CREATING → READY
READY → STOPPED | RECOVERING | ERROR | DELETING
DELETING → DELETED
```

Direct SSH Workspace 不使用 `STOPPED`。

### 12.3 Execution

```text
QUEUED → STARTING → RUNNING
                        ├── SUCCEEDED
                        ├── FAILED
                        ├── TIMED_OUT
                        ├── CANCELLED
                        └── UNKNOWN
```

远端命令已开始且手机在取得结果前断线时进入 `UNKNOWN`。App 禁止自动重复该命令。

## 13. Probe 与运行模式

### 13.1 Probe

App 通过只读 SSH 命令探测 `/etc/os-release`、内核、架构、用户、Shell、CPU、内存、磁盘、负载、Docker、sudo、端口转发、SFTP 和 PTY。低内存、低磁盘和高负载只生成提示。

### 13.2 Direct SSH

1. Agent 拥有 SSH 登录账号的实际权限。
2. `/workspace` 映射到 `~/.everytalk/workspaces/<workspace_id>`。
3. 结构化文件 Tool 只能访问当前 Workspace。
4. `exec` 具备登录账号本身的权限。
5. 首次启用时显示“Agent 使用 SSH 用户本身权限执行操作”。
6. SSH 用户为 root 时，额外显示高风险确认。

### 13.3 Container 隔离环境

Ubuntu 与 Debian 使用 Docker 官方 APT Repository 安装 Docker。禁止执行 `curl ... | sh`。

App 通过一次性 sudo 会话安装 root-owned helper：

```text
/usr/local/libexec/everytalk-containerctl
```

helper v3 只允许 Probe、网络、镜像、Workspace Container、容器执行、后台任务停止、地址解析与 Public Preview 固定子命令。每个子命令强制准确参数数量，ID、Label、规范路径、端口、协议和镜像全部严格解析。所有 Container 操作先校验 EveryTalk Label 与 Workspace 归属，禁止接收任意 Docker 参数。安装完成后的 helper 拒绝再次执行安装入口。sudoers 只允许执行这个 root-owned 且普通用户不可写的 helper。helper 不监听端口，不保留常驻控制进程。

### 13.4 Container 安全边界

1. 禁止 privileged、Host Network、Host PID、Host Root Mount 和 Docker Socket。
2. 禁止 `seccomp=unconfined`。
3. 启用 `no-new-privileges` 与 Docker 默认 seccomp。
4. 只读挂载控制元数据，读写挂载当前 Workspace。
5. 不传 `--cpus`、`--memory`、`--memory-swap`、`--pids-limit`，不创建磁盘 quota。
6. Workspace Container 与 Public Preview Container 都使用 `--restart no`。
7. Android 只在当前会话实际需要时启动对应 Container，禁止 VPS 重启后自动拉起全部历史会话。

### 13.5 网络边界

Container 使用专用 Docker Bridge。默认允许公网，阻止私网、link-local、云元数据与 IPv6 ULA。用户可以在详情页显式允许访问 VPS 私有网络。Direct SSH 沿用登录账号的网络权限。

## 14. Workspace

每个 Workspace 使用：

```text
~/.everytalk/workspaces/<workspace_id>
```

目录包含工作文件以及 `.everytalk/workspace.json`、`runtime/`、`background/` 和 `previews/`。Container 内统一映射为 `/workspace`。

每个后台任务使用 `.everytalk/background/<process_id>/`，其中 `stdout.log` 和 `stderr.log` 保存输出，`state` 原子保存 `execution_id`、`process_id`、PID、Linux 进程起始标记、`RUNNING | SUCCEEDED | FAILED | CANCELLED | STOPPED`、Exit Code 和更新时间。状态文件不保存命令、环境变量、Secret 或完整输出。

用户第一次在某台服务器上开启 Agent 时执行幂等 get-or-create。切回曾经使用的服务器时恢复原目录和 Container。关闭 Agent、切换模型、切换服务器和删除聊天都不会自动删除 Workspace。

## 15. 七个 Agent Tool

### 15.1 公共约束

模型只看到 `exec`、`read_file`、`write_file`、`terminal`、`upload`、`download` 和 `open_port`。模型参数中不包含 Computer 身份。Android 根据本轮会话快照注入 Computer、Workspace、Conversation 和本地 Tool Call ID。

统一结果：

```ts
type ComputerToolResult<T> = {
  ok: boolean
  execution_id: string
  data?: T
  error?: {
    code: string
    message: string
    retryable: boolean
    action?: string
  }
}
```

### 15.2 `exec`

```ts
exec({
  command: string
  cwd?: string
  env?: Record<string, string>
  secret_names?: string[]
  stdin?: string
  timeout_ms?: number
  background?: boolean
  as_root?: boolean
})
```

前台返回 exit code、stdout、stderr、timeout 与截断标记。后台返回 process ID、PID 和日志路径。

实现要求：

1. `cwd` 默认 `/workspace`。
2. Android 通过 SFTP 写入 0600 临时执行信封和脚本。
3. SSH 命令行只出现受控固定路径和经过验证的 ID。
4. 目标 Shell 从临时文件读取 command、cwd、env 和 stdin。
5. Secret 不进入命令参数和进程标题。
6. 前台超时终止远端进程组。
7. 无法确认终止时返回 `EXECUTION_UNKNOWN`。
8. 大输出只返回首尾片段并标记截断。
9. 后台日志写入 `.everytalk/background/<process_id>/`。
10. `as_root` 只在 Container 模式有效。
11. 后台 Runtime 作为独立 Session 运行，完成、失败或取消后原子更新远端状态并清理 Runtime 信封。
12. 删除 Direct Workspace 时只有 PID、进程起始标记、Session ID 和状态目录参数全部匹配才发送终止信号。

### 15.3 `read_file`

```ts
read_file({
  path: string
  offset?: number
  limit?: number
  encoding?: "utf8" | "base64"
})
```

通过 SFTP 分页读取。路径限制在 `/workspace`，必须防止 `..`、绝对路径绕过、符号链接逃逸和检查后替换竞态。

### 15.4 `write_file`

```ts
write_file({
  path: string
  content: string
  encoding?: "utf8" | "base64"
  mode?: "overwrite" | "append"
  create_parents?: boolean
})
```

覆盖写入使用同目录临时文件、完整写入、大小校验和原子 rename。append 使用 `tool_call_id` 本地幂等记录，禁止一次 Tool Call 重复追加。

### 15.5 `terminal`

```ts
terminal({
  action: "open" | "write" | "read" | "resize" | "close"
  terminal_id?: string
  input?: string
  cursor?: number
  cols?: number
  rows?: number
})
```

PTY Ring Buffer 只存在于当前 Android 进程。App 被终止或 SSH 断开后返回 `TERMINAL_LOST`。

### 15.6 `upload`

```ts
upload({
  attachment_id: string
  destination_path: string
  overwrite?: boolean
})
```

Android 从 ContentResolver 或 FileManager 流式读取，经 SFTP 写入临时文件，完成大小和 SHA-256 校验后原子 rename。禁止完整加载大文件。

### 15.7 `download`

```ts
download({
  source_path: string
  suggested_name?: string
})
```

SFTP 流式写入 Android FileManager。模型只收到文件名、MIME、大小和本地附件 ID。

### 15.8 `open_port`

```ts
open_port({
  port: number
  protocol?: "http" | "https"
  visibility?: "private" | "public"
  expires_in_seconds?: number
})
```

Private Preview：

1. Direct 模式转发到 VPS `127.0.0.1:<port>`。
2. Container 模式先解析 Container Address。
3. Android 在 `127.0.0.1` 选择随机本地端口。
4. SSH 本地端口转发承载 HTTP、HTTPS 和 WebSocket。
5. URL 只在当前手机和当前连接存活期间有效。

Public Preview：

1. Tool Call 暂停并弹出用户确认。
2. Container 模式通过受限 helper 创建带 Label 的端口转发资源。
3. Direct 模式要求用户程序监听 VPS 公网地址，App 只确认并检查端口。
4. 返回 `http(s)://<VPS Host>:<public_port>`。
5. 云厂商安全组或 VPS 防火墙仍可能阻止访问，UI 必须准确显示该限制。
6. HTTPS 证书由用户服务或用户域名负责。
7. 用户可以从服务器详情页撤销公开端口。

## 16. Workspace Secret

用户可以按 Workspace 保存环境变量。值由 Android Keystore 本地加密。

1. UI 创建后只显示名称、更新时间和作用域。
2. 模型只知道可用 Secret 名称。
3. `exec.secret_names` 明确声明本次使用项。
4. Android 解密后通过 SFTP 写入远端 0600 临时环境文件。
5. Wrapper 在启动目标进程前读取并立即删除文件。
6. 成功、失败、超时和取消都执行清理。
7. terminal 默认不注入 Secret。
8. SSH Credential、sudo 密码和 Keystore 主密钥禁止作为 Workspace Secret。
9. Secret 值禁止进入日志、Execution 摘要、错误响应和模型结果。

## 17. Tool 注入与现有 Agent Loop

Computer Tool 只有同时满足以下条件时注入：

1. 当前会话 `agent_enabled = true`。
2. 当前会话已选择一台服务器。
3. Computer 与 Workspace 都处于 `READY`。
4. 当前模型和 Provider 路径支持 Tool Call。

开启 Agent 时，App 先执行 Workspace get-or-create。缺少服务器、服务器不可用或模型不支持 Tool Call 时阻止开启。Agent 已开启后服务器掉线，发送请求直接失败并显示错误，禁止静默移除 Computer Tool。

Tool 注入顺序：

```text
Provider 原生工具
  → 自定义工具
  → MCP 候选工具
  → Computer 内建工具
  → PromptCachePolicy.normalizeTools()
```

`executeSharedToolCall()` 路由顺序：

```text
Prompt Capability
Web Fetch
Current Time
Read Attachment
Web Search
Computer Tool
MCP fallback
```

四类 Provider 继续复用同一个回调。Computer Tool Executor 使用当前请求启动时保存的会话、Computer 和 Workspace 快照，避免用户在生成过程中切换聊天导致路由漂移。

Agent 环境提示保持紧凑，只包含 `/workspace`、运行模式、系统架构和七个 Tool 名称。禁止加入 Host、端口、用户名、真实 Host Path、Host Key 和 Secret。

## 18. Android 产品交互

### 18.1 设置入口与服务器页面

设置页三点菜单包含平台配置、联网搜索、MCP、服务器、导入与导出。服务器页面左上角加号打开添加服务器悬浮卡片，页面支持多张服务器卡片。

1. 复用现有配置页面的悬浮卡片、遮罩、表单、取消与保存交互。
2. 点击卡片进入详情。
3. 服务器页面不处理模型绑定。
4. 页面不提供手动 Workspace 创建或选择。
5. 空页面只保留简短说明与左上角加号。

### 18.2 添加服务器悬浮卡片

字段包含名称、服务器地址、SSH 端口、用户名、登录方式、密码或私钥、可选私钥口令与运行模式。

流程：本地校验、读取 Host Key、确认指纹、登录、升级专用 Key、Probe、配置运行模式、验收。添加服务器阶段不创建 Workspace。

首次启用 Agent 时显示通用数据流提示：必要的命令、执行结果和文件片段可能发送给当前 AI 模型服务商。Direct SSH 模式同时显示权限提示；root 登录账号额外显示高风险确认。确认记录只保存在 Android 本地。

### 18.3 Agent 短按

1. 已开启时始终关闭，即使服务器掉线。
2. 未选择服务器时打开单选悬浮卡片，选服并创建 Workspace 成功后开启。
3. 当前服务器可用时创建或恢复 Workspace并开启。
4. 当前服务器不可用时显示错误。
5. 模型不支持 Tool Call 时显示明确错误。

### 18.4 Agent 长按

1. 长按 Agent 选项或 Agent 标签打开服务器单选卡片。
2. 卡片列出全部服务器，只有 `READY` 服务器可选。
3. Agent 关闭时只更新当前服务器，延迟创建 Workspace。
4. Agent 开启时先准备目标 Workspace，成功后再切换。
5. 失败时保留原服务器和 Workspace。
6. 长按不改变 Agent 开关。

### 18.5 紧凑标签

Agent 开启后只显示 `[终端图标 Agent ×]`。标签禁止显示服务器名称、状态、Host、Workspace 和运行模式。联网搜索、MCP 与 Agent 同时开启时都使用短标签。

### 18.6 服务器详情

详情页包含状态、最后连接、系统资源、运行模式、权限说明、Host Key、Credential 状态、自动 Workspace、Secret、Preview、重连、探测、修复、停用、清理、删除和本地审计。

## 19. 消息持久化

1. `ConversationFunctionToggleState` 增加 `agentEnabled`。
2. `MessageToolIds` 增加 `AGENT`。
3. 每条启用 Agent 的用户消息保存实际 `computer_id` 与 `workspace_id` 快照。
4. 快照只用于历史展示和本地审计，不锁定后续会话选择。
5. Execution Step 保存 Tool 名称、简短目标、完成状态和 `execution_id`。
6. 重答使用重答时的当前服务器与 Agent 开关。
7. Tool 原始输出只留在本轮 Provider Tool Context，历史保存安全摘要。

## 20. 并发、幂等与取消

1. Workspace 生命周期和同一路径写入使用每 Workspace Mutex。
2. 多个前台 `exec` 可以并发，不限制远端进程数量。
3. `tool_call_id` 在当前请求生命周期内唯一。
4. 有副作用 Tool 在 Room 保存 request hash 与结果状态。
5. 相同 ID 与相同 hash 返回首次结果摘要。
6. 相同 ID 与不同 hash 返回 `IDEMPOTENCY_CONFLICT`。
7. 用户停止回答时取消尚未开始的 Tool。
8. 前台命令尝试终止远端进程组。
9. 用户主动启动的后台命令继续运行。
10. 无法确认远端状态时标记 `UNKNOWN`。

## 21. 恢复

### 21.1 手机网络切换

1. 关闭失效 SSH Transport。
2. 重新解析 Host。
3. 验证固定 Host Key。
4. 使用本地加密凭据重连。
5. 重新 Probe 当前 Workspace。
6. 已开始且结果未知的命令禁止自动重放。

### 21.2 App 重启

1. Room 恢复服务器、会话选择和 Workspace 映射。
2. Keystore 恢复凭据解密能力。
3. `RUNNING` 且无法从远端状态文件确认的 Execution 变为 `UNKNOWN`。
4. PTY 变为 `TERMINAL_LOST`。
5. Private Preview 变为 `STOPPED`，用户打开时重建转发。
6. Container 和远端后台进程按实际状态恢复。

### 21.3 VPS 重启

1. Computer 进入 `OFFLINE`。
2. 用户触发或 App 恢复时重连。
3. 重新验证 Host Key。
4. Probe Docker、Container 和 Workspace。
5. 不自动启动任何历史 Container。
6. 当前会话启用 Agent 或发送请求时，按需启动对应 Container；Container 缺失时使用原 Host Path 重建。
7. 后台进程根据状态文件和 PID 标记实际结果。

## 22. 生命周期操作

### 22.1 Disconnect

停止接受新 Tool Call，关闭 SSH、SFTP、PTY 和本地端口转发，保留本地加密 Credential、远端 Workspace 与 Container，并把状态设为 `DISCONNECTED`。

### 22.2 Delete Computer

默认顺序：

1. 禁止新 Tool Call。
2. 关闭连接和 Preview。
3. 尝试从 `authorized_keys` 移除专用公钥。
4. 销毁本地 wrapped key 与加密 Credential 文件。
5. 清理 Room 中该服务器的当前选择和映射。
6. 删除本地服务器元数据。

默认保留 VPS Workspace 和 Container。删除确认页可以单独选择清理 Container 与删除 Workspace 文件，两个选项都显示准确路径并二次确认。远端不可达时允许只删除本地记录，并提示残留公钥位置。

### 22.3 Delete Workspace

1. 停止新 Tool Call。
2. 关闭 Preview 与 Terminal。
3. 停止 Container 和已记录后台进程。
4. 默认删除 Container并保留 Host Path。
5. 删除 Host Path 需要单独勾选和二次确认。
6. 清理对应 Workspace 映射和本地 Secret。
7. 下次开启 Agent 时自动创建空 Workspace。

## 23. 错误码

| 错误码 | 含义 | 用户动作 |
| --- | --- | --- |
| `HOST_RESOLUTION_FAILED` | 域名解析失败 | 检查 Host 和当前网络 |
| `SSH_TIMEOUT` | SSH 连接超时 | 检查 VPS、端口和防火墙 |
| `HOST_KEY_CHANGED` | 服务器身份变化 | 核对并确认新指纹 |
| `AUTH_FAILED` | SSH 认证失败 | 更新登录信息 |
| `PRIVATE_KEY_INVALID` | 私钥格式或口令错误 | 重新输入 |
| `KEYSTORE_UNAVAILABLE` | 本地安全密钥不可用 | 解锁设备或重新添加服务器 |
| `SUDO_REQUIRED` | 自动配置需要 sudo | 输入一次性 sudo 密码或选择 Direct |
| `UNSUPPORTED_OS` | 不支持自动安装 Docker | 使用 Direct SSH |
| `DOCKER_INSTALL_FAILED` | Docker 安装失败 | 查看失败步骤并重试 |
| `HELPER_INTEGRITY_FAILED` | Helper 校验失败 | 重新配置环境 |
| `SERVER_NOT_SELECTED` | 当前会话未选择服务器 | 选择一台服务器 |
| `COMPUTER_NOT_READY` | 当前服务器不可用 | 长按 Agent 改选或修复 |
| `MODEL_TOOL_CALL_UNSUPPORTED` | 模型不支持 Tool Call | 更换模型或关闭 Agent |
| `WORKSPACE_PATH_INVALID` | 文件路径越界 | 使用 `/workspace` 内路径 |
| `WORKSPACE_NOT_READY` | Workspace 未就绪 | 等待或修复 |
| `EXECUTION_UNKNOWN` | 命令结果无法确认 | 检查进程和文件状态 |
| `TERMINAL_LOST` | PTY 已丢失 | 新开 Terminal |
| `UPLOAD_INTERRUPTED` | 上传中断 | 重试 |
| `DOWNLOAD_INTERRUPTED` | 下载中断 | 重试 |
| `PREVIEW_FORWARD_LOST` | 本地 SSH 转发失效 | 重建预览 |
| `PUBLIC_PORT_BLOCKED` | 公网端口无法访问 | 检查安全组和防火墙 |
| `VPS_DISK_FULL` | 远端磁盘写入失败 | 清理 VPS 文件 |
| `VPS_PROCESS_OOM` | 进程疑似被 OOM Killer 终止 | 查看资源状态 |

## 24. Android 文件改动

### 24.1 新增文件

| 路径 | 职责 |
| --- | --- |
| `data/computer/ComputerModels.kt` | Computer、Workspace、Preview、Execution 与错误模型 |
| `data/computer/ComputerSshClient.kt` | SSH 认证、Host Key、exec、SFTP、PTY 和转发封装 |
| `data/computer/ComputerConnectionPool.kt` | 多服务器连接复用、Keepalive 与重连 |
| `data/computer/ComputerCredentialStore.kt` | Android Keystore 信封加密与原子文件存储 |
| `data/computer/ComputerRepository.kt` | Room、本地凭据和 SSH 状态的单一入口 |
| `data/computer/ComputerProbe.kt` | 系统、Docker、sudo、资源与能力探测 |
| `data/computer/ComputerProvisioner.kt` | Docker、Helper、网络和 Container 隔离配置 |
| `data/computer/ComputerWorkspaceManager.kt` | 自动 Workspace 与 Container 生命周期 |
| `data/computer/ComputerToolCatalog.kt` | 七个稳定 Tool Schema |
| `data/computer/ComputerToolExecutor.kt` | 参数校验、会话快照和七个 Tool 路由 |
| `data/computer/ComputerFileTransfer.kt` | ContentResolver、FileManager 与 SFTP 流式桥接 |
| `data/computer/ComputerTerminalManager.kt` | PTY 与进程内 Ring Buffer |
| `data/computer/ComputerPreviewManager.kt` | 本地 SSH 转发与 Public Port 生命周期 |
| `data/computer/ComputerRuntimeEnvelope.kt` | 安全远端执行信封与清理 |
| `data/database/entities/ComputerEntities.kt` | Computer、Workspace、选择、Execution、Preview 与审计 Entity |
| `data/database/daos/ComputerDao.kt` | 本地 Computer 数据 DAO |
| `statecontroller/viewmodel/ComputerManager.kt` | UI 状态、添加流程、会话选服和 Agent 开关 |
| `service/ComputerConnectionService.kt` | 活动 SSH 连接前台服务 |
| `ui/screens/computer/ComputerScreen.kt` | 独立服务器页面和卡片 |
| `ui/screens/computer/AddComputerFlow.kt` | 添加服务器、指纹确认和配置进度悬浮卡片 |
| `ui/screens/computer/ComputerDetailScreen.kt` | 状态、操作、Workspace 和审计详情 |
| `ui/screens/computer/ComputerSelectionPopup.kt` | 会话服务器单选悬浮卡片 |
| `ui/screens/computer/ComputerWorkspaceUi.kt` | Workspace、Secret 与清理 UI |
| `ui/screens/computer/ComputerPreviewUi.kt` | Preview 列表、确认和打开逻辑 |

VPS 资产放入 Android `assets/computer/`：

```text
everytalk-containerctl.sh
install-docker.sh
runtime-wrapper.sh
```

### 24.2 修改文件

| 路径 | 修改内容 |
| --- | --- |
| `navigation/Screen.kt` | 增加服务器列表与详情路由 |
| `AppDatabase.kt` | 增加 Entity、DAO 和 12 到 13 迁移 |
| `app/schemas/.../13.json` | 提交 Room Schema |
| `Message.kt` | 增加 Agent Tool ID、Computer/Workspace 快照和执行语义 |
| `ChatEntities.kt`、`ChatDao.kt` | 持久化新消息字段 |
| `ViewModelStateModels.kt` | 增加 `agentEnabled` |
| `ViewModelStateHolder.kt` | 增加当前会话 Agent 内存状态 |
| `AppViewModel.kt` | 持有 ComputerManager 并注册统一执行器 |
| `AppViewModelSupport.kt` | 路由七个 Computer Tool |
| `MessageSender.kt` | 接入 Tool Catalog 与冲突检查 |
| `MessageSenderSendFlow.kt` | 注入 Tool、环境提示与消息快照 |
| `ApiHandler.kt` | 绑定和释放本轮 Computer 执行上下文 |
| `ApiHandlerStreamProcessor.kt` | Computer Tool 执行步骤 |
| `ThinkingExecutionTimeline.kt` | 本地化 Computer 时间线 |
| `SettingsScreen.kt` | 三点菜单增加“服务器”入口 |
| `ChatInputArea.kt`、`ChatInputPanels.kt` | Agent 短按、长按、选服卡片和紧凑标签 |
| `AndroidManifest.xml` | Foreground Service、通知与网络权限声明 |
| `strings.xml`、`values-zh/strings.xml` | 中英文文案与 TalkBack |
| `libs.versions.toml`、`app/build.gradle.kts` | 增加经验证的 SSH 库和必要加密依赖 |

Room 禁止保存 SSH 密码、私钥、私钥口令、sudo 密码和 Workspace Secret 值。

## 25. 本地审计与日志

记录服务器生命周期、Host Key 确认、Credential 升级、Bootstrap 步骤、Workspace 生命周期、Tool 类型与状态、Preview 创建撤销和 Secret 名称级操作。

禁止记录凭据、Secret 值、完整命令、完整输出、文件内容、密钥材料、完整 Host 和 Username。执行摘要只保留 Tool 类型、短目标、耗时、状态与 Exit Code。

## 26. 测试计划

### 26.1 单元测试

1. Host 输入校验与 IPv4、IPv6、域名解析。
2. Host Key 指纹、固定、变化和重新确认。
3. Keystore 信封格式、AAD 绑定、认证失败和 Crypto Shredding。
4. Computer、Workspace 与 Execution 状态机。
5. Tool Schema 和名称冲突。
6. Workspace 路径穿越与符号链接逃逸。
7. Runtime Envelope 参数编码与 Secret 清理。
8. 输出截断与敏感信息脱敏。
9. `tool_call_id` 幂等与 hash 冲突。
10. 会话选服、Agent 开关与 Workspace 恢复。
11. 后台 Runtime 的成功、取消、状态落盘、PID 起始标记和信封清理。
12. 首个 Channel 建立失败可安全重连，已启动 Channel 禁止重放。
13. 审计事件、结果和安全摘要的中英文映射。

### 26.2 Android SSH 集成测试

使用可销毁 sshd 测试环境覆盖：

1. Password 与 keyboard-interactive。
2. RSA、ECDSA、Ed25519 与加密私钥。
3. Host Key 替换。
4. exec、SFTP、PTY、本地转发和 WebSocket。
5. 专用 Key 安装、验证、回滚和删除。
6. UTF-8 中文路径、文件名、stdout 和终端输入。
7. SSH 中途断开、sshd 重启和 Keepalive 失效。

### 26.3 Container 集成测试

1. Ubuntu 与 Debian 安装脚本幂等。
2. amd64 与 arm64 Container 隔离环境启动。
3. Workspace UID/GID 与文件所有权。
4. 无 privileged、Docker Socket、Host Mount、Host PID 和 Host Network。
5. `docker inspect` 确认无 CPU、内存、PID 和磁盘配额。
6. 公网可访问，私网与元数据默认不可访问。
7. Helper 无法操作无 EveryTalk Label 的资源。
8. Container 重建后 Workspace 文件保留。
9. VPS 重启后历史 Container 保持停止，当前会话使用时按需恢复。
10. Public Preview 创建和撤销。

### 26.4 Tool 合约测试

七个 Tool 都覆盖正常、边缘、失败、取消、断线和中文数据。上传、下载和文件读写额外覆盖大文件流式处理、空间不足、原子写入、Hash 不符与文件变化。

### 26.5 Compose 与状态测试

1. 设置三点菜单进入服务器页面。
2. 左上角加号和多服务器卡片。
3. Host Key 确认与 sudo 输入生命周期。
4. Agent 短按和长按。
5. 不可用服务器无法选中并显示错误。
6. 三个紧凑标签同时显示。
7. 关闭 Agent 保留服务器和 Workspace。
8. 切换模型不改变 Agent 状态。
9. 切换服务器失败时保留原映射。
10. TalkBack 与中英文文案。

### 26.6 真实端到端验收

1. 在真实 Android 设备添加 Ubuntu 与 Debian VPS。
2. 两台服务器都能独立管理。
3. Container 与 Direct 各创建一个会话 Workspace。
4. 切服、关闭、重开和切模型后文件关系正确。
5. 上传 CSV、运行分析、下载结果。
6. 创建 Web 项目并打开 App 内 private preview。
7. 用户确认后创建 public preview，另一设备访问并撤销。
8. 手机切网、App 重启和 VPS 重启后恢复。
9. Host Key 替换后完全阻断。
10. 删除 Computer 时本地凭据不可恢复，远端数据按用户选项保留或清理。
11. 抓包确认 Computer 流量只在 Android 与用户 VPS 之间传输。

## 27. CI 与制品

1. Android CI 增加 Computer 定向单元测试。
2. 使用容器化 sshd 执行 JVM 集成测试。
3. 使用 Ubuntu 与 Debian 矩阵验证 Bootstrap。
4. 构建并扫描 amd64、arm64 Container Image。
5. Android 编译执行 `:app:testDebugUnitTest`、`:app:compileDebugKotlin` 和 `:app:assembleDebug`。
6. CI 禁止要求 Computer 后端环境变量。
7. 发布包内的 Bootstrap 脚本、Helper 与镜像引用必须带版本和 SHA-256。
8. CI 对 `runtime-wrapper.sh` 与 `everytalk-containerctl.sh` 执行 `bash -n`，并运行后台 Runtime Shell 自检。

## 28. 实施里程碑

### 里程碑 0：本地技术验证

1. 锁定七个 Tool Schema、错误外壳和状态枚举。
2. 真机验证 SSH 库的认证、Host Key、SFTP、PTY 与端口转发。
3. 验证 Android Keystore 信封加密与原子文件写入。
4. 验证 Foreground Service 保持活动连接。
5. 验证 Container Address 的 private preview。

完成条件：所有关键路径都有可运行自检。

### 里程碑 1：本地数据、安全与 SSH

1. Room 13 Schema 与迁移。
2. Credential Store。
3. Host Key 首次确认、固定和变化阻断。
4. 四类认证输入。
5. 专用 Key 升级与回滚。
6. Connection Pool 与 Foreground Service。

完成条件：凭据不离开手机，重连始终验证固定 Host Key。

### 里程碑 2：Probe 与两种运行模式

1. 完整 Probe 与 Direct Adapter。
2. Docker 安装脚本、Container Helper 与 sudoers。
3. 专用 Bridge、网络规则与 Container Image。
4. 自动 Workspace 生命周期。
5. 无资源配额断言。

完成条件：支持矩阵内 VPS 可以从裸系统配置并持久运行 Workspace。

### 里程碑 3：七个 Tool 与 Agent Loop

1. exec、read_file、write_file、terminal、upload、download、private open_port。
2. Execution、幂等、取消和 UNKNOWN。
3. 四 Provider 统一 Tool 路由。
4. 会话 Agent 开关、选服、消息快照和执行时间线。

完成条件：模型可以在当前会话 Workspace 完成写代码、执行、上传和下载闭环。

### 里程碑 4：完整 UI、Preview 与 Secret

1. 服务器列表、添加、详情、修复和删除 UI。
2. Agent 短按、长按与紧凑标签。
3. Workspace 与 Secret UI。
4. Private Preview 恢复。
5. Public Preview 用户确认、直连暴露和撤销。
6. 中英文、TalkBack 和错误恢复。

完成条件：用户只用手机完成全部产品路径。

### 里程碑 5：恢复、兼容与发布

1. 手机切网、App 重启和 VPS 重启对账。
2. Disconnect、Delete Computer 和 Delete Workspace。
3. 本地审计与敏感信息检查。
4. Ubuntu、Debian、amd64、arm64 全矩阵。
5. CI、Container 制品和 Android Release 验收。

完成条件：功能、失败路径、清理路径、测试与制品全部交付。

## 29. 完成定义

只有同时满足以下条件，功能才算完成：

1. Computer 功能不依赖 EveryTalk 后端服务。
2. 抓包确认 SSH 凭据、命令、文件和 Preview 不经过项目方服务器。
3. 支持矩阵内系统与架构通过自动配置和真实机验收。
4. Password、keyboard-interactive、私钥和私钥口令全部通过。
5. 首次 Host Key 在认证前确认，变化会阻断所有 Tool。
6. 原始凭据可以升级为每台服务器专用 Key。
7. Credential 和 Workspace Secret 只在本地 Keystore 加密存储。
8. Direct 与 Container 权限语义和 UI 一致。
9. Container 不持有 Docker Socket、Host Root、Host PID 或 Host Network。
10. Container 没有 CPU、内存、磁盘和 PID 配额。
11. Workspace 与 Public Preview Container 均为 `restart=no`，VPS 重启后只按需恢复当前会话。
12. 七个 Tool 具备正常、边缘、失败、取消和断线测试。
13. 四类 Provider 共用同一个 ComputerToolExecutor。
14. 上传、下载与 private preview 全程流式处理。
15. App 被终止后不会伪造 PTY 和未知命令恢复。
16. VPS 和 App 重启后 Workspace 可恢复。
17. Public Preview 只在用户明确确认后直接暴露 VPS 端口。
18. 会话可以随时切换服务器。
19. 关闭 Agent 不清除服务器与 Workspace。
20. 切换模型不改变 Agent 能力关系。
21. Agent 标签紧凑且不显示服务器信息。
22. 不可用服务器只在选择卡片中阻止并报错。
23. 所有模式显示模型数据流提示，Direct SSH 与 root 权限风险分层确认。
24. 中英文、TalkBack、Room Migration 与备份排除通过。
25. Android CI、Container Image 和发布制品完整。
26. 文档中的状态、目录、错误码与实际实现一致。

## 30. 权威参考

1. Android Keystore：<https://developer.android.com/privacy-and-security/keystore>
2. Android Foreground Services：<https://developer.android.com/develop/background-work/services/fgs>
3. OpenSSH：<https://man.openbsd.org/ssh>
4. OpenSSH Host Key：<https://man.openbsd.org/ssh_config>
5. OpenSSH SFTP：<https://man.openbsd.org/sftp>
6. SSHJ：<https://github.com/hierynomus/sshj>
7. Apache MINA sshd：<https://mina.apache.org/sshd-project/>
8. Docker Engine 安装：<https://docs.docker.com/engine/install/>
9. Docker 默认 Seccomp：<https://docs.docker.com/engine/security/seccomp/>
10. Docker Packet Filtering：<https://docs.docker.com/engine/network/firewall-iptables/>
11. Docker Group 权限说明：<https://docs.docker.com/engine/install/linux-postinstall/>
12. OWASP Secrets Management Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html>
