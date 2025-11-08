# EveryTalk - 您的私密、多功能、强大的 AI 伴侣

<p align="center">
  <img src="https://qone.kuz7.com/uploads/images/2025/11/09/28553c13-29b3-4ab4-87a8-e72ad4be8568.png" alt="EveryTalk Logo" width="200"/>
</p>

<p align="center">
  <strong>一个高度可定制、功能强大的 Android AI 聊天客户端,提供极致的灵活性和流畅的对话体验。支持接入任意大模型 API,完全开源!</strong>
</p>

<p align="center">
  <a href="https://github.com/roseforljh/EveryTalk/releases/latest"><img src="https://img.shields.io/github/v/release/roseforljh/EveryTalk?style=for-the-badge&label=release" alt="GitHub release (latest by date)"></a>
  <a href="https://github.com/roseforljh/EveryTalk/blob/main/LICENSE.md"><img src="https://img.shields.io/github/license/roseforljh/EveryTalk?style=for-the-badge" alt="GitHub"></a>
  <a href="https://github.com/roseforljh/EveryTalk/stargazers"><img src="https://img.shields.io/github/stars/roseforljh/EveryTalk?style=for-the-badge" alt="GitHub stars"></a>
</p>

---

## 目录

- [EveryTalk - 您的私密、多功能、强大的 AI 伴侣](#everytalk---您的私密多功能强大的-ai-伴侣)
  - [目录](#目录)
  - [🌟 核心亮点](#-核心亮点)
  - [📱 应用截图](#-应用截图)
    - [夜间模式](#夜间模式)
    - [浅色模式](#浅色模式)
  - [🚀 快速开始](#-快速开始)
    - [1. 下载安装](#1-下载安装)
    - [2. 配置模型](#2-配置模型)
    - [3. 开始对话](#3-开始对话)
  - [👨‍💻 开发者指南](#-开发者指南)
    - [从源码构建](#从源码构建)
    - [开发者配置](#开发者配置)
  - [🛠️ 技术栈与系统要求](#️-技术栈与系统要求)
  - [📁 项目结构](#-项目结构)
  - [🔌 后端接口协议](#-后端接口协议)
    - [模式说明](#模式说明)
    - [聊天流式接口](#聊天流式接口)
    - [图像生成接口](#图像生成接口)
    - [模型列表获取](#模型列表获取)
    - [安全与部署建议](#安全与部署建议)
  - [🤝 贡献代码](#-贡献代码)
  - [❓ 常见问题](#-常见问题)
  - [📄 开源协议](#-开源协议)

---

## 🌟 核心亮点

EveryTalk 不仅仅是一个聊天应用,它是一个集成了前沿技术的全能型 AI 交互平台。

- **✨ 无限模型支持**: 轻松接入并切换任意 AI 模型,无论是 OpenAI、Gemini、Claude,还是您本地部署的开源模型。
- **🌐 实时联网搜索**: 让 AI 掌握最新资讯,提供更精准、更具时效性的回答,并附上信息来源。
- **🖼️ 强大图文多模态**: 不止于文本,更能理解和分析图像,实现看图对话、图像生成等丰富交互。
- **⚙️ 深度参数定制**: 从 API 地址到系统提示词,一切尽在掌控。为不同场景创建和保存多套配置,一键切换。
- **🚀 极致流畅体验**: 如丝般顺滑的流式响应,每一个字都实时呈现。深度优化的滚动逻辑,确保手动操作永远优先。
- **🎨 现代美学设计**: 基于 Jetpack Compose 构建,界面简洁美观,并提供完善的浅色/深色模式。
- **🔍 思考过程全透明**: 可选的"Reasoning"模式,让您了解 AI 是如何一步步推导出最终答案的。
- **🔒 绝对隐私安全**: 所有配置和聊天记录均存储于本地设备,我们不收集任何个人数据,完全开源可审计。

## 📱 应用截图

### 夜间模式

|                                 主聊天界面                                 |                                 侧边栏/模型切换                                 |                                 设置界面                                 |
| :------------------------------------------------------------------------: | :-----------------------------------------------------------------------------: | :------------------------------------------------------------------------: |
| <img src="https://qone.kuz7.com/uploads/images/2025/10/02/e901a896-323a-4f27-9ee2-1c2919d2ef7a.png" alt="主聊天界面" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/10/01/7c4a347f-7104-40a5-8c49-e321b03ca447.png" alt="侧边栏/模型切换" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/10/01/284aecfe-dba9-49f6-9abb-10a73c889155.png" alt="设置界面" width="280"/> |

|                                 图像生成界面                                 |                                 关于                                 |
| :------------------------------------------------------------------------: | :-----------------------------------------------------------------------------: |
| <img src="https://qone.kuz7.com/uploads/images/2025/10/01/7f1327b6-2242-4cda-8ee7-7ecf4ea06212.png" alt="图像生成界面" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/10/01/cc3e08e2-3a60-4ab9-9b2d-0e8c01765bb7.png" alt="关于" width="280"/> |

### 浅色模式

|                                 主聊天界面                                 |                                 侧边栏/模型切换                                 |                                 设置界面                                 |
| :------------------------------------------------------------------------: | :-----------------------------------------------------------------------------: | :------------------------------------------------------------------------: |
| <img src="https://qone.kuz7.com/uploads/images/2025/10/01/9473c866-0de7-4b2d-b0ed-3181e4e40669.png" alt="主聊天界面" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/10/01/36c25d31-8a61-4eca-9f4a-f28e3f6b7e0d.png" alt="侧边栏/模型切换" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/10/01/9fa3097c-9bab-49b0-8a70-ce37d8d4f224.png" alt="设置界面" width="280"/> |

|                                 图像生成界面                                 |                                 关于                                 |
| :------------------------------------------------------------------------: | :-----------------------------------------------------------------------------: |
| <img src="https://qone.kuz7.com/uploads/images/2025/10/01/ed57cda3-3269-48d9-b827-24131c59c478.png" alt="图像生成界面" width="280"/> | <img src="https://qone.kuz7.com/uploads/images/2025/10/01/1f3917fe-e0dd-43b4-b5db-6e8d5bddd522.png" alt="关于" width="280"/> |

---

## 🚀 快速开始

### 1. 下载安装

1.  前往项目的 [**Releases**](https://github.com/roseforljh/EveryTalk/releases/latest) 页面。
2.  下载最新版本的 `app-release.apk` 文件。
3.  在您的安卓设备上允许"安装未知来源的应用",然后点击 APK 文件进行安装。

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

> **提示**: 应用依赖一个后端代理来处理聊天和图像生成请求。如果您未配置后端,这些功能将不可用。模型列表则可直接从您填写的 API 地址获取。

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

## 🛠️ 技术栈与系统要求

- **客户端 (Android)**:
  - **语言**: [Kotlin](https://kotlinlang.org/)
  - **UI 框架**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
  - **异步处理**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & [Flow](https://kotlinlang.org/docs/flow.html)
  - **网络层**: [Ktor Client](https://ktor.io/docs/client.html)
  - **数据持久化**: SharedPreferences

- **后端 (代理)**:
  - 聊天与图像生成依赖后端代理,请在构建时配置 `BACKEND_URLS`。
  - 开源后端仓库: [backdAiTalk](https://github.com/roseforljh/backdAiTalk)

- **运行环境**:
  - 最低支持 Android 8.1 (API 27),推荐 Android 10+。

- **开发环境**:
  - **IDE**: Android Studio Koala+
  - **JDK**: 17+

## 📁 项目结构

```
EveryTalk/
├── app1/                              # Android 客户端源码
│   ├── app/                           # 主应用模块
│   │   ├── src/main/java/com/android/everytalk/
│   │   │   ├── config/                # 配置管理
│   │   │   ├── data/                  # 数据层 (网络, 数据模型)
│   │   │   ├── statecontroller/       # 状态与视图模型
│   │   │   ├── ui/                    # UI 层 (Compose UI)
│   │   │   └── util/                  # 工具类
│   │   └── build.gradle.kts           # 模块构建配置
│   └── build.gradle.kts               # 项目级构建配置
├── ET-Backend-code/                   # 后端代理服务 (可选)
└── README.md                          # 项目文档
```

## 🔌 后端接口协议

下面描述客户端与后端代理之间的通信协议。

- **客户端实现**: [`ApiClient.kt`](EveryTalk/app1/app/src/main/java/com/android/everytalk/data/network/ApiClient.kt)
- **后端参考**: [backdAiTalk](https://github.com/roseforljh/backdAiTalk)

### 模式说明

- **文本模式 (聊天)**:
  - **请求**: `multipart/form-data`,包含 ChatRequest JSON 与附件。
  - **响应**: `text/event-stream`,逐行返回 JSON 事件,以 `[DONE]` 终止。
  - **特点**: 支持流式显示、多模态输入,协议归一化。

- **图像模式 (生成/编辑)**:
  - **请求**: JSON 直达后端图像生成路由。
  - **响应**: 统一的 JSON 格式,包含 `images` 列表。
  - **特点**: 支持文生图、图文编辑,自动适配多种上游接口。

### 聊天流式接口

- **方法**: `POST`
- **路径**: `/chat` (建议 `BACKEND_URLS` 指向该路由)
- **请求体**: `multipart/form-data`,包含 `json` 字段 (ChatRequest) 和 `files` 字段 (附件)。
- **响应**: Server-Sent Events (SSE),以 `data: [DONE]` 结束。
- **API 地址解析**: 客户端会根据地址格式(是否包含 `#` 或 `/`)自动拼接 OpenAI 兼容的路径,确保灵活性。

### 图像生成接口

- **方法**: `POST`
- **路径**: `/v1/images/generations` 或 `/chat/v1/images/generations`
- **请求体**: JSON 格式,包含模型、提示词、尺寸、上游 API 地址等关键信息。
- **响应**: 统一的 JSON 格式: `{ images: [{ url }...], ... }`
- **上游适配**: 后端可自动归一化多种上游接口格式,包括 OpenAI DALL·E、Gemini Image API 等。

### 模型列表获取

- 客户端会直接请求您在 UI 中填写的 API 地址的 `/v1/models` 端点,用于快速获取可用模型列表。

### 安全与部署建议

- **安全**: 部署在受控环境,使用 HTTPS,开启访问控制,避免明文传输密钥。
- **部署**: 推荐使用 Docker 容器化部署,并配置 Nginx 等反向代理。

---

## 🤝 贡献代码

我们非常欢迎社区的贡献!无论是报告 Bug、提出功能建议,还是提交代码,都是对项目的巨大支持。

1.  **Fork** 本仓库。
2.  创建您的特性分支 (`git checkout -b feature/AmazingFeature`)。
3.  提交您的修改 (`git commit -m 'Add some AmazingFeature'`)。
4.  推送至您的分支 (`git push origin feature/AmazingFeature`)。
5.  **创建 Pull Request**。

请确保遵循 Kotlin 官方编码规范,并为您的代码添加必要的注释和测试。

---

## ❓ 常见问题

**Q1: 为什么无法连接到本地模型服务?**
**A:** 请确保手机与电脑在同一局域网,使用电脑的局域网 IP (而非 `localhost`),并检查防火墙设置。

**Q2: 联网搜索是如何实现的?**
**A:** 由后端代理调用搜索引擎 API,整合搜索结果后提供给 AI 模型,以生成有时效性的回答。

**Q3: 支持哪些 AI 模型?**
**A:** 支持任何兼容 OpenAI API 格式的模型,包括 OpenAI, Google, Anthropic, 以及通过 Ollama 等部署的各类开源模型。

**Q4: 如何配置后端代理?**
**A:** 克隆 [backdAiTalk](https://github.com/roseforljh/backdAiTalk) 仓库,按照其 README 配置并运行,然后在应用的 `local.properties` 中填入后端 URL。

**Q5: 应用是否收集用户数据?**
**A:** **完全不会**。所有配置和聊天记录仅存储在本地,API 请求直接发送到您配置的服务端点,代码完全开源可审计。

**Q6: 如何更新应用?**
**A:** 关注 [Releases](https://github.com/roseforljh/EveryTalk/releases) 页面获取最新版 APK,直接覆盖安装即可。

**Q7: 遇到崩溃或异常怎么办?**
**A:** 请通过 [GitHub Issues](https://github.com/roseforljh/EveryTalk/issues) 提交问题,并附上设备信息、复现步骤和日志(可在设置中导出)。

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
