# EveryTalk - Next-Generation Intelligent Conversation Platform

<p align="center">
  <img src="logo01.png" alt="EveryTalk Logo" width="200"/>
</p>

<p align="center">
  <strong>🚀 Ultra-smooth · 🎨 Highly Customizable · 🔒 Privacy First · 🌐 All-in-one Multimodal</strong>
</p>

<p align="center">
  <strong>A revolutionary Android AI client integrating cutting-edge technology stacks, supporting any LLM.<br/>From text chat to image generation, voice interaction, and real-time web search, build your custom AI companion.</strong>
</p>

<p align="center">
  <strong>English</strong> | <a href="README_zh.md">中文</a>
</p>

<p align="center">
  <a href="https://github.com/roseforljh/EveryTalk/releases/latest"><img src="https://img.shields.io/github/v/release/roseforljh/EveryTalk?style=for-the-badge&label=release" alt="GitHub release (latest by date)"></a>
  <a href="https://github.com/roseforljh/EveryTalk/blob/main/LICENSE.md"><img src="https://img.shields.io/github/license/roseforljh/EveryTalk?style=for-the-badge" alt="GitHub"></a>
  <a href="https://github.com/roseforljh/EveryTalk/stargazers"><img src="https://img.shields.io/github/stars/roseforljh/EveryTalk?style=for-the-badge" alt="GitHub stars"></a>
  <a href="https://t.me/+EKxpszVkOBc1MGJl"><img src="https://img.shields.io/badge/Telegram-Join%20Group-blue?style=for-the-badge&logo=telegram" alt="Telegram Group"></a>
</p>

## 🧭 Project Status

- **Project Positioning**: A native Android AI client focusing on multi-model integration, real-time streaming, multimodal features, and local privacy.
- **Target Platform**: Android 8.1 and above (Android 10+ recommended); Development environment requires Android Studio + JDK 17.
- **Capabilities**: Covers text conversation, vision, image generation, live voice, LaTeX offline rendering, native web search, and OpenClaw remote control integration.
- **Connection Modes**: Supports direct upstream API calls (serverless), as well as routing through an optional proxy backend for image generation and extra endpoints.
- **Minimum Requirement**: First-time use requires at least one set of API endpoint, model name, and API key. Advanced capabilities depend on upstream models or backend support.

---

## 📌 Target Audience & Suitability

### Suitable For

- Personal users who want to unify multiple LLMs (such as OpenAI o1/o3-mini, Gemini 2.5/2.0, Claude 3.7 Sonnet, DeepSeek-R1) in a single Android app.
- Heavy AI users who require high-performance streaming, multimodal inputs, advanced image generation (Flux.1, Imagen 3, DALL-E 3), and voice conversations.
- Developers wanting to learn modern native Android AI applications using Kotlin and Jetpack Compose.
- Advanced users requiring remote server control via the OpenClaw / Bridge setup.

### Not Suitable For

- Users looking for a "ready-to-go" application with no API configurations or keys required.
- Users who only want the official client experience of a single model and do not need customization.
- Scenarios requiring native iOS, Web, or Desktop clients.
- Environments where all search, voice, and image capabilities must work completely offline.

---

## ⚠️ Prerequisites & Warnings

- The primary deliverable of this project is the **native Android client** located in `app1/`.
- The application supports a **serverless direct connection mode**, but not all models provide identical features (e.g., web search only works on models that support native search parameters).
- Features like image generation, voice interaction, backend proxies, and OpenClaw depend on whether your upstream models or server endpoints support them.
- "Supported" in this README means the client contains the integration pipeline, not that every model works with every config by default.

---

## 📚 Document Index

- **Project Overview**: Current `README.md` (English) / `README_zh.md` (中文).
- **OpenClaw Integration**: [`docs/openclaw-integration.md`](docs/openclaw-integration.md), detailing the setup of EveryTalk -> Bridge -> Gateway.
- **Design Logs**: `docs/plans/`, containing design documentation and historical architectural decisions.
- **Developer / Agent Docs**: `CLAUDE.md` and `AGENTS.md`, helpful for maintainers wanting to build and run tests.

---

## 🌟 Core Features

### 🎯 Intelligent Chat System
- **🤖 Full Model Compatibility**: Seamlessly connect to OpenAI (GPT-4o, o1, o3-mini), Google Gemini (Gemini 2.5, 2.0 Pro/Flash), Anthropic Claude (Claude 3.7 Sonnet, 3.5 Opus), Zhipu GLM, and local models via Ollama (Llama 3.3, Qwen 2.5, DeepSeek-R1).
- **⚡ Ultra-fast Streaming**: Millisecond-level stream parser for fluid typing animation. Features a pass-through stream controller that accumulates tokens and updates UI immediately.
- **🧠 Reasoning Chain Visualizer**: Real-time visualization of thinking processes for reasoning models like DeepSeek-R1, OpenAI o1, and o3-mini.
- **🌐 Deep Web Search**:
  - **Gemini**: Supports native Google Search tool call integration.
  - **Qwen**: Supports native search parameters.
  - **DeepSeek/OpenAI**: Displays search citations and results if supported by the endpoint.
- **🐍 Code Interpreter**:
  - **Gemini**: Detects computation/data execution tasks, runs Python code in a secure sandboxed environment, and renders output.
- **🔄 Auto Fallback**: Seamlessly switches to direct connection mode when Cloudflare challenges are detected.
- **📐 LaTeX Math Rendering**: Supports inline (`$...$`) and block (`$$...$$`) LaTeX formulas. Fully offline rendering with light/dark adaptive themes.

### 🎨 Multimodal Interactions
- **🖼️ Vision & Documents**:
  - **Image Recognition**: Support for GPT-4o, Gemini 2.0/2.5 Flash/Pro, Claude 3.7 Sonnet.
  - **Document Analysis**: Directly upload and chat with PDF, Word, and Excel files (local PDF parser included).
- **🎭 AI Image Generation**: Connects to Flux.1, Imagen 3, DALL-E 3, and other custom text-to-image endpoints.
- **🎙️ Live Voice Dialogue**:
  - **OpenAI**: High-quality TTS/STT combos or OpenAI Realtime audio protocols.
  - **Gemini**: Low-latency native multimodal audio flow input/output (Live Voice).
  - **Azure/Aliyun**: High-fidelity cloud text-to-speech engine integrations.
- **🎨 Image Compression & Editing**: Smart on-device image optimization before upload.

### ⚙️ Customizations & Controls
- **🎛️ Parameters Fine-tuning**: Fine-grained controls over Temperature, Top-P, Max Tokens, etc.
- **📝 System Prompts**: Create and switch custom AI personas for various roles.
- **🔧 Multi-Config Switcher**: Save multiple API profiles and switch models in one tap.
- **🎨 Preset Aspect Ratios**: Choose from 1:1, 16:9, 9:16 for image generation.
- **💾 History Manager**: Full conversation persistence and history search.
- **☁️ Telegram Releases**: Get automatically notified of new APK releases in the official channel.

### 🛡️ Security & Performance
- **🔒 On-Device Encryption**: Configuration and chat logs are stored locally with AES-256 encryption.
- **🔐 Message Signatures**: Optional HMAC-SHA256 signature protocol verification for backend API calls.
- **⚡ Performance Tuning**: Smart scroll controls (pauses autoscroll when user scrolls manually) and list view caching.

### 🎨 Modern UI/UX
- **🌓 Perfect Dark Mode**: Curated dark and light themes using Material Design 3.
- **✨ Smooth Motion**: Crossfades, spring animations, and tactile feedback.
- **🎯 Dynamic Sidebar**: Easily switch chats, access settings, and manage multiple profiles.

---

## 🚀 Quick Start

### 1. Download & Installation

1. Go to the project's [**Releases**](https://github.com/roseforljh/EveryTalk/releases/latest) page.
2. Download the latest `app-release.apk`.
3. Allow "Install from Unknown Sources" on your Android device and install the package.

### 1.1 CI/CD & Telegram Notifications

The project is configured with a automated pipeline (see [.github/workflows/build-artifacts.yml](.github/workflows/build-artifacts.yml)):
1. **Auto Release**: Merging a Release PR builds and signs the APK and AAB, uploading them directly to GitHub Releases.
2. **Telegram Broadcast**: A bot notifies the Telegram channel with release notes and download links instantly upon build completion.

### 2. Developer Configuration

Before building or running the project locally, set up your keys:

1. Copy `app1/local.properties.example` and rename it to `app1/local.properties`.
2. Populate the keys with your credentials:

```properties
# local.properties example configuration
GOOGLE_API_KEY="AIzaSy..."
GOOGLE_API_BASE_URL="https://generativelanguage.googleapis.com"
DEFAULT_OPENAI_API_BASE_URL="https://api.openai.com"
SILICONFLOW_API_KEY="sk-..."
# For more parameters, refer to local.properties.example
```

---

## 🛠️ Technical Architecture

### Frontend Technology Stack
```
┌─────────────────────────────────────────┐
│         Jetpack Compose UI              │
│     Material 3 · Responsive Layout      │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│       Kotlin Coroutines & Flow          │
│   Async Process · SSE Stream · State    │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│         Ktor Client Network             │
│   SSE Parser · Fallback · Direct Call   │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│      Local Data (Room Database)         │
│  Encrypted SharedPreferences · Files    │
└─────────────────────────────────────────┘
```

### Core Components
- **[`ApiClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/ApiClient.kt)**: Manages network calls, SSE deserialization, error recovery, and Cloudflare bypass.
- **[`StreamingOutputController.kt`](app1/app/src/main/java/com/android/everytalk/util/streaming/StreamingOutputController.kt)**: Pass-through streaming controller updating Compose states immediately.
- **[`GeminiDirectClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/GeminiDirectClient.kt)** / **[`OpenAIDirectClient.kt`](app1/app/src/main/java/com/android/everytalk/data/network/OpenAIDirectClient.kt)**: Handles serverless connections directly to Google/OpenAI.
- **[`VoiceChatSession.kt`](app1/app/src/main/java/com/android/everytalk/data/network/VoiceChatSession.kt)**: Manages continuous voice sessions (STT → Chat → TTS).

### System Requirements
| Component | Requirement |
|------|------|
| **Minimum SDK** | Android 8.1 (API 27) |
| **Recommended SDK** | Android 10+ (API 29+) |
| **IDE** | Android Studio Koala+ / Ladybug+ |
| **JDK** | JDK 17+ |
| **Backend** | Optional (Supports direct serverless mode) |

### Backend Service (Optional Proxy)
- **Open-source Proxy**: [backdAiTalk](https://github.com/roseforljh/backdAiTalk)
- **Functions**: API proxying, remote image generation, rate limiting.
- **Deployment**: Supports Docker containerized setup with multi-instance load balancing.

---

## 📁 Project Structure

```
EveryTalk/
├── app1/                                    # 📱 Android Client Module
│   ├── app/
│   │   ├── src/main/java/com/android/everytalk/
│   │   │   ├── config/                      # ⚙️ Configurations
│   │   │   ├── data/                        # 📊 Data Layer
│   │   │   │   ├── network/                 # 🌐 Network APIs
│   │   │   │   │   ├── ApiClient.kt         # Master Client
│   │   │   │   │   ├── AppStreamEvent.kt    # SSE Event Models
│   │   │   │   │   └── StreamEventParser.kt # Parser
│   │   │   │   └── DataClass/               # Room Entities
│   │   │   ├── statecontroller/             # 🎮 State Management
│   │   │   │   ├── AppViewModel.kt          # Main ViewModel
│   │   │   │   ├── facade/                  # UI State Facade
│   │   │   │   └── ViewModelStateHolder.kt  # State Holder
│   │   │   ├── ui/                          # 🎨 UI Screen Components
│   │   │   │   ├── screens/                 # Screens
│   │   │   │   └── components/              # UI Custom Components
│   │   │   └── util/                        # 🔧 Utilities
│   │   └── build.gradle.kts                 # Build Script
│   └── local.properties.example             # Template Properties
├── ET-Backend-code/                         # 🖥️ Optional Backend Proxy
└── README.md                                # 📖 Master English Document
```

---

## 🔌 API Protocols

### Chat Completions (Stream)

**Endpoint**: `POST /chat`

**Request Format**: `multipart/form-data`
```
├── chat_request_json: ChatRequest (JSON)
│   ├── model: string
│   ├── messages: Array<Message>
│   ├── useWebSearch: boolean
│   ├── showReasoning: boolean
│   └── generationConfig: {...}
└── uploaded_documents: File[] (Optional)
```

**Response Format**: Server-Sent Events (SSE)
```
data: {"type":"content","text":"Hello","output_type":null,"block_type":null}

data: {"type":"reasoning","text":"Let me think..."}

data: {"type":"web_search_results","results":[...]}

data: {"type":"finish","reason":"stop"}

data: [DONE]
```

**Event Types**:
- `content`: Incremental token.
- `content_final`: Completed message token.
- `reasoning`: Mind chain tokens (OpenAI o1/o3-mini, DeepSeek-R1).
- `reasoning_finish`: End of thinking step.
- `web_search_status`: Real-time web crawling status.
- `web_search_results`: Crawled search citations.
- `tool_call`: Tool invocations.
- `error`: Error logs.
- `finish`: Stream close trigger.

### Image Generations

**Endpoint**: `POST /v1/images/generations`

**Request Format**: `application/json`
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

**Response Format**:
```json
{
  "images": [
    {
      "url": "data:image/png;base64,..."
    }
  ]
}
```

---

## 🦞 OpenClaw Guide

If you wish to control your remote VPS instances running Claw via EveryTalk:

```bash
curl -fsSL https://claw.everytalk.cc | bash
```

See [OpenClaw Integration](docs/openclaw-integration.md) for full configuration steps.

---

## 🤝 Contributions

1.  **Fork** this repository.
2.  Create your feature branch (`git checkout -b feature/AmazingFeature`).
3.  Commit changes (`git commit -m 'Add some AmazingFeature'`).
4.  Push your branch (`git push origin feature/AmazingFeature`).
5.  **Create a Pull Request**.

Make sure to follow Kotlin style guides and cover your changes with tests.

---

## ❓ FAQ

<details>
<summary><b>Q1: Why can't I connect to my local Ollama server?</b></summary>

**A:** Check the following:
1. Ensure your Android device and computer are on the same Wi-Fi network.
2. Use your computer's local IP address (e.g., `192.168.1.100`), not `localhost` or `127.0.0.1`.
3. Check your PC's firewall settings for the Ollama port (usually `11434`).
4. Ensure Ollama is configured to listen on all interfaces by setting environment variable `OLLAMA_HOST=0.0.0.0`.
</details>

<details>
<summary><b>Q2: How does Web Search work?</b></summary>

**A:** The app routes web queries natively:
1. **Gemini**: Activates the native `google_search` tool call.
2. **Qwen**: Sends the query with native search parameters (`enable_search=true`).
3. **DeepSeek**: Triggers the search parameters.
Search results will render in citation cards. Tap cards to open the link directly in your browser.
</details>

<details>
<summary><b>Q3: What are the latest supported models?</b></summary>

**A:** EveryTalk supports all OpenAI-compatible APIs. Recommended latest models include:
- **Reasoning Models**: DeepSeek-R1, OpenAI o1, o3-mini.
- **Commercial Flagships**: Claude 3.7 Sonnet, Claude 3.5 Opus, Gemini 2.5 Pro/Flash, GPT-4o.
- **Open-source / Local**: Llama 3.3, Qwen 2.5 (deployed via Ollama, LM Studio, vLLM).
- **Image Generation**: Flux.1, Gemini Imagen 3, DALL-E 3.
</details>

<details>
<summary><b>Q4: Does the app collect user telemetry?</b></summary>

**A:** **No.** All settings, configurations, keys, and chat histories are saved locally in the SQLite Room Database with AES encryption. Telemetry SDKs are not included.
</details>

<details>
<summary><b>Q5: The message list jumps when the streaming finishes. How do I fix it?</b></summary>

**A:** This is caused by spacing variations between draft markdown and finished structured components (like code blocks and tables). We resolve this with two features: **streaming height placeholders** and **single-swap rendering**. You can tweak these options in `PerformanceConfig.kt`:
```kotlin
ENABLE_STREAMING_HEIGHT_PLACEHOLDER = true
ENABLE_SINGLE_SWAP_RENDERING = true
```
</details>

---

## 📄 License

This project is licensed under the [MIT License](LICENSE.md).

---

<p align="center">
  <strong>If this project helped you, please give us a ⭐ Star!</strong>
</p>

<p align="center">
  Made with ❤️ by the EveryTalk Team
</p>
