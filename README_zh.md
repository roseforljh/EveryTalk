# EveryTalk - 新一代智能对话平台

<p align="center">
  <strong>🚀 极致流畅 · 🎨 高度定制 · 🔒 隐私至上 · 🌐 全能多模态</strong>
</p>

<p align="center">
  <strong>一款革命性的 Android AI 对话客户端，集成前沿技术栈，支持任意大模型接入。<br/>从文本对话到图像生成，从语音交互到实时联网，打造您的专属 AI 助手。</strong>
</p>

## 🧭 项目状态

- **项目定位**: 面向 Android 的原生 AI 对话客户端，聚焦多模型接入、流式交互、多模态能力与本地化使用体验。
- **当前平台**: Android 8.1 及以上，推荐 Android 10+；开发环境建议使用 Android Studio + JDK 17。
- **能力范围**: 覆盖文本对话、视觉理解、图像生成、语音交互、数学公式渲染、原生联网搜索与 OpenClaw 远程控制扩展接入。
- **接入方式**: 支持直连兼容 API，也支持通过可选后端做请求代理、图像生成与附加能力扩展。
- **使用门槛**: 首次使用至少需要一套可用的模型 API 地址、模型名称与对应密钥；部分高级能力依赖上游模型或后端支持。

---

## 📌 适合 / 不适合

### 适合

- 想在 Android 设备上统一接入多种大模型（如最新的 OpenAI o1/o3-mini, Gemini 2.5/2.0, Claude 3.7 Sonnet, DeepSeek-R1 等）的个人用户。
- 需要流式响应、多模态输入、高级图像生成（如 Flux.1, Imagen 3, DALL-E 3）、语音交互的重度 AI 使用者。
- 希望基于 Kotlin + Jetpack Compose 学习原生 AI 应用实现的开发者。
- 需要通过 OpenClaw / Bridge 方案扩展远程能力的进阶用户。

### 不适合

- 期望开箱即用、无需任何 API 配置即可直接使用的用户。
- 只关注单一模型官方体验，不需要多模型切换与自定义配置的用户。
- 需要 iOS、Web 或桌面端原生客户端的场景。
- 希望所有联网、语音、图像等能力都在纯离线环境下可用的场景。

---

## ⚠️ 使用前须知

- 本项目当前主交付形态是 **Android 原生客户端**，核心工程位于 `app1/`。
- 应用支持 **无后端直连模式**，但并不是所有模型都具备一致能力；例如联网搜索只对支持原生搜索的模型生效。
- 图像生成、语音链路、后端代理、OpenClaw 等能力，是否可用取决于你接入的服务端或上游模型是否支持。
- README 中的“支持”表示项目已经提供对应接入链路或消费能力，不等于任意模型在任意配置下都默认可用。
- 仓库中同时包含 Android 客户端与可选后端相关代码，初次阅读时建议优先从 `README_zh.md`、`docs/openclaw-integration.md` 和 `docs/plans/` 进入。

---

## 📚 文档入口

- **项目总览**: 当前 `README_zh.md`（中文）/ `README.md`（英文）。
- **OpenClaw 专题**: [`docs/openclaw-integration.md`](docs/openclaw-integration.md)，说明 EveryTalk -> Bridge -> Gateway 的接入链路与部署方式。
- **方案沉淀**: `docs/plans/`，包含部分功能设计与演进方案，可用于理解历史决策与结构调整方向。
- **Claude / Agent 相关文档**: `CLAUDE.md` 与 `AGENTS.md`，适合需要在本仓库内继续开发或协作的维护者。

---

## 🌟 核心特性

### 🎯 智能对话系统
- **🤖 全模型兼容**: 无缝接入 OpenAI（GPT-4o, o1, o3-mini 等）、Google Gemini（Gemini 2.5, 2.0 Pro/Flash 等）、Anthropic Claude（Claude 3.7 Sonnet, 3.5 Opus 等）、智谱 GLM、本地 Ollama（Llama 3.3, Qwen 2.5, DeepSeek-R1）等任意兼容 API。
- **⚡ 极速流式响应**: 毫秒级流式输出，打字机效果丝般顺滑，当前采用直通式流式输出控制，接收到增量后直接累积并回传 UI。
- **🧠 推理过程可视化**: 支持 DeepSeek-R1、OpenAI o1/o3-mini 等推理模型的思考过程（Reasoning Chain）实时展示，让 AI 决策与反思透明化。
- **🌐 深度联网搜索**:
  - **Gemini**: 支持原生 Google Search 工具。
  - **Qwen**: 支持模型原生联网搜索。
  - **DeepSeek/OpenAI**: 支持最新原生联网搜索或通过 API 转发的搜索结果呈现。
- **🐍 代码解释器 (Code Execution)**:
  - **Gemini**: 自动识别需要计算或数据处理的场景，在沙箱环境中运行 Python 代码并展示结果。
- **🔄 智能降级机制**: 遇到 Cloudflare 拦截自动切换直连模式，确保服务永不中断。
- **📐 数学公式渲染**: 支持 LaTeX 数学公式（`$...$` 内联、`$$...$$` 块级），离线渲染，深浅色主题自适应。

### 🎨 多模态交互
- **🖼️ 视觉理解 (Vision)**:
  - **图片识别**: 支持 GPT-4o, Gemini 2.0/2.5 Flash/Pro, Claude 3.7 Sonnet 等最新的多模态视觉模型。
  - **文档解析**: 智能识别 PDF、Word、Excel 等文档内容，直接与文档对话（支持本地 PDF 解析）。
- **🎭 AI 图像生成**: 集成 Flux.1, Imagen 3, DALL·E 3 等主流图像生成模型，支持通过 API 获取高质量生图。
- **🎙️ 实时语音对话 (Live Voice)**:
  - **OpenAI**: 支持标准 TTS/STT 组合与 Realtime 音频流协议。
  - **Gemini**: 原生多模态音频流输入输出（低延迟 Live Voice 对话）。
  - **Azure/Aliyun**: 接入云端高拟真语音合成服务。
- **🎨 图像编辑增强**: 支持图文混合编辑，智能图片压缩与优化。

### ⚙️ 高级定制
- **🎛️ 深度参数控制**: Temperature、Top-P、Max Tokens 等参数精细调节。
- **📝 系统提示词管理**: 为不同场景创建专属 AI 人格。
- **🔧 多配置快速切换**: 保存多套 API 配置，一键切换不同模型。
- **🎨 图像比例预设**: 支持 1:1、16:9、9:16 等多种图像生成比例。
- **💾 会话历史管理**: 完整的对话历史记录与搜索功能。
- **☁️ 自动更新推送**: 每次发布新版本，自动通过 Telegram 频道推送通知。

### 🛡️ 安全与性能
- **🔒 本地数据存储**: 所有配置和聊天记录仅存储在本地，零数据上传。
- **🔐 请求签名验证**: 内置 HMAC-SHA256 签名机制，防止中间人攻击。
- **⚡ 智能性能优化**:
  - 流式输出控制器：直通式累积并回传 UI，支持长文本流式展示。
  - 智能滚动管理：用户手动滚动时自动暂停自动跟随。
- **🔄 容错与重试**: 多后端 URL 自动回退，确保服务高可用。

### 🎨 现代化 UI/UX
- **🌓 完美深色模式**: 精心设计的浅色/深色主题，护眼舒适。
- **📱 Material Design 3**: 基于 Jetpack Compose 构建，遵循最新设计规范。
- **✨ 流畅动画效果**: Crossfade 过渡、平滑滚动、触觉反馈。
- **🎯 直观交互设计**: 侧边栏快速切换、长按操作菜单、智能输入建议。

---

## 🚀 快速开始

### 1. 下载安装

1.  前往项目的 [**Releases**](https://github.com/roseforljh/EveryTalk/releases/latest) 页面。
2.  下载最新版本的 `app-release.apk` 文件。
3.  在您的安卓设备上允许"安装未知来源的应用",然后点击 APK 文件进行安装。

### 1.1 自动发版与 Telegram 通知

项目已配置完整的 CI/CD 流程（见 [.github/workflows/build-artifacts.yml](.github/workflows/build-artifacts.yml)）：

1. **自动发版**：
   - 合并 Release PR 后，自动构建 Release APK 和 AAB。
   - 自动上传构建产物到 GitHub Releases。

2. **Telegram 通知**：
   - 构建完成后，机器人会自动向配置的 Telegram 频道发送新版本通知。
   - 通知包含：版本号、更新日志链接、下载地址。

### 2. 开发者配置

在开发或编译项目之前，需在本地配置 API 密钥。

1. 复制模板 `app1/local.properties.example` 并重命名为 `app1/local.properties`。
2. 填入您的配置信息（可留空使用直连，也可以配置对应的后端代理与 Key）：

```properties
# local.properties 示例配置
GOOGLE_API_KEY="AIzaSy..."
GOOGLE_API_BASE_URL="https://generativelanguage.googleapis.com"
DEFAULT_OPENAI_API_BASE_URL="https://api.openai.com"
SILICONFLOW_API_KEY="sk-..."
# 更多配置请参阅 local.properties.example
```

---

## 🛠️ 技术架构

### 前端技术栈
```
┌─────────────────────────────────────────┐
│         Jetpack Compose UI              │
│  Material Design 3 · 响应式布局         │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│      Kotlin Coroutines & Flow           │
│  异步处理 · 流式数据 · 状态管理         │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│         Ktor Client 网络层              │
│  SSE 流式解析 · 多后端容错 · 直连降级   │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│      本地数据持久化 (Room Database)      │
│  SharedPreferences · 文件管理           │
└─────────────────────────────────────────┘
```

### 核心组件
- **[`ApiClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/ApiClient.kt)**: 统一网络请求客户端，支持流式 SSE 解析、多后端容错、Cloudflare 拦截自动降级。
- **[`GeminiDirectClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/GeminiDirectClient.kt)** / **[`OpenAIDirectClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/OpenAIDirectClient.kt)**: 直连客户端，支持无后端模式。
- **[`VoiceChatSession.kt`](app1/app/src/main/java/com/android/everytalk/data/network/VoiceChatSession.kt)**: 语音对话会话管理，STT → Chat → TTS 完整链路。

### 系统要求
| 组件 | 要求 |
|------|------|
| **最低 Android 版本** | Android 8.1 (API 27) |
| **推荐 Android 版本** | Android 10+ (API 29+) |
| **开发 IDE** | Android Studio Koala+ / Ladybug+ |
| **JDK 版本** | JDK 17+ |
| **后端服务** | 可选，支持无后端直连模式 |

### 后端服务（可选）
- **开源后端**: [backdAiTalk](https://github.com/roseforljh/backdAiTalk)
- **功能**: 请求代理、图像生成、速率限制。
- **部署**: 支持 Docker 一键部署，可配置多实例负载均衡。

---

## 📁 项目结构

```
EveryTalk/
├── app1/                                    # 📱 Android 客户端
│   ├── app/
│   │   ├── src/main/java/com/android/everytalk/
│   │   │   ├── config/                      # ⚙️ 配置管理
│   │   │   │   └── BackendConfig.kt         # 后端 URL 配置
│   │   │   ├── data/                        # 📊 数据层
│   │   │   │   ├── network/                 # 🌐 网络请求
│   │   │   │   │   ├── ApiClient.kt         # 统一 API 客户端
│   │   │   │   │   ├── GeminiDirectClient.kt    # Gemini 直连
│   │   │   │   │   ├── OpenAIDirectClient.kt    # OpenAI 直连
│   │   │   │   │   ├── VoiceChatSession.kt      # 语音对话
│   │   │   │   │   └── AppStreamEvent.kt        # 流式事件定义
│   │   │   │   └── DataClass/               # 数据模型
│   │   │   ├── statecontroller/             # 🎮 状态管理
│   │   │   │   ├── AppViewModel.kt          # 主视图模型
│   │   │   │   ├── facade/                  # 🌉 UI 状态门面 (原 statecontroller.ui)
│   │   │   │   ├── StreamingBuffer.kt       # 流式缓冲
│   │   │   │   └── ViewModelStateHolder.kt  # 状态持有者
│   │   │   ├── ui/                          # 🎨 UI 层
│   │   │   │   ├── screens/                 # 页面组件
│   │   │   │   │   ├── MainScreen/          # 主聊天界面
│   │   │   │   │   ├── ImageGeneration/     # 图像生成界面
│   │   │   │   │   └── settings/            # 设置界面
│   │   │   │   └── components/              # 通用组件
│   │   │   └── util/                        # 🔧 工具类
│   │   │       ├── RequestSignatureUtil.kt       # 请求签名
│   │   │       └── ScrollController.kt           # 滚动控制
│   │   └── build.gradle.kts                 # 构建配置
│   └── local.properties.example             # 配置示例
├── ET-Backend-code/                         # 🖥️ 后端服务（可选）
│   ├── eztalk_proxy/                        # Python FastAPI 服务
│   └── Dockerfile                           # Docker 部署
└── README.md                                # 📖 项目英文文档
```

---

## 🔌 通信协议详解

### 流式聊天接口

**端点**: `POST /chat`

**请求格式**: `multipart/form-data`
```
├── chat_request_json: ChatRequest (JSON)
│   ├── model: string
│   ├── messages: Array<Message>
│   ├── useWebSearch: boolean
│   ├── showReasoning: boolean
│   └── generationConfig: {...}
└── uploaded_documents: File[] (可选)
```

**响应格式**: Server-Sent Events (SSE)
```
data: {"type":"content","text":"Hello","output_type":null,"block_type":null}

data: {"type":"reasoning","text":"Let me think..."}

data: {"type":"web_search_results","results":[...]}

data: {"type":"finish","reason":"stop"}

data: [DONE]
```

**事件类型**:
- `content`: 流式文本内容。
- `content_final`: 最终完整内容。
- `reasoning`: 推理思考过程（针对 o1, o3-mini, DeepSeek-R1）。
- `reasoning_finish`: 推理结束。
- `web_search_status`: 联网搜索状态更新。
- `web_search_results`: 联网搜索结果。
- `tool_call`: 工具调用。
- `error`: 错误信息。
- `finish`: 流结束。

### 图像生成接口

**端点**: `POST /v1/images/generations`

**请求格式**: `application/json`
```json
{
  "model": "flux-1-schnell",
  "prompt": "A beautiful sunset over the mountains",
  "image_size": "1024x1024",
  "batch_size": 1,
  "apiAddress": "https://api.siliconflow.cn",
  "apiKey": "sk-...",
  "forceDataUri": true
}
```

**响应格式**:
```json
{
  "images": [
    {
      "url": "data:image/png;base64,..."
    }
  ]
}
```

### 直连模式

当后端代理不可用或选择直接连上游时，客户端支持切换到直连模式：

- **Gemini 直连**:
  - 端点: `https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent`
  - 支持: 流式对话、多模态输入、原生联网搜索、代码解释器。
- **OpenAI 兼容直连**:
  - 端点: `https://api.openai.com/v1/chat/completions` (或任何兼容镜像)
  - 支持: 流式对话、多模态输入、推理模式；Qwen 等兼容模型支持开启原生搜索。

### 安全机制

**请求签名**: 所有发送至可选后端的请求均使用 HMAC-SHA256 签名：
```
X-Signature: HMAC-SHA256(secret, method + path + timestamp + body)
X-Timestamp: Unix timestamp
```

**数据加密**:
- 本地设备数据使用 AES-256 加密存储。
- 网络传输强制使用 HTTPS。

---

## 📚 技术实现文档

| 主题 | 说明 | 链接 |
|------|------|------|
| **OpenClaw 远程接入** | 说明如何通过 EveryTalk + VPS Bridge + OpenClaw Gateway 的方式远程控制龙虾。 | [👉 查看文档](docs/openclaw-integration.md) |

---

## 🦞 OpenClaw 接入指南

如果你希望通过 EveryTalk 远程控制部署在服务器上的 Claw 龙虾控制端：

```bash
curl -fsSL https://claw.everytalk.cc | bash
```

详细信息请参见 [OpenClaw 接入指南](docs/openclaw-integration.md)。

---

## 🤝 贡献代码

1.  **Fork** 本仓库。
2.  创建特性分支 (`git checkout -b feature/AmazingFeature`)。
3.  提交修改 (`git commit -m 'Add some AmazingFeature'`)。
4.  推送分支 (`git push origin feature/AmazingFeature`)。
5.  **创建 Pull Request**。

请确保遵循 Kotlin 官方编码规范，并为代码添加必要的注释和测试！

---

## ❓ 常见问题

<details>
<summary><b>Q1: 为什么无法连接到本地模型服务？</b></summary>

**A:** 请检查以下几点：
1. 确保手机与电脑在同一局域网下。
2. 使用电脑的局域网 IP（如 `192.168.1.100`），而非 `localhost` 或 `127.0.0.1`。
3. 检查电脑防火墙是否允许该端口流量。
4. 确认 Ollama 等本地服务已正确启动并在 `0.0.0.0` 绑定监听。
</details>

<details>
<summary><b>Q2: 联网搜索是如何实现的？</b></summary>

**A:** 应用采用模型原生搜索方案：
1. **Gemini**: 启用原生 `google_search` 搜索工具。
2. **Qwen**: 传入 `enable_search=true` 原生搜索参数。
3. **DeepSeek**: 启用对应的联网搜索开关。
搜索结果会以引用卡片形式显示在会话中，点击可直接打开链接。
</details>

<details>
<summary><b>Q3: 支持哪些最新的 AI 模型？</b></summary>

**A:** 支持任何兼容 OpenAI 接口的模型。推荐的最新的模型有：
- **推理与深度反思模型**: DeepSeek-R1, OpenAI o1, o3-mini。
- **商业旗舰模型**: Claude 3.7 Sonnet, Claude 3.5 Opus, Gemini 2.5 Pro/Flash, GPT-4o。
- **开源/本地模型**: Llama 3.3, Qwen 2.5, DeepSeek-V3（通过 Ollama, vLLM 等部署）。
- **图像生成模型**: Flux.1 系列, Gemini Imagen 3, DALL-E 3。
</details>

<details>
<summary><b>Q4: 应用是否收集用户数据？</b></summary>

**A:** **绝对不会！**
- ✅ 所有配置和聊天记录仅加密存储在本地。
- ✅ API 请求直接发送到您指定的 API 端点。
- ✅ 不包含任何数据统计或追踪 SDK。
</details>

<details>
<summary><b>Q5: AI流式输出结束后列表跳动怎么办？</b></summary>

**A:** 这是因为流式期间的紧凑 Markdown 与完成后加载代码块/表格工具条产生的突变。应用默认开启了**等高占位**与**单次完成替换**策略。如果您想微调或关闭该功能，可前往 `PerformanceConfig.kt` 中修改对应的开关：
```kotlin
ENABLE_STREAMING_HEIGHT_PLACEHOLDER = true
ENABLE_SINGLE_SWAP_RENDERING = true
```
</details>

---

## 📄 开源协议

本项目采用 [MIT License](LICENSE.md) 开源协议。

---

<p align="center">
  <strong>如果这个项目对您有帮助，请点一个 ⭐ Star！</strong>
</p>

<p align="center">
  Made with ❤️ by the EveryTalk Team
</p>
