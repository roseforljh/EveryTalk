# EveryTalk - 新一代智能对话平台

<p align="center">
  <img src="https://qone.kuz7.com/uploads/images/2025/11/09/28553c13-29b3-4ab4-87a8-e72ad4be8568.png" alt="EveryTalk Logo" width="200"/>
</p>

<p align="center">
  <strong>🚀 极致流畅 · 🎨 高度定制 · 🔒 隐私至上 · 🌐 全能多模态</strong>
</p>

<p align="center">
  <strong>一款革命性的 Android AI 对话客户端，集成前沿技术栈，支持任意大模型接入。<br/>从文本对话到图像生成，从语音交互到实时联网，打造您的专属 AI 助手。</strong>
</p>

<p align="center">
  <a href="https://github.com/roseforljh/EveryTalk/releases/latest"><img src="https://img.shields.io/github/v/release/roseforljh/EveryTalk?style=for-the-badge&label=release" alt="GitHub release (latest by date)"></a>
  <a href="https://github.com/roseforljh/EveryTalk/blob/main/LICENSE.md"><img src="https://img.shields.io/github/license/roseforljh/EveryTalk?style=for-the-badge" alt="GitHub"></a>
  <a href="https://github.com/roseforljh/EveryTalk/stargazers"><img src="https://img.shields.io/github/stars/roseforljh/EveryTalk?style=for-the-badge" alt="GitHub stars"></a>
</p>

---

## 目录

- [🌟 核心特性](#-核心特性)
- [📱 应用截图](#-应用截图)
- [🚀 快速开始](#-快速开始)
- [👨‍💻 开发者指南](#-开发者指南)
- [🛠️ 技术架构](#️-技术架构)
- [📁 项目结构](#-项目结构)
- [🔌 通信协议详解](#-通信协议详解)
- [🤝 贡献代码](#-贡献代码)
- [❓ 常见问题](#-常见问题)
- [📄 开源协议](#-开源协议)

---

## 🌟 核心特性

### 🎯 智能对话系统
- **🤖 全模型兼容**: 无缝接入 OpenAI、Google Gemini、Anthropic Claude、本地 Ollama 等任意兼容 API
- **⚡ 极速流式响应**: 毫秒级流式输出，打字机效果丝般顺滑，配备智能去重与内容缓冲机制
- **🧠 推理过程可视化**: 支持 o1/o3 等推理模型的思考过程实时展示，让 AI 决策透明化
- **🌐 智能联网搜索**: 实时获取最新信息，自动整合搜索结果，并标注信息来源
- **🔄 智能降级机制**: 遇到 Cloudflare 拦截自动切换直连模式，确保服务永不中断
- **📐 数学公式渲染**: 支持 LaTeX 数学公式（$...$内联、$$...$$块级），离线渲染，深浅色主题自适应

### 🎨 多模态交互
- **🖼️ 图文混合对话**: 支持图片上传、识别与分析，实现真正的多模态理解
- **🎭 AI 图像生成**: 集成 DALL·E、Gemini Imagen 等主流图像生成模型
- **🎨 图像编辑增强**: 支持图文混合编辑，智能图片压缩与优化
- **🎙️ 语音实时对话**: STT → Chat → TTS 完整语音交互链路，支持多种音色选择
- **📁 文件智能处理**: 支持多种文件格式上传与解析

### ⚙️ 高级定制
- **🎛️ 深度参数控制**: Temperature、Top-P、Max Tokens 等参数精细调节
- **📝 系统提示词管理**: 为不同场景创建专属 AI 人格
- **🔧 多配置快速切换**: 保存多套 API 配置，一键切换不同模型
- **🎨 图像比例预设**: 支持 1:1、16:9、9:16 等多种图像生成比例
- **💾 会话历史管理**: 完整的对话历史记录与搜索功能

### 🛡️ 安全与性能
- **🔒 本地数据存储**: 所有配置和聊天记录仅存储在本地，零数据上传
- **🔐 请求签名验证**: 内置 HMAC-SHA256 签名机制，防止中间人攻击
- **⚡ 智能性能优化**: 
  - 流式输出控制器：防止内存溢出，支持 500KB+ 长文本
  - 内容去重机制：基于 SHA-256 + LRU 缓存，避免重复渲染
  - 智能滚动管理：用户手动滚动时自动暂停自动跟随
- **🔄 容错与重试**: 多后端 URL 自动回退，确保服务高可用

### 🎨 现代化 UI/UX
- **🌓 完美深色模式**: 精心设计的浅色/深色主题，护眼舒适
- **📱 Material Design 3**: 基于 Jetpack Compose 构建，遵循最新设计规范
- **✨ 流畅动画效果**: Crossfade 过渡、平滑滚动、触觉反馈
- **🎯 直观交互设计**: 侧边栏快速切换、长按操作菜单、智能输入建议

## 📱 应用截图

### 夜间模式

|                                 主聊天界面                                 |                                 侧边栏/模型切换                                 |                                 设置界面                                 |
| :------------------------------------------------------------------------: | :-----------------------------------------------------------------------------: | :------------------------------------------------------------------------: |
| <img src="https://qone.kuz7.com/uploads/images/2025/11/10/fbe91690-d33f-414d-b600-43fdfdce4d92.png" alt="主聊天界面" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/11/10/c679a95d-b671-4b9c-a582-4c18b1280206.png" alt="侧边栏/模型切换" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/11/10/b4af6d5f-8537-4888-bb08-7b2bbba530ef.png" alt="设置界面" width="280"/> |

|                                 图像生成界面                                 |                                 关于                                 |
| :------------------------------------------------------------------------: | :-----------------------------------------------------------------------------: |
| <img src="https://qone.kuz7.com/uploads/images/2025/11/10/bd4f5a89-a4cb-4554-9d98-15135b97adaf.png" alt="图像生成界面" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/11/10/2656afa4-7af1-4f42-8ace-d53c38a16b02.png" alt="关于" width="280"/> 

---

## 🚀 快速开始

### 1. 下载安装

1.  前往项目的 [**Releases**](https://github.com/roseforljh/EveryTalk/releases/latest) 页面。
2.  下载最新版本的 `app-release.apk` 文件。
3.  在您的安卓设备上允许"安装未知来源的应用",然后点击 APK 文件进行安装。

### 1.1 自动发版与安装包获取

以下流程已在仓库中自动配置完成（工作流见 [.github/workflows/release-please.yml](.github/workflows/release-please.yml:1)、[.github/workflows/build-artifacts.yml](.github/workflows/build-artifacts.yml:1)）：

1. 推送任意提交到 `main`/`master` 分支  
   - 机器人会自动创建“发版 PR”，其中包含自动生成的版本号与 Changelog。
2. 在 GitHub 上审阅并合并该“发版 PR”  
   - 合并后将自动创建新的 Release 与 Tag。
3. 打开最新的 Release 页面，下载安装包  
   - 入口：项目页 → Releases → 最新版本  
   - 你将看到以下附件（Assets）：  
     - Android 安装包：`app-release.apk`（若未签名将提供 `debug` APK）  
     - 应用商店包：`*.aab`（若构建成功）  
     - 后端打包：`ET-Backend-code.tar.gz`
4. 可选：配置 Android 签名（建议用于生成可分发的 release APK/AAB）  
   - 仓库 → Settings → Secrets and variables → Actions → New repository secret  
   - 添加以下 Secrets：  
     - `ANDROID_KEYSTORE_BASE64`：将你的 `everytalk-release.jks` 用 base64 编码后的字符串  
     - `ANDROID_KEYSTORE_PASSWORD`  
     - `ANDROID_KEY_ALIAS`  
     - `ANDROID_KEY_PASSWORD`  
   - 构建脚本会自动读取这些变量并完成签名；未设置时会回退上传 `debug` APK 以便测试。
### 2. 配置模型

首次启动应用后,您需要配置连接 AI 模型的 API。

1.  从主屏幕左侧边缘向右滑动,或点击左上角的菜单按钮,打开侧边栏。
2.  点击下方的 **"添加一套新配置"** 来创建您的专属连接。
3.  在配置页面,填写您的服务信息:
    - **配置名称**: 一个容易识别的名字 (例如: "我的本地模型")。
    - **API 地址**: 您的 AI 服务 API 端点。
    - **API 密钥**: 访问服务所需的密钥 (如果不需要,可以留空)。
    - **模型名称**: 您想要使用的具体模型 ID (例如: `gpt-4`, `llama3-70b-8192`)。
    - **系统提示词**: (可选)用于设定 AI 的角色和行为。
4.  点击"保存",您的新配置就会出现在侧边栏列表中。

> **提示**: 应用支持无后端直连模式（Gemini/OpenAI），也可配置后端代理以使用联网搜索等高级功能。

### 3. 开始对话

1.  在侧边栏选择您想使用的配置。
2.  返回主聊天界面,在底部的输入框中输入您的问题。
3.  点击右侧的发送按钮,或点击 "+" 号上传图片进行多模态对话。
4.  您还可以通过输入框上方的开关来启用 **联网搜索** 或查看 **思考过程**。

## 👨‍💻 开发者指南

### 从源码构建

如果您想自行修改代码或体验最新功能,可以从源码构建应用:

1.  克隆本仓库:
    ```bash
    git clone https://github.com/roseforljh/EveryTalk.git
    cd EveryTalk
    ```
2.  使用 Android Studio 打开 `app1` 目录。
3.  等待 Gradle 同步和构建完成。
4.  连接您的安卓设备或使用模拟器,点击 "Run 'app'"。

### 开发者配置

应用支持通过构建参数注入一个"后端代理 URL 列表",用于容灾或并发请求:

复制 `app1/local.properties.example` 为 `app1/local.properties`,并按需填写:

```properties
# 生产环境后端代理 URL (多个用逗号分隔)
BACKEND_URLS_RELEASE="http://prod1.example.com/chat,http://prod2.example.com/chat"

# 开发环境后端代理 URL
BACKEND_URLS_DEBUG="http://127.0.0.1:8000/chat"
```

- 构建脚本会将该配置注入到 `BuildConfig.BACKEND_URLS` 中。
- 默认使用串行模式(逐个尝试 URL),可在 `app/build.gradle.kts` 中开启并发模式。
- 推荐将 URL 指向后端的聊天路由(如 `/chat`),图像生成路由会自动适配。

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
│      本地数据持久化层                    │
│  SharedPreferences · 文件管理           │
└─────────────────────────────────────────┘
```

### 核心组件
- **[`ApiClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/ApiClient.kt)**: 统一网络请求客户端，支持流式 SSE 解析、多后端容错、Cloudflare 拦截自动降级
- **[`StreamingOutputController.kt`](app1/app/src/main/java/com/android/everytalk/util/StreamingOutputController.kt)**: 流式输出控制器，实现打字机效果与内容去重
- **[`GeminiDirectClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/GeminiDirectClient.kt)** / **[`OpenAIDirectClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/OpenAIDirectClient.kt)**: 直连客户端，支持无后端模式
- **[`VoiceChatSession.kt`](app1/app/src/main/java/com/android/everytalk/data/network/VoiceChatSession.kt)**: 语音对话会话管理，STT → Chat → TTS 完整链路
- **[`ImageCompressionPreferences.kt`](app1/app/src/main/java/com/android/everytalk/config/ImageCompressionPreferences.kt)**: 智能图片压缩配置管理

### 系统要求
| 组件 | 要求 |
|------|------|
| **最低 Android 版本** | Android 8.1 (API 27) |
| **推荐 Android 版本** | Android 10+ (API 29+) |
| **开发 IDE** | Android Studio Koala+ |
| **JDK 版本** | JDK 17+ |
| **后端服务** | 可选，支持无后端直连模式 |

### 后端服务（可选）
- **开源后端**: [backdAiTalk](https://github.com/roseforljh/backdAiTalk)
- **功能**: 请求代理、联网搜索、图像生成、速率限制
- **部署**: 支持 Docker 一键部署，可配置多实例负载均衡

## 📁 项目结构

```
EveryTalk/
├── app1/                                    # 📱 Android 客户端
│   ├── app/
│   │   ├── src/main/java/com/android/everytalk/
│   │   │   ├── config/                      # ⚙️ 配置管理
│   │   │   │   ├── BackendConfig.kt         # 后端 URL 配置
│   │   │   │   └── ImageCompressionPreferences.kt  # 图片压缩配置
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
│   │   │   │   ├── StreamingBuffer.kt       # 流式缓冲
│   │   │   │   └── ViewModelStateHolder.kt  # 状态持有者
│   │   │   ├── ui/                          # 🎨 UI 层
│   │   │   │   ├── screens/                 # 页面组件
│   │   │   │   │   ├── MainScreen/          # 主聊天界面
│   │   │   │   │   ├── ImageGeneration/     # 图像生成界面
│   │   │   │   │   └── settings/            # 设置界面
│   │   │   │   └── components/              # 通用组件
│   │   │   └── util/                        # 🔧 工具类
│   │   │       ├── StreamingOutputController.kt  # 流式输出控制
│   │   │       ├── ImprovedContentDeduplicator.kt # 内容去重
│   │   │       ├── RequestSignatureUtil.kt       # 请求签名
│   │   │       └── ScrollController.kt           # 滚动控制
│   │   └── build.gradle.kts                 # 构建配置
│   └── local.properties.example             # 配置示例
├── ET-Backend-code/                         # 🖥️ 后端服务（可选）
│   ├── eztalk_proxy/                        # Python FastAPI 服务
│   │   ├── api/                             # API 路由
│   │   ├── services/                        # 业务逻辑
│   │   └── docs/                            # 接口文档
│   └── Dockerfile                           # Docker 部署
└── README.md                                # 📖 项目文档
```

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
- `content`: 流式文本内容
- `content_final`: 最终完整内容
- `reasoning`: 推理过程（o1/o3 模型）
- `reasoning_finish`: 推理结束
- `web_search_status`: 搜索状态更新
- `web_search_results`: 搜索结果
- `tool_call`: 工具调用
- `error`: 错误信息
- `finish`: 流结束

### 图像生成接口

**端点**: `POST /v1/images/generations`

**请求格式**: `application/json`
```json
{
  "model": "dall-e-3",
  "prompt": "A beautiful sunset",
  "image_size": "1024x1024",
  "batch_size": 1,
  "apiAddress": "https://api.openai.com",
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

当后端不可用时，客户端自动切换到直连模式：

**Gemini 直连**:
- 端点: `https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent`
- 支持: 流式对话、多模态输入、联网搜索

**OpenAI 直连**:
- 端点: `https://api.openai.com/v1/chat/completions`
- 支持: 流式对话、多模态输入、推理模式

### 安全机制

**请求签名**: 所有请求使用 HMAC-SHA256 签名
```
X-Signature: HMAC-SHA256(secret, method + path + timestamp + body)
X-Timestamp: Unix timestamp
```

**数据加密**: 
- 本地数据使用 AES-256 加密存储
- 网络传输强制 HTTPS
- 支持自定义 SSL 证书固定

---

## 🤝 贡献代码

我们非常欢迎社区的贡献!无论是报告 Bug、提出功能建议,还是提交代码,都是对项目的巨大支持。

1.  **Fork** 本仓库。
2.  创建您的特性分支 (`git checkout -b feature/AmazingFeature`)。
3.  提交您的修改 (`git commit -m 'Add some AmazingFeature'`)。
4.  推送至您的分支 (`git push origin feature/AmazingFeature`)。
5.  **创建 Pull Request**。

请确保遵循 Kotlin 官方编码规范,并为您的代码添加必要的注释和测试。。

---

## ❓ 常见问题

<details>
<summary><b>Q1: 为什么无法连接到本地模型服务？</b></summary>

**A:** 请检查以下几点：
1. 确保手机与电脑在同一局域网
2. 使用电脑的局域网 IP（如 `192.168.1.100`），而非 `localhost` 或 `127.0.0.1`
3. 检查防火墙是否允许相应端口
4. 确认后端服务已正常启动
5. 尝试在浏览器中访问 API 地址测试连通性
</details>

<details>
<summary><b>Q2: 联网搜索是如何实现的？</b></summary>

**A:** 联网搜索有两种实现方式：
1. **后端代理模式**：后端调用搜索引擎 API，整合结果后注入到 AI 上下文
2. **直连模式**：客户端直接调用搜索 API（需在 `customExtraBody` 中配置搜索端点）

搜索结果会以引用卡片形式展示，点击可查看来源链接。
</details>

<details>
<summary><b>Q3: 支持哪些 AI 模型？</b></summary>

**A:** 支持所有兼容 OpenAI API 格式的模型：
- **商业模型**: OpenAI GPT-4/o1, Google Gemini, Anthropic Claude, 智谱 GLM 等
- **开源模型**: 通过 Ollama、LM Studio、vLLM 等部署的 Llama、Qwen、DeepSeek 等
- **图像模型**: DALL·E 3, Gemini Imagen, Stable Diffusion 等

只需填入对应的 API 地址和密钥即可使用。
</details>

<details>
<summary><b>Q4: 如何配置后端代理？</b></summary>

**A:** 后端代理是可选的，配置步骤：
1. 克隆后端仓库: `git clone https://github.com/roseforljh/backdAiTalk.git`
2. 配置环境变量（参考 `.env.example`）
3. 使用 Docker 部署: `docker-compose up -d`
4. 在应用的 `local.properties` 中填入后端 URL

**无后端模式**：应用支持直连 Gemini 和 OpenAI，无需后端即可使用基础功能。
</details>

<details>
<summary><b>Q5: 应用是否收集用户数据？</b></summary>

**A:** **绝对不会！** 
- ✅ 所有配置和聊天记录仅存储在本地设备
- ✅ API 请求直接发送到您配置的服务端点
- ✅ 不包含任何数据统计或追踪代码
- ✅ 完全开源，代码可审计
- ✅ 无需注册账号，无需联网激活
</details>

<details>
<summary><b>Q6: 如何更新应用？</b></summary>

**A:** 两种更新方式：
1. **应用内更新**：设置 → 关于 → 检查更新
2. **手动更新**：访问 [Releases](https://github.com/roseforljh/EveryTalk/releases) 页面下载最新 APK，直接覆盖安装

应用会自动保留您的所有配置和聊天记录。
</details>

<details>
<summary><b>Q7: 遇到 Cloudflare 拦截怎么办？</b></summary>

**A:** 应用内置智能降级机制：
1. 自动检测 Cloudflare 拦截
2. 无缝切换到直连模式（Gemini/OpenAI）
3. 保持对话连续性，用户无感知

如果直连也失败，建议：
- 更换 API 地址（使用反代或镜像站）
- 配置自己的后端服务
- 检查网络环境（VPN/代理设置）
</details>

<details>
<summary><b>Q8: 如何报告 Bug 或提出建议？</b></summary>

**A:** 欢迎通过以下方式反馈：
1. [GitHub Issues](https://github.com/roseforljh/EveryTalk/issues)：提交 Bug 报告或功能建议
2. 提供以下信息有助于快速定位问题：
   - 设备型号和 Android 版本
   - 应用版本号
   - 详细的复现步骤
   - 日志文件（设置 → 导出日志）
</details>

<details>
<summary><b>Q9: 语音对话功能如何使用？</b></summary>

**A:** 语音对话需要后端支持：
1. 确保后端已配置 STT/TTS 服务
2. 在聊天界面长按麦克风按钮开始录音
3. 松开按钮自动识别并获取 AI 回复
4. 支持多种音色选择（在设置中配置）
</details>

<details>
<summary><b>Q10: 如何优化图片上传速度？</b></summary>

**A:** 应用内置智能图片压缩：
1. 进入设置 → 图片压缩配置
2. 选择压缩模式：
   - **自动选择**：根据用途自动优化
   - **高质量**：最小压缩，保留细节
   - **平衡**：默认模式，兼顾质量与速度
   - **快速**：最大压缩，适合网络较慢时
   - **自定义**：手动设置分辨率和质量
3. 建议使用"平衡"或"快速"模式以提升上传速度
</details>

<details>
<summary><b>Q11: 如何在对话中使用数学公式？</b></summary>

**A:** 应用支持 LaTeX 数学公式渲染：

**内联公式**（行内显示）：
```
这是一个内联公式 $E = mc^2$，它会在文本中显示。
```

**块级公式**（独立行显示）：
```
这是一个块级公式：

$$
\int_{-\infty}^{\infty} e^{-x^2} dx = \sqrt{\pi}
$$
```

**支持的语法**：
- 基础运算：`$a + b$`, `$x^2$`, `$\frac{a}{b}$`
- 希腊字母：`$\alpha$`, `$\beta$`, `$\gamma$`
- 积分微分：`$\int$`, `$\sum$`, `$\frac{d}{dx}$`
- 矩阵：`$\begin{matrix} a & b \\ c & d \end{matrix}$`

**特性**：
- ✅ 离线渲染，无需网络
- ✅ 深浅色主题自动适配
- ✅ 流式渲染安全点检测（未闭合时缓冲，避免闪烁）
- ✅ 与代码块、表格等其他格式兼容

**示例对话**：
```
用户: 请解释二次方程公式
AI: 二次方程 $ax^2 + bx + c = 0$ 的求根公式为：

$$
x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
$$

其中 $\Delta = b^2 - 4ac$ 称为判别式。
```
</details>

<details>
<summary><b>Q12: AI流式输出结束后列表为什么会跳动？</b></summary>

**A:** 这是由于流式渲染切换导致的高度突变问题，已在最新版本中修复：

**问题原因**：
- 流式期间使用单一 `MarkdownRenderer`（紧凑布局）
- 完成后切换为分段渲染（`CodeBlock` + `TableRenderer`，包含工具条与更大padding）
- LazyColumn 项高度突变导致可视区域"向上跳"

**修复方案**：
1. **等高占位策略**：流式期间为含代码块/表格的消息添加与完成态一致的占位高度
2. **单次切换策略**：等待解析完成后一次性替换，避免中间态回退

**详细说明**：参见 [STREAMING_JUMP_FIX.md](STREAMING_JUMP_FIX.md)

**配置开关**（在 `PerformanceConfig.kt` 中）：
```kotlin
ENABLE_STREAMING_HEIGHT_PLACEHOLDER = true  // 启用等高占位
ENABLE_SINGLE_SWAP_RENDERING = true         // 启用单次切换
```

如需回退到旧逻辑，将上述开关设为 `false` 即可。
</details>

---

## 📄 开源协议

本项目采用 [MIT License](LICENSE.md) 开源协议。

这意味着您可以自由使用、修改和分发本软件,无论是商业还是非商业用途,但需要保留原始的版权和许可声明。

---

<p align="center">
  <strong>如果这个项目对您有帮助,请给我们一个 ⭐ Star!</strong>
</p>

<p align="center">
  Made with ❤️ by the EveryTalk Team
</p>
