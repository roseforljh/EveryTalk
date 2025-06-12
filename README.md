# KunTalk - Your Private, Versatile, and Powerful AI Companion

<p align="center">
  <img src="https://raw.githubusercontent.com/roseforljh/KunTalkwithAi/main/app1/app/src/main/res/mipmap-xxxhdpi/kztalk.webp" alt="KunTalk Logo" width="200"/>
</p>

<p align="center">
  <strong>一个高度可定制、功能强大的安卓AI聊天客户端，旨在提供极致的灵活性和流畅的对话体验。接入大模型api与之对话，ai写的程序，完全开源，想玩的随便改！</strong>
</p>

<p align="center">
  <a href="https://github.com/roseforljh/KunTalkwithAi/releases/tag/1.2.1"><img src="https://img.shields.io/github/v/release/roseforljh/KunTalkwithAi?style=for-the-badge" alt="GitHub release (latest by date)"></a>
  <a href="https://github.com/roseforljh/KunTalkwithAi/blob/main/LICENSE.md"><img src="https://img.shields.io/github/license/roseforljh/KunTalkwithAi?style=for-the-badge" alt="GitHub"></a>
  <a href="https://github.com/roseforljh/KunTalkwithAi/stargazers"><img src="https://img.shields.io/github/stars/roseforljh/KunTalkwithAi?style=for-the-badge" alt="GitHub stars"></a>
</p>

---

## 🌟 核心功能 (Features)

KunTalk 不仅仅是一个简单的聊天应用，它是一个集成了前沿技术的AI交互平台，为您带来桌面级的AI体验。

- **✨ 多模型/多平台支持**: 无缝切换不同的AI模型和服务商。您可以自由配置和接入任何兼容OpenAI格式的API，无论是本地部署的模型还是云端服务。
- **🌐 联网搜索**: 让AI不再局限于训练数据。开启联网模式，AI可以获取实时信息，为您提供更准确、更具时效性的回答，并附上参考来源。
- **🖼️ 多模态输入**: 交流不止于文字。您可以轻松上传图片，让AI理解和分析图像内容，实现更丰富的交互。
- **⚙️ 高度可定制**: 从API地址、模型名称到系统提示词（System Prompt），一切尽在您的掌控之中。您可以为不同的场景创建和保存多套配置，一键切换。
- **🚀 流畅的流式响应**: 体验如丝般顺滑的打字机效果，AI的每一个字都实时呈现在您眼前。我们对滚动逻辑进行了深度优化，确保用户的手动滚动操作永远拥有最高优先级，绝不被AI的自动滚动打断。
- **🎨 现代UI设计**: 采用最新的 Jetpack Compose 构建，界面简洁、美观，并提供了完善的浅色/深色模式支持。
- **🔍 思考过程可见**: 在AI生成答案时，您可以选择查看其“思考过程”或“Reasoning”，了解AI是如何一步步得到最终答案的。
- **🔒 隐私优先**: 所有的API配置和聊天记录都存储在您的本地设备上，我们不收集任何您的个人数据。

## 📱 截图展示 (Screenshots)

*这里是展示App核心界面的地方，建议您替换成自己的截图。*

| 主聊天界面 | 侧边栏/模型切换 |
| :---: | :---: |
| `在此处插入截图` | `在此处插入截图` |

## 🛠️ 技术栈 (Tech Stack)

本项目采用现代化的技术栈构建，确保了代码的高质量、可维护性和可扩展性。

- **客户端 (Android)**:
  - **语言**: [Kotlin](https://kotlinlang.org/)
  - **UI框架**: [Jetpack Compose](https://developer.android.com/jetpack/compose) - 用于构建声明式、响应式的原生UI。
  - **异步处理**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & [Flow](https://kotlinlang.org/docs/flow.html) - 用于处理网络请求、数据库操作等所有异步任务。
  - **网络请求**: [Retrofit](https://square.github.io/retrofit/) - 业界领先的类型安全的HTTP客户端。
  - **依赖注入**: [Hilt](https://developer.android.com/training/dependency-injection/hilt-android) - 简化依赖注入，提升代码模块化。
  - **数据持久化**: [SharedPreferences](https://developer.android.com/training/data-storage/shared-preferences) - 用于在本地存储用户配置。

- **后端 (代理)**:
  - 项目包含一个可选的Python后端代理，用于转换和适配不同的AI模型API。
  - **框架**: [FastAPI](https://fastapi.tiangolo.com/) - 高性能的现代Python Web框架。

## 🚀 快速开始 (Getting Started)

1.  前往 [**Releases**](https://github.com/roseforljh/KunTalkwithAi/releases/tag/1.2.1) 页面。
2.  下载最新版本的 `app-release.apk` 文件。
3.  在您的安卓设备上安装并运行。
4.  首次启动时，请在侧边栏的设置中配置您的AI模型API地址和密钥。

## 📄 许可证 (License)

本项目采用 [MIT License](https://github.com/roseforljh/KunTalkwithAi/blob/main/LICENSE.md) 开源许可证。