# 用户自有 VPS 作为 Agent Sandbox

## 技术设计文档 v0.1

**状态：Draft / 待评审**
**日期：2026-08-11**

---

# 1. 背景

我们的目标不是自建一套类似 E2B、Cloudflare Sandbox 的计算资源，也不是在用户 VPS 上部署另一个完整 Agent、Runner 或 Hermes。

目标是允许用户将**自己已有的 VPS 直接提供给 App 中的 Agent 使用**。

用户在手机 App 中填写 VPS 登录信息，例如：

```text
Host: 1.2.3.4
Port: 22
Username: root

Authentication:
- Password
- SSH Private Key
```

点击：

```text
连接
```

之后所有 VPS 检测、初始化、环境配置、Sandbox 创建等操作均由平台自动完成。

用户：

* 不需要打开电脑；
* 不需要打开终端；
* 不需要复制 shell 命令；
* 不需要手动安装我们自己的 Agent；
* 不需要学习 Docker；
* 不需要理解 SSH；
* 不需要开放额外管理端口。

最终用户只需要看到：

```text
我的计算机

Tokyo VPS
Ubuntu 24.04
4 CPU / 8 GB RAM

状态：● Agent Ready

[使用此 VPS]
```

Agent 随后可以像使用本地 Computer / Sandbox 一样使用这台 VPS。

---

# 2. 产品目标

核心产品体验：

```text
用户购买 VPS
       ↓
打开我们的 App
       ↓
填写 SSH 登录信息
       ↓
点击「连接」
       ↓
平台自动检测和配置
       ↓
VPS 变成 Agent Sandbox
       ↓
用户开始和 Agent 对话
```

对于普通用户而言，整个过程应该接近：

> **“把服务器账号交给 Agent，然后 Agent 就拥有了一台电脑。”**

用户不应该被迫理解底层实现。

---

# 3. 非目标

本方案明确不做以下事情。

### 3.1 不在 VPS 上运行第二套 Agent

LLM、Agent Loop、Memory、Planning、Tool Calling 等仍然全部运行在我们的平台。

VPS 只负责：

```text
执行代码
文件系统
终端
进程
网络服务
开发环境
计算资源
```

架构不是：

```text
Our App
   ↓
VPS Agent
   ↓
另一个 Agent Runtime
```

而是：

```text
Our Agent
   ↓
Computer / Sandbox API
   ↓
SSH
   ↓
User VPS
```

---

### 3.2 不要求用户安装我们的 daemon

VPS 不要求长期运行：

```text
our-agent
our-runner
our-daemon
our-service
```

因此本文中的 **Agentless** 特指：

> VPS 不需要长期运行我们自研的控制程序。

这不代表 VPS 完全不会发生任何修改。

例如平台可能自动：

* 创建 workspace；
* 添加 SSH 公钥；
* 安装 Docker；
* 创建 Docker container。

这些属于自动 provisioning / bootstrap，而不是部署一个新的 Agent runtime。

---

# 4. 总体架构

建议整体架构：

```text
┌──────────────────────────────┐
│         Mobile App           │
│                              │
│   Chat / Files / Preview     │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│       Agent Platform         │
│                              │
│  LLM                         │
│   ↓                          │
│  Agent Loop                  │
│   ↓                          │
│  Computer Tool Layer         │
│                              │
│  exec                        │
│  read_file                   │
│  write_file                  │
│  terminal                    │
│  upload                      │
│  download                    │
│  open_port                   │
└──────────────┬───────────────┘
               │
            SSH/SFTP
               │
               ▼
┌──────────────────────────────┐
│        User VPS              │
│                              │
│  ┌────────────────────────┐  │
│  │ Sandbox Container      │  │
│  │                        │  │
│  │ /workspace             │  │
│  │ node / python / git    │  │
│  │ user's code            │  │
│  └────────────────────────┘  │
│                              │
│       Docker Engine          │
│                              │
└──────────────────────────────┘
```

OpenSSH 原生支持登录远程主机、执行远程命令以及 TCP forwarding，因此 SSH 可以直接承担整个远程控制通道。

文件传输则使用 SFTP。SFTP 本身运行在 SSH 加密传输之上，并支持上传、下载、目录操作等文件能力。

因此第一版不需要额外定义自己的网络协议。

---

# 5. 核心设计原则

## 5.1 Agent 不知道底层是 SSH

Agent 不应该直接拿到：

```text
host
username
password
private_key
ssh_port
```

Agent 只应该知道：

```text
computer_id
workspace_id
```

以及七个 Computer Tools：

```text
exec
read_file
write_file
terminal
upload
download
open_port
```

例如模型只看到：

```json
{
  "computer_id": "computer_123",
  "command": "npm test",
  "cwd": "/workspace"
}
```

真正执行的时候：

```text
Tool Layer
   ↓
Computer Manager
   ↓
SSH Adapter
   ↓
Docker Adapter
   ↓
VPS
```

这样以后底层可以随时增加：

```text
VPSComputer
LocalComputer
E2BComputer
CloudflareComputer
KubernetesComputer
```

而 Agent 本身完全不需要修改。

---

# 6. 七个 Agent Tool 是否足够

结论：

> **足够。**

只要 `exec` 允许在 Sandbox 中执行通用 shell 命令，那么 Agent 已经具备通用计算能力。

甚至从“图灵完备的执行环境”角度来说：

```text
exec
+
write_file
```

就已经足够让 Agent：

```text
写程序
↓
执行程序
↓
读取结果
↓
修改程序
↓
再次执行
```

其他工具主要用于提高效率和用户体验。

---

# 7. Tool 1：exec

这是整个 Computer API 中最重要的能力。

建议接口：

```ts
exec({
  command: string,
  cwd?: string,
  env?: Record<string, string>,
  timeout_ms?: number,
  stdin?: string,
  background?: boolean
})
```

返回：

```ts
{
  exit_code: number | null,
  stdout: string,
  stderr: string,
  timed_out: boolean,
  process_id?: string
}
```

例如：

```text
exec({
  command: "python test.py",
  cwd: "/workspace"
})
```

Agent 可以通过它执行：

```text
git clone
git pull

npm install
npm test
npm run build

pip install
pytest

cargo build

go test

gcc

curl

grep
find
sed
awk

tar
zip

docker
```

因此不需要额外做：

```text
git_clone
npm_install
mkdir
rm_file
grep
list_process
```

等几十个细粒度工具。

这些全部可以通过 shell 完成。

OpenSSH 本身支持直接执行远程命令，因此 `exec` 可以直接映射为 SSH command execution。

---

# 8. Agent 自己写脚本

这是本方案必须支持的核心能力。

例如 Agent 判断一个任务需要复杂的数据处理。

第一步：

```text
write_file({
  path: "/workspace/analyze.py",
  content: "..."
})
```

第二步：

```text
exec({
  command: "python3 analyze.py",
  cwd: "/workspace"
})
```

得到：

```text
stdout
stderr
exit_code
```

如果报错：

```text
Traceback...
```

Agent：

```text
read_file("analyze.py")
```

然后重新：

```text
write_file(...)
```

再次：

```text
exec(...)
```

因此完整自主循环为：

```text
Think
 ↓
Write
 ↓
Execute
 ↓
Observe
 ↓
Fix
 ↓
Execute
 ↓
Observe
 ↓
Complete
```

这已经能够支持真正意义上的 Coding Agent。

---

# 9. Tool 2：read_file

建议接口：

```ts
read_file({
  path: string,
  offset?: number,
  limit?: number,
  encoding?: "utf8" | "base64"
})
```

返回：

```ts
{
  content: string,
  size: number,
  truncated: boolean
}
```

必须支持：

```text
offset
limit
```

原因是 Agent 不能每次读取一个几百 MB 的日志文件。

例如：

```text
read_file({
  path: "/workspace/server.log",
  offset: 100000,
  limit: 20000
})
```

---

# 10. Tool 3：write_file

建议：

```ts
write_file({
  path: string,
  content: string,
  encoding?: "utf8" | "base64",
  mode?: "overwrite" | "append",
  create_parents?: boolean
})
```

必须允许 Agent：

* 创建源码；
* 修改源码；
* 写 shell script；
* 写 Python script；
* 写配置；
* 写 `.env`；
* 写 package.json；
* 写 Dockerfile。

建议内部默认采用：

```text
write temp file
↓
fsync / complete write
↓
atomic rename
```

避免一次连接中断留下半个配置文件。

---

# 11. Tool 4：terminal

`exec` 用于：

```text
运行命令
↓
等待命令结束
↓
返回结果
```

但很多东西需要真正 PTY。

例如：

```text
python REPL
node REPL
top
htop
vim
interactive installer
CLI login
long-running dev server
```

因此需要：

```ts
terminal({
  action: "open" | "write" | "read" | "resize" | "close",
  terminal_id?: string,
  input?: string,
  cols?: number,
  rows?: number
})
```

OpenSSH 支持分配 pseudo-terminal，因此不需要自己在服务器实现一套 terminal daemon。

### 使用原则

Agent 默认应该优先使用：

```text
exec
```

只有遇到：

```text
interactive command
persistent shell
PTY requirement
```

才使用：

```text
terminal
```

否则 Agent 大量使用终端会显著增加状态管理复杂度。

---

# 12. 长时间运行任务

例如：

```text
npm run dev
python server.py
```

不能让普通 `exec` 无限等待。

因此 `exec` 建议支持：

```text
background: true
```

例如：

```ts
exec({
  command: "npm run dev",
  cwd: "/workspace",
  background: true
})
```

返回：

```json
{
  "process_id": "proc_123"
}
```

内部实现可以在 VPS 中建立：

```text
/workspace/.runtime/proc_123/
    stdout.log
    stderr.log
    pid
    exit_code
```

这样即使 SSH command channel 关闭，远端进程仍然可以继续运行。

MVP 不必额外暴露：

```text
process_list
process_status
process_kill
```

Agent 本身已经可以：

```text
exec("ps ...")
exec("kill ...")
read_file("stdout.log")
```

后期如果发现模型频繁操作进程时容易犯错，再增加结构化 Process API。

---

# 13. Tool 5：upload

定义：

> 将用户上传到 App 的文件发送到 VPS workspace。

例如用户手机上传：

```text
project.zip
data.csv
photo.png
```

Agent：

```text
upload(
  source_file_id,
  "/workspace/data.csv"
)
```

底层通过 SFTP 传输即可。SFTP 支持 SSH 上的安全文件上传及递归文件传输。

---

# 14. Tool 6：download

反向操作：

```text
VPS
 ↓
Platform
 ↓
Mobile App
```

例如 Agent：

```text
生成 result.xlsx
生成 app.apk
生成 build.zip
生成报告.pdf
```

然后：

```text
download("/workspace/result.xlsx")
```

返回：

```text
file_id
```

用户手机即可看到：

```text
Agent 创建了 result.xlsx

[下载]
```

---

# 15. Tool 7：open_port

这是 Coding Agent 很重要的一项能力。

例如 Agent 写了一个网站：

```text
npm run dev
```

运行：

```text
localhost:3000
```

Agent 调用：

```ts
open_port({
  port: 3000,
  protocol: "http"
})
```

平台返回：

```text
https://abc123.preview.example.com
```

用户直接在手机打开。

---

# 16. open_port 实现

不要求用户修改 VPS 防火墙。

也不要求开放：

```text
3000
5173
8000
8080
```

可以通过 SSH Local Forwarding：

```text
Preview Gateway
      ↓
Platform SSH Client
      ↓
SSH Tunnel
      ↓
VPS localhost:3000
```

OpenSSH 原生支持把本地 TCP 连接通过加密 SSH 通道转发到远端地址和端口，因此该能力可以直接建立在 SSH forwarding 上。

建议默认：

```text
VPS app:
127.0.0.1:3000
```

而不是：

```text
0.0.0.0:3000
```

用户不需要公开 VPS 的真实端口。

---

# 17. Preview URL

建议返回：

```text
https://<random-id>.preview.example.com
```

必须包含：

```text
authentication
TTL
rate limit
automatic revoke
```

建议默认：

```text
private preview
```

只有当前用户登录后才能访问。

用户主动选择：

```text
公开分享
```

才能创建 public preview。

---

# 18. SSH Connection

用户添加 VPS 时填写：

```text
Host
Port
Username
Authentication
```

Authentication 第一版建议支持：

```text
Password
SSH Private Key
```

后续可以增加：

```text
Keyboard Interactive
SSH Certificate
Bastion / ProxyJump
```

MVP 不需要一次全部实现。

---

# 19. Host Key Verification

不能把：

```text
StrictHostKeyChecking=no
```

作为正式方案。

SSH host key 的意义是确认“现在连接的服务器仍然是原来的服务器”；OpenSSH 会维护 known hosts，并在服务器身份发生变化时进行警告，从而帮助防止服务器伪装或中间人攻击。

建议产品实现：

### 首次连接

获取：

```text
host fingerprint
```

然后：

```text
TOFU
Trust On First Use
```

存储：

```text
computer_id
host
host_key
fingerprint
```

以后连接必须匹配。

如果变化：

```text
⚠️ 服务器身份发生变化

为了保护你的服务器，
Agent 已暂停连接。
```

不能直接静默接受新 key。

---

# 20. SSH Credential 生命周期

这是整个系统最敏感的部分之一。

原则：

> **SSH 密码和私钥永远不能进入 LLM context。**

Agent Tool Request：

```text
computer_id = xxx
```

Computer Manager 再从 Secret Storage 中取得凭据。

结构：

```text
LLM
 │
 │ computer_id
 ▼
Tool Service
 │
 ▼
Credential Broker
 │
 ▼
Secret Store / KMS
 │
 ▼
SSH Client
```

LLM 永远看不到：

```text
password
private key
sudo password
```

OWASP 的 Secrets Management 指南同样建议集中管理 secrets，并对 secret 的创建、权限、轮换、撤销和过期进行生命周期管理。

---

# 21. Password → Dedicated SSH Key

对于密码登录，我建议提供一个自动增强流程。

用户第一次输入：

```text
root
password
```

平台成功登录后：

```text
生成该 Computer 专属 SSH keypair
              ↓
自动追加 public key 到 authorized_keys
              ↓
测试 public key 登录
              ↓
成功
```

以后使用：

```text
dedicated SSH private key
```

而不再依赖密码。

产品 UI 可以显示：

```text
✓ 已升级为安全密钥登录
```

是否立即删除服务器端存储的密码，可以作为安全策略决定。

推荐成功切换到 key 后删除长期保存的 password。

---

# 22. 一个 VPS 一把 Key

绝对不要：

```text
所有用户 VPS
      ↓
同一把平台 SSH key
```

应该：

```text
VPS A → Key A
VPS B → Key B
VPS C → Key C
```

这样某一台 VPS 或某一个 credential 泄漏，不会直接扩大到全部计算节点。

---

# 23. Bootstrap

Bootstrap 指：

> 平台第一次连接 VPS 后自动检测和准备 Agent 所需要的执行环境。

用户不会运行任何脚本。

所有操作由后台通过已有 SSH session 执行。

---

# 24. Bootstrap 状态机

建议：

```text
CONNECTING
     ↓
AUTHENTICATING
     ↓
VERIFYING_HOST
     ↓
PROBING
     ↓
CHECKING_RUNTIME
     ↓
CONFIGURING
     ↓
CREATING_WORKSPACE
     ↓
VERIFYING_SANDBOX
     ↓
READY
```

用户手机只看到：

```text
正在连接服务器...
正在检测服务器...
正在配置 Agent 环境...
正在测试执行环境...

✓ Agent Ready
```

---

# 25. Probe 阶段

首先运行纯读取命令，不修改系统。

例如检测：

```text
uname
/etc/os-release
id
whoami
uname -m
df
free
command -v docker
docker version
sudo capability
shell
```

得到：

```json
{
  "os": "ubuntu",
  "version": "24.04",
  "arch": "amd64",
  "cpu": 4,
  "memory": "8GB",
  "disk_free": "60GB",
  "docker": true,
  "sudo": true
}
```

---

# 26. Sandbox Mode

建议支持两个执行模式。

## Mode A：Container Sandbox

优先模式。

```text
SSH
 ↓
Docker
 ↓
Container
 ↓
Agent
```

这是产品正常情况下应该使用的模式。

Docker 官方支持直接通过 SSH 与远程 Docker Engine 通信，包括 `docker context` 和 `DOCKER_HOST=ssh://...`。

因此我们不需要为了远程 Docker 控制而开放：

```text
2375
2376
```

等额外 Docker TCP 管理端口。

---

## Mode B：Direct SSH

fallback 模式：

```text
SSH
 ↓
remote shell
```

Agent 直接使用登录用户权限执行。

这个模式必须明确标记：

> **Computer Mode，而不是安全 Sandbox Mode。**

因为 Agent 可以访问该 SSH 用户本身能够访问的所有文件和系统资源。

适用于：

```text
用户明确希望 Agent 控制整台 VPS
```

但不应该宣传为强隔离环境。

---

# 27. Docker 自动配置

如果检测：

```text
Docker installed
```

则直接进入 container setup。

如果：

```text
Docker not installed
```

且：

```text
root OR sudo available
```

可以让平台自动安装。

但产品应在连接页面提前明确：

```text
连接后，我们可能自动安装 Docker 等 Agent 运行组件。
```

这仍然符合“用户零命令操作”。

---

# 28. Docker 安装策略

不要在生产产品里简单依赖：

```text
curl get.docker.com | sh
```

Docker 官方将 convenience installation script 定位为 testing/development 场景，而正式环境更适合按照对应 Linux distribution 的 repository 安装流程。

因此 Bootstrap Manager 应根据：

```text
/etc/os-release
```

选择不同 installer。

例如：

```text
UbuntuInstaller
DebianInstaller
CentOSInstaller
FedoraInstaller
```

Docker 官方目前对 Ubuntu、Debian、CentOS、Fedora、RHEL 等 Linux 平台提供正式 Engine 安装路径。

---

# 29. 第一版支持范围

为了避免 Bootstrap 变成 Linux 包管理器兼容工程，MVP 建议只承诺：

```text
Ubuntu
Debian

amd64
arm64
```

其他系统：

```text
检测到已有 Docker → 可以尝试支持
没有 Docker → 不自动安装
```

产品提示：

```text
当前服务器可以使用，
但不支持自动安装执行环境。
```

后续再逐渐扩大。

---

# 30. Workspace

每个 Agent Workspace 建议对应：

```text
~/.ourapp/workspaces/<workspace_id>
```

例如：

```text
~/.ourapp/workspaces/ws_01JX...
```

Container 内统一映射成：

```text
/workspace
```

因此 Agent 永远只看到：

```text
/workspace
```

不会关心真实 host path。

---

# 31. Workspace 生命周期

建议支持：

```text
persistent
temporary
```

默认：

```text
persistent
```

也就是说：

今天：

```text
Agent clone repo
npm install
写代码
```

第二天用户重新回来：

```text
/workspace
```

仍然存在。

这也是用户自有 VPS 相比临时 Cloud Sandbox 很重要的产品优势。

---

# 32. Container 生命周期

建议关系：

```text
Computer
  └── Workspace
        └── Sandbox Container
```

例如：

```text
computer_123
  ├── ws_project_a
  │     └── container_a
  │
  └── ws_project_b
        └── container_b
```

不同 Workspace 默认不共用 container。

---

# 33. Sandbox Image

MVP 不要让 Agent 每次从裸 Ubuntu 开始。

提供标准镜像，例如：

```text
ourapp/sandbox-base
```

包含：

```text
bash
git
curl
wget
ca-certificates

python
node
npm

build-essential

zip/unzip
jq
ripgrep
```

后续允许 Agent 自己安装额外软件。

这样：

```text
用户第一次任务
```

不会消耗大量时间重复安装基础开发工具。

---

# 34. Resource Limit

这是必须实现，而不是增强功能。

Docker 官方明确说明：**容器默认没有资源限制**，如果不主动配置，它能够使用 host scheduler 所允许的 CPU 和内存资源。

因此创建 Sandbox 时必须设置：

```text
memory
CPU
PID
```

例如：

```text
memory: 4 GB
CPU: 2
pids: 512
```

否则 Agent 一条错误程序：

```text
while(true) {}
```

就可能拖死用户整台 VPS。

---

# 35. 默认资源策略

例如：

```text
VPS RAM <= 2GB
Sandbox max = 1GB

VPS RAM 4GB
Sandbox max = 2GB

VPS RAM 8GB+
Sandbox max = 4GB
```

必须给 host 留出系统运行空间。

用户后续可以：

```text
Sandbox Settings

CPU       2 Core
Memory    4 GB
Disk      20 GB
```

自行修改。

---

# 36. Docker 权限问题

这里必须非常谨慎。

不要简单地认为：

```text
加入 docker group = 普通用户权限
```

Docker 官方明确警告：

> `docker` group 实际授予的是 root-level privileges。

所以不能把：

```text
docker.sock
```

mount 到 Agent container：

```text
/var/run/docker.sock
```

否则 Agent 基本可以控制整个 host Docker daemon，并进一步影响宿主机。

正确架构应该是：

```text
Agent
 ↓
Platform Tool Layer
 ↓
SSH
 ↓
Docker Engine
 ↓
Sandbox Container
```

Container 自己：

```text
❌ 不拥有 docker.sock
```

---

# 37. Rootless Docker

后续可以支持 Rootless Docker。

Rootless mode 会让 Docker daemon 和 container 都运行在非 root user namespace 中，用来降低 Docker daemon / runtime 漏洞可能带来的风险。

但不建议 MVP 强制要求 Rootless。

原因是 Rootless 存在额外 prerequisites，并且部分资源限制能力依赖 cgroup v2/systemd 等宿主环境。Docker 官方也对这些条件有明确说明。

因此可以采用：

```text
V1:
Standard Docker

V2:
Rootless Docker when supported
```

---

# 38. Container Security Defaults

Sandbox container 必须：

```text
❌ --privileged
❌ host PID
❌ host network
❌ mount /
❌ mount docker.sock
```

默认使用 Docker seccomp。

Docker 默认 seccomp profile 本身采用 syscall allowlist 思路，并阻止一批高风险 system calls；Docker 官方也不建议无理由禁用默认 seccomp profile。

即：

```text
不要：
--security-opt seccomp=unconfined
```

除非用户明确进入高级模式。

---

# 39. Sandbox 和 Host 的安全边界

需要明确产品语义：

### Container Sandbox

Agent 可以：

```text
修改 /workspace
安装包
运行程序
访问 Internet
启动服务
```

但默认不应该：

```text
修改 host /etc
读取 host SSH key
控制 host Docker
访问 host 全盘
修改 SSH config
```

### Direct SSH Mode

不存在上述强边界。

Agent 拥有：

> SSH 用户本身所拥有的权限。

如果登录的是：

```text
root
```

那实际上就是：

> Agent 拥有这台 VPS 的 root 权限。

UI 必须明确告知用户。

---

# 40. Sudo

Bootstrap 可能需要 sudo，例如安装 Docker。

流程：

```text
ssh login
 ↓
sudo -n true
```

如果成功：

```text
passwordless sudo
```

直接 bootstrap。

如果失败：

```text
需要 sudo password
```

如果 SSH 登录方式本身是 password，可以尝试用户授权使用相同密码。

如果仍然失败，手机 UI 再提示：

```text
需要管理员权限才能自动配置 Sandbox。

[输入 sudo 密码]
```

仍然是在手机操作，不要求用户进入服务器。

---

# 41. Connection Reuse

不要：

```text
每一个 exec
重新 TCP connect
重新 SSH handshake
重新认证
```

Computer Manager 应维护 SSH connection pool。

Docker 官方在 SSH remote Engine 的建议中也专门提到可通过 SSH connection reuse 降低重复连接成本。

可以抽象：

```text
ComputerSession

connection
sftp
terminal channels
forwarding channels
```

空闲一段时间后自动释放。

---

# 42. SSH 断线

必须默认假设：

```text
SSH 会断
```

原因可能是：

```text
网络抖动
VPS reboot
sshd restart
NAT timeout
平台实例重启
```

Tool Layer 不应该直接把所有断线视为任务永久失败。

建议：

```text
command 未开始
    → reconnect + retry

command 已确认开始
    → 不自动重复执行危险命令
```

因为：

```text
apt install
rm
数据库 migration
payment script
```

不能因为网络断了一次就盲目执行两遍。

因此每个 Tool Invocation 应存在：

```text
tool_call_id
execution_id
```

用于审计和幂等判断。

---

# 43. Agent Tool Permission

不能默认允许所有 Agent Task 无限执行任何行为。

建议未来支持：

```text
Permission Level
```

例如：

### Normal

```text
workspace full access
internet
package install inside sandbox
open preview port
```

### Elevated

```text
host package install
Docker management
system service changes
```

### Dangerous

```text
Direct SSH root
host filesystem modification
firewall changes
SSH config changes
```

重要操作可触发：

```text
Agent wants to modify host firewall.

[Allow Once]
[Always Allow]
[Deny]
```

---

# 44. Secret Protection

Agent 运行的代码可能是恶意的。

例如 Agent 从 npm 下载了一个被污染的 dependency。

因此 sandbox 内绝对不能存在：

```text
用户 VPS SSH password
SSH private key
平台 API key
所有用户 credential
KMS credential
```

SSH credential 只存在于：

```text
Platform Control Plane
```

Container 永远看不到。

这点非常重要。

---

# 45. Environment Secrets

如果未来用户需要：

```text
OPENAI_API_KEY
GITHUB_TOKEN
DATABASE_URL
```

应该做独立 Secret Injection。

而不是：

```text
写进 Agent prompt
```

例如：

```text
Sandbox Secrets

OPENAI_API_KEY = *********
DATABASE_URL   = *********
```

执行时注入：

```text
process environment
```

并提供独立 permission scope。

---

# 46. 日志

必须保留：

```text
谁
什么时候
哪个 Agent
哪个 Computer
哪个 Workspace
执行了什么 Tool
结果是什么
```

例如：

```json
{
  "user_id": "u_123",
  "computer_id": "c_456",
  "workspace_id": "ws_789",
  "tool": "exec",
  "command": "npm test",
  "exit_code": 0,
  "started_at": "...",
  "duration_ms": 4823
}
```

但：

```text
SSH password
Private key
secret environment variable
```

绝不能写入日志。

---

# 47. 手机端 UX

添加计算机：

```text
添加 VPS

服务器地址
[________________]

SSH 端口
[22______________]

用户名
[root____________]

登录方式
● 密码
○ SSH 私钥

密码
[••••••••••••••••]

              [连接]
```

---

# 48. Connecting UX

点击后：

```text
正在连接服务器...

✓ SSH 连接成功
✓ Ubuntu 24.04
✓ 4 CPU · 8 GB RAM
✓ Docker 已就绪
✓ 创建 Agent Sandbox

你的 VPS 已准备完成。

[开始使用]
```

不要显示：

```text
apt
docker daemon
cgroup
SSH tunnel
SFTP
seccomp
```

这些都是 implementation details。

---

# 49. 如果没有 Docker

例如：

```text
✓ SSH 连接成功
✓ Ubuntu 24.04

需要配置 Agent 执行环境
预计会安装 Docker。

[继续配置]
```

用户点一次：

```text
继续
```

平台自动完成。

这仍然是 0 terminal onboarding。

---

# 50. Agent 实际工作示例

用户：

```text
帮我做一个网页，
可以上传 CSV 然后生成数据图表。
```

Agent：

```text
exec("mkdir -p /workspace/app")

write_file("/workspace/app/package.json", ...)

write_file("/workspace/app/server.js", ...)

write_file("/workspace/app/index.html", ...)

exec("npm install", cwd="/workspace/app")

exec("npm test", cwd="/workspace/app")

exec(
  "npm start",
  cwd="/workspace/app",
  background=true
)

open_port(3000)
```

最后：

```text
完成了。

预览：
https://xxxx.preview.example.com
```

整个过程中：

用户没有：

```text
SSH
terminal
Docker
VPS panel
```

任何操作。

---

# 51. Agent Python 示例

用户：

```text
分析这个 CSV。
```

Agent：

```text
upload(file, "/workspace/data.csv")
```

然后：

```text
write_file(
  "/workspace/analyze.py",
  ...
)
```

再：

```text
exec(
  "python3 analyze.py",
  cwd="/workspace"
)
```

最后：

```text
download("/workspace/result.csv")
```

这就是完整的自主计算链。

---

# 52. 为什么七个 Tool 已经足够

七个工具覆盖：

| 能力                  | Tool         |
| --------------------- | ------------ |
| Shell                 | `exec`       |
| 文件读取              | `read_file`  |
| 文件修改              | `write_file` |
| Interactive shell     | `terminal`   |
| 输入文件              | `upload`     |
| 输出文件              | `download`   |
| Web / service preview | `open_port`  |

通过 `exec`，Agent 又自然获得：

```text
Git
package manager
compiler
database CLI
curl
shell script
Python
Node
Go
Rust
Docker CLI
系统工具
```

因此不应该一开始制造几十个专用 Tool。

工具越少：

```text
模型选择越简单
Tool schema 越稳定
Backend 越容易实现
不同 Sandbox backend 越容易兼容
```

---

# 53. 内部 API 与 Agent API 要分开

Agent-facing API 可以只有七个。

但平台内部完全可以有：

```text
connect()
disconnect()
probe()
createSandbox()
destroySandbox()
restartSandbox()
getMetrics()
installRuntime()
verifyRuntime()
createTunnel()
closeTunnel()
```

这些不需要暴露给模型。

这一点非常重要。

不要因为：

> Agent 只有七个 Tool

就强迫 Backend 也只能实现七个方法。

---

# 54. 推荐接口

最终：

```ts
interface Computer {
  exec(input: ExecInput): Promise<ExecResult>;

  readFile(input: ReadFileInput): Promise<ReadFileResult>;

  writeFile(input: WriteFileInput): Promise<void>;

  terminal(input: TerminalInput): Promise<TerminalResult>;

  upload(input: UploadInput): Promise<void>;

  download(input: DownloadInput): Promise<File>;

  openPort(input: OpenPortInput): Promise<PortPreview>;
}
```

底层：

```ts
class VpsComputer implements Computer
```

未来：

```ts
class LocalComputer implements Computer
class E2BComputer implements Computer
class CloudComputer implements Computer
```

Agent 侧无需变化。

---

# 55. `open_port` 建议接口

```ts
openPort({
  port: number,
  protocol?: "http" | "tcp",
  visibility?: "private" | "public",
  ttl_seconds?: number
})
```

返回：

```ts
{
  preview_id: string,
  url: string,
  expires_at: string
}
```

第一版甚至只支持：

```text
protocol=http
visibility=private
```

即可。

---

# 56. 路径限制

所有结构化 File Tool 默认限制在：

```text
/workspace
```

例如 Agent 调用：

```text
read_file("/etc/shadow")
```

在 Container Mode：

```text
拒绝
```

Agent 如果通过：

```text
exec("cat /etc/shadow")
```

读到的也应该只是 container 自己的 `/etc/shadow`。

这正是 container sandbox 的价值。

---

# 57. 网络

Coding Agent 默认需要互联网，因为它通常需要：

```text
npm install
pip install
git clone
curl docs
download dependencies
```

因此 MVP 可以：

```text
Internet: Allow
```

但后期建议增加：

```text
Private Network: Block by default
```

防止 Sandbox 扫描用户 VPS 所处的：

```text
LAN
VPC
cloud metadata
private database
internal Redis
NAS
```

网络隔离可以作为第二阶段安全增强，而不是阻塞第一版开发。

---

# 58. 用户删除 Computer

用户点击：

```text
删除 VPS
```

平台应该：

```text
close SSH connections
close port tunnels
revoke stored credential
delete platform-side secret
remove computer metadata
```

如果之前平台自动向：

```text
authorized_keys
```

加入过专属 key，可以尝试 SSH 登录后自动删除该 public key。

然后再删除本地 private key。

---

# 59. Disconnect 与 Delete 区别

建议：

### Disconnect

```text
停止 Agent 使用
credential 保留
workspace 保留
VPS 不修改
```

随时可以重新连接。

### Delete

```text
删除 Credential
删除绑定关系
撤销 preview
可选清理平台创建的 SSH key
```

默认不要自动删除用户 workspace。

否则可能误删用户数据。

---

# 60. Reboot

VPS reboot 后：

```text
SSH temporarily offline
```

之后 Agent Platform：

```text
reconnect
↓
probe
↓
check Docker
↓
check container
↓
resume
```

因为没有我们自己的常驻 daemon，恢复主要依赖：

```text
sshd
Docker service
workspace data
```

---

# 61. 错误状态

需要设计明确错误：

```text
AUTH_FAILED
HOST_KEY_CHANGED
SSH_TIMEOUT
SUDO_REQUIRED
UNSUPPORTED_OS
DOCKER_INSTALL_FAILED
DOCKER_NOT_RUNNING
DISK_FULL
MEMORY_LOW
SANDBOX_START_FAILED
PORT_FORWARD_FAILED
```

手机 UI 不显示内部 error code。

例如：

```text
无法连接 VPS

SSH 用户名或密码不正确。

[重新输入]
```

而不是：

```text
SSH_AUTH_FAILED errno 255
```

---

# 62. 能力检测

每台 Computer 存：

```json
{
  "capabilities": {
    "exec": true,
    "sftp": true,
    "pty": true,
    "docker": true,
    "sudo": true,
    "port_forward": true,
    "sandbox": true
  }
}
```

这样 Agent 在开始 task 前即可知道：

```text
这个 Computer 能干什么。
```

---

# 63. MVP 明确范围

## MVP 必须做

```text
SSH password auth
SSH private key auth

host key pinning

exec
read_file
write_file
terminal
upload
download
open_port

SFTP

Ubuntu / Debian detection

Docker detection

Docker auto provisioning

Container create/start

persistent workspace

CPU limit
memory limit
PID limit

SSH credential encryption

connection pooling

basic logs
```

---

# 64. MVP 暂时不要做

```text
Firecracker
gVisor
Kubernetes

Tailscale
WireGuard

Windows Server

GPU orchestration

multi-node scheduling

snapshot system

container image marketplace

SSH certificate CA

complex network ACL

automatic package manager for every Linux distro

custom VPS daemon
```

这些全部可以以后补。

不要让第一版偏离：

> **“用户填 SSH，然后 Agent 获得一台真正的电脑。”**

---

# 65. 推荐开发顺序

### Phase 1 — SSH Computer

完成：

```text
connect
exec
read
write
terminal
upload
download
```

此时已经可以验证：

> Agent 是否能够真正使用用户 VPS 完成任务。

---

### Phase 2 — Sandbox

增加：

```text
probe
Docker detect
Docker setup
workspace
container
resource limits
```

从：

```text
Remote Computer
```

升级成：

```text
Remote Sandbox
```

---

### Phase 3 — Preview

实现：

```text
background process
open_port
preview gateway
```

此时 Agent 已经可以：

```text
写网站
启动网站
手机直接预览
```

产品体验会有明显提升。

---

### Phase 4 — Security

增加：

```text
credential rotation
dedicated SSH keys
network policy
better seccomp
rootless mode
permission confirmation
audit logs
```

---

# 66. 一个重要的产品决定

建议不要强制用户提供：

```text
root
```

而是：

```text
root：最好体验，可以自动配置全部环境

sudo user：完整支持

普通 user + Docker available：支持

普通 user + 无 Docker：Direct Computer only
```

因此用户只需要提供自己现有的 VPS account。

系统自动判断可以做到什么。

---

# 67. 推荐产品状态

例如：

### Full Sandbox

```text
🟢 Agent Sandbox

Docker isolation
4 CPU
8 GB
```

### Direct Computer

```text
🟡 Direct Computer

Agent 将使用 SSH 用户权限直接操作服务器。
```

不要把两种安全级别混起来。

---

# 68. 最重要的安全边界

必须始终保证：

```text
                    ┌──────── LLM
                    │
                    │ 不能看到 SSH secret
                    │
                    ▼
               Tool Layer
                    │
                    ▼
              Credential Layer
                    │
                    ▼
                  SSH
                    │
                    ▼
              Docker Host
                    │
                    ▼
              Sandbox Container
```

Credential 永远向下流到 SSH Client。

不能反向进入：

```text
Prompt
Tool output
Container environment
Workspace
Logs
```

---

# 69. 核心技术结论

整个产品第一版并不需要复杂的分布式 Sandbox Infra。

真正需要解决的是四件事：

```text
1. SSH Remote Computer

2. Container Isolation

3. Credential Security

4. Preview Tunnel
```

OpenSSH 已经提供：

```text
remote command
authentication
PTY
encrypted transport
TCP forwarding
```

能力。

SFTP 已经提供：

```text
file transfer
directory/file operations
```

能力。

Docker 已经支持通过 SSH 管理远端 Engine。

因此没有必要为了 MVP 再设计：

```text
自定义 VPS RPC protocol
自定义 daemon
自定义 filesystem protocol
自定义 terminal protocol
自定义 container runtime
```

---

# 70. 最终推荐架构

第一版我建议正式确定为：

```text
Mobile App
    │
    ▼
Agent
    │
    ▼
Computer API
    │
    ├── exec
    ├── read_file
    ├── write_file
    ├── terminal
    ├── upload
    ├── download
    └── open_port
    │
    ▼
VPS Computer Adapter
    │
    ├── SSH
    ├── SFTP
    └── SSH Port Forward
    │
    ▼
User VPS
    │
    ▼
Docker
    │
    ▼
Sandbox Container
    │
    ▼
/workspace
```

用户体验：

```text
输入 VPS 登录信息
        ↓
连接
        ↓
Ready
```

Agent 体验：

```text
我有一台 Linux Computer。
```

平台体验：

```text
不承担用户计算资源成本。
```

用户体验：

```text
我的 Agent 真正拥有了一台长期在线、
拥有文件系统、Shell、Internet 和计算资源的电脑。
```

---

# 71. 当前建议暂定的七个 Tool

最终建议先冻结为：

```text
exec
read_file
write_file
terminal
upload
download
open_port
```

暂时不要增加第八、第九个 Agent Tool。

如果未来真实 Agent trace 显示某一种操作：

```text
高频
容易出错
浪费 token
```

再把它抽象成结构化 Tool。

例如未来可能出现：

```text
process
search_files
```

但应该由真实数据推动，而不是现在提前设计。

---

# 72. 最终一句话定义

本功能可以定义为：

> **Connect any VPS and turn it into a persistent computer for your AI Agent.**

技术上：

> **SSH 是控制通道，Docker 是隔离层，VPS 是算力，Computer API 是 Agent 与计算环境之间唯一的抽象。**

产品上：

> **用户只需要在手机输入 VPS 登录信息，之后所有配置和执行全部由 Agent 完成。**

这是当前版本最应该保持不变的核心。

