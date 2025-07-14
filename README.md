# KunTalk - Your Private, Versatile, and Powerful AI Companion

<p align="center">
  <img src="https://raw.githubusercontent.com/roseforljh/KunTalkwithAi/main/app1/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" alt="KunTalk Logo" width="200"/>
</p>

<p align="center">
  <strong>一个高度可定制、功能强大的安卓AI聊天客户端，旨在提供极致的灵活性和流畅的对话体验。接入大模型api与之对话，ai写的程序，完全开源，想玩的随便改！</strong>
</p>

<p align="center">
  <a href="https://github.com/roseforljh/KunTalkwithAi/releases/latest"><img src="https://img.shields.io/github/v/release/roseforljh/KunTalkwithAi?style=for-the-badge&label=release" alt="GitHub release (latest by date)"></a>
  <a href="https://github.com/roseforljh/KunTalkwithAi/blob/main/LICENSE.md"><img src="https://img.shields.io/github/license/roseforljh/KunTalkwithAi?style=for-the-badge" alt="GitHub"></a>
  <a href="https://github.com/roseforljh/KunTalkwithAi/stargazers"><img src="https://img.shields.io/github/stars/roseforljh/KunTalkwithAi?style=for-the-badge" alt="GitHub stars"></a>
</p>

---

## 目录 (Table of Contents)

- [🌟 核心功能](#-核心功能-features)
- [📱 截图展示](#-截图展示-screenshots)
- [🛠️ 技术栈](#️-技术栈-tech-stack)
- [🚀 安装与使用指南](#-安装与使用指南-installation--usage-guide)
- [👨‍💻 面向开发者](#-面向开发者-for-developers)
- [📁 项目结构](#-项目结构-project-structure)
- [🤝 贡献指南](#-贡献指南-contributing)
- [❓ 常见问题](#-常见问题-faq)
- [📄 许可证](#-许可证-license)
- [🙏 致谢](#-致谢-acknowledgments)

---

## 🌟 核心功能 (Features)

KunTalk 不仅仅是一个简单的聊天应用，它是一个集成了前沿技术的 AI 交互平台，为您带来桌面级的 AI 体验。

- **✨ 多模型/多平台支持**: 无缝切换不同的 AI 模型和服务商。您可以自由配置和接入任何兼容 OpenAI 格式的 API，无论是本地部署的模型还是云端服务。
- **🌐 联网搜索**: 让 AI 不再局限于训练数据。开启联网模式，AI 可以获取实时信息，为您提供更准确、更具时效性的回答，并附上参考来源。
- **🖼️ 多模态输入**: 交流不止于文字。您可以轻松上传图片，让 AI 理解和分析图像内容，实现更丰富的交互。
- **⚙️ 高度可定制**: 从 API 地址、模型名称到系统提示词（System Prompt），一切尽在您的掌控之中。您可以为不同的场景创建和保存多套配置，一键切换。
- **🚀 流畅的流式响应**: 体验如丝般顺滑的打字机效果，AI 的每一个字都实时呈现在您眼前。我们对滚动逻辑进行了深度优化，确保用户的手动滚动操作永远拥有最高优先级，绝不被 AI 的自动滚动打断。
- **🎨 现代 UI 设计**: 采用最新的 Jetpack Compose 构建，界面简洁、美观，并提供了完善的浅色/深色模式支持。
- **🔍 思考过程可见**: 在 AI 生成答案时，您可以选择查看其“思考过程”或“Reasoning”，了解 AI 是如何一步步得到最终答案的。
- **🔒 隐私优先**: 所有的 API 配置和聊天记录都存储在您的本地设备上，我们不收集任何您的个人数据。

## 📱 截图展示 (Screenshots)

_这里是展示 App 核心界面的地方，建议您替换成自己的截图。_

|                                 主聊天界面                                 |                                 侧边栏/模型切换                                 |
| :------------------------------------------------------------------------: | :-----------------------------------------------------------------------------: |
| <img src="imgs/微信图片_20250613010959.jpg" alt="主聊天界面" width="300"/> | <img src="imgs/微信图片_20250613011037.jpg" alt="侧边栏/模型切换" width="300"/> |

## 🛠️ 技术栈 (Tech Stack)

本项目采用现代化的技术栈构建，确保了代码的高质量、可维护性和可扩展性。

- **客户端 (Android)**:

  - **语言**: [Kotlin](https://kotlinlang.org/)
  - **UI 框架**: [Jetpack Compose](https://developer.android.com/jetpack/compose) - 用于构建声明式、响应式的原生 UI。
  - **异步处理**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & [Flow](https://kotlinlang.org/docs/flow.html) - 用于处理网络请求、数据库操作等所有异步任务。
  - **网络请求**: [Retrofit](https://square.github.io/retrofit/) - 业界领先的类型安全的 HTTP 客户端。
  - **依赖注入**: [Hilt](https://developer.android.com/training/dependency-injection/hilt-android) - 简化依赖注入，提升代码模块化。
  - **数据持久化**: [SharedPreferences](https://developer.android.com/training/data-storage/shared-preferences) - 用于在本地存储用户配置。

- **后端 (代理)**:
  - 项目包含一个可选的 Python 后端代理，用于转换和适配不同的 AI 模型 API。
  - **框架**: [FastAPI](https://fastapi.tiangolo.com/) - 高性能的现代 Python Web 框架。

## 🚀 安装与使用指南 (Installation & Usage Guide)

### 1. 快速安装

对于大多数用户，我们推荐直接安装预编译的 APK 包：

1.  前往项目的 [**Releases**](https://github.com/roseforljh/KunTalkwithAi/releases/latest) 页面。
2.  下载最新版本的 `app-release.apk` 文件。
3.  在您的安卓设备上允许“安装未知来源的应用”，然后点击下载的 APK 文件进行安装。

### 2. 配置 API

首次启动应用后，您需要配置连接 AI 模型的 API。

1.  从主屏幕左侧边缘向右滑动，或点击左上角的菜单按钮，打开侧边栏。
2.  在侧边栏中，您会看到默认的配置项。点击下方的 **“添加一套新配置”** 来创建您的专属连接。
3.  在配置页面，您需要填写以下信息：
    - **配置名称**: 给这个配置起一个容易识别的名字 (例如: "我的本地模型")。
    - **API 地址**: 您的 AI 服务 API 端点。
      - **⚠️ 重要：URL格式说明**
        API地址末尾是否带有 `#` 符号，将决定应用的连接方式：
        - **地址末尾带 `#`** (例如: `http://192.168.1.10:8000/v1/chat/completions#`)
          这会告诉App：“这是一个完整的、可以直接使用的URL”。应用将直接向这个地址发起请求，不会做任何修改。**这是连接到您自己的本地模型或第三方服务的标准方式。**
        - **地址末尾不带 `#`** (例如: `http://some.address/`)
          在这种模式下，应用会 **忽略** 您填写的地址，转而尝试连接到一个内置的、由项目维护的公共代理服务器列表。这主要用于演示或备用，通常不推荐日常使用。
    - **API 密钥**: 访问服务所需的密钥 (如果不需要，可以留空)。
    - **模型名称**: 您想要使用的具体模型 ID (例如: `gpt-4`, `llama3-70b-8192`)。
    - **系统提示词 (System Prompt)**: 可选。用于设定 AI 的角色和行为。
4.  点击“保存”，您的新配置就会出现在侧边栏列表中。点击即可切换。

### 3. 开始聊天

1.  在侧边栏选择您想使用的配置。
2.  返回主聊天界面，在底部的输入框中输入您的问题。
3.  点击右侧的发送按钮。
4.  您可以通过输入框上方的开关来启用 **联网搜索** 或查看 **思考过程**。
5.  点击输入框左侧的 “+” 号，您可以 **上传图片** 与 AI 进行多模态对话。

## 👨‍💻 面向开发者 (For Developers)

### 从源码构建 (Build from Source)

如果您想自行修改代码或体验最新功能，可以从源码构建应用：

1.  克隆本仓库: `git clone https://github.com/roseforljh/KunTalkwithAi.git`
2.  使用 Android Studio 打开 `KunTalkwithAi/app1` 目录。
3.  等待 Gradle 同步和构建完成。
4.  连接您的安卓设备或使用模拟器，点击 "Run 'app'"。

### 运行后端代理 (可选)

本项目包含一个位于 `backend` 目录下的 Python 后端代理，基于 FastAPI。它的主要作用是：

- 将不兼容 OpenAI 格式的 API（如 Google Gemini）转换为 OpenAI 格式。
- 为前端提供一个统一、稳定的 API 入口。

**如何运行:**

```bash
# 1. 进入后端目录
cd backend

# 2. (推荐) 创建并激活虚拟环境
python -m venv venv
# On Windows: venv\Scripts\activate
# On macOS/Linux: source venv/bin/activate

# 3. 安装依赖
pip install -r requirements.txt

# 4. 启动服务 (使用uvicorn)
uvicorn eztalk_proxy.main:app --host 0.0.0.0 --port 8000
```

启动后，您可以在 KunTalk 客户端中将 API 地址设置为 `http://<您的电脑IP>:8000/v1/chat/completions`。

## 📁 项目结构 (Project Structure)

````
KunTalkwithAi/
├── app1/              # Android 客户端源码 (使用 Android Studio 打开此目录)
├── backend/           # Python 后端代理源码
│   ├── eztalk_proxy/  # FastAPI 应用核心逻辑
│   └── ...
├── imgs/              # README中使用的图片资源
├── .github/           # GitHub Actions 工作流配置
└── README.md          # 就是你正在看的这个文件```

## 🤝 贡献指南 (Contributing)

我们非常欢迎社区的贡献！如果您有任何好的想法或发现了Bug，请通过以下方式参与进来：

- **报告Bug**: 通过 [GitHub Issues](https://github.com/roseforljh/KunTalkwithAi/issues) 提交您发现的问题。请尽可能详细地描述问题和复现步骤。
- **功能建议**: 有很酷的功能想加入？同样可以通过 [GitHub Issues](https://github.com/roseforljh/KunTalkwithAi/issues) 来告诉我们。
- **提交代码**:
  1. Fork 本仓库。
  2. 创建您的特性分支 (`git checkout -b feature/AmazingFeature`)。
  3. 提交您的更改 (`git commit -m 'Add some AmazingFeature'`)。
  4. 推送到分支 (`git push origin feature/AmazingFeature`)。
  5. 打开一个 Pull Request。

## ❓ 常见问题 (FAQ)

**Q: 为什么我无法连接到我的本地模型服务？**
**A:** 请检查以下几点：
1.  确保您的手机和运行本地模型的电脑在同一个局域网 (Wi-Fi) 内。
2.  在KunTalk的API地址中，使用了您电脑的正确局域网IP地址 (例如 `192.168.1.x`)，而不是 `localhost` 或 `127.0.0.1`。
3.  检查您电脑的防火墙设置，确保端口（如8000）是开放的，允许来自局域网的连接。
4.  确保您的本地模型服务已经成功运行，并且监听的是 `0.0.0.0` 而不是 `127.0.0.1`，这样才能接受外部连接。

**Q: 联网搜索功能是如何实现的？**
**A:** 联网搜索功能由后端代理实现。当检测到需要联网时，后端会使用搜索引擎（如 DuckDuckGo）进行搜索，将搜索结果整合后作为上下文信息提供给AI模型，最终生成有时效性的回答。

**Q: 我可以添加其他模型（比如 Google Gemini）的支持吗？**
**A:** 当然可以！后端代理 (`backend`) 的设计初衷就是为了适配不同的API。您可以在 `backend/eztalk_proxy/api/` 目录下参考 `gemini.py` 的实现，编写一个新的转换器来适配您想接入的模型API，然后将其整合到 `chat.py` 的路由中。

## 🙏 致谢 (Acknowledgments)

- 感谢所有为本项目提供灵感和支持的开源社区。
- 特别感谢以下项目，它们是KunTalk构建的基石：
  - [Jetpack Compose](https://developer.android.com/jetpack/compose)
  - [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
  - [Retrofit](https://square.github.io/retrofit/)
  - [FastAPI](https://fastapi.tiangolo.com/)

## 📄 许可证 (License)

本项目采用 [MIT License](https://github.com/roseforljh/KunTalkwithAi/blob/main/LICENSE.md) 开源许可证。
````
