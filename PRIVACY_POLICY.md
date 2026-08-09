# EveryTalk Privacy Policy / EveryTalk 隐私政策

**Effective date / 生效日期: August 9, 2026 / 2026 年 8 月 9 日**

EveryTalk is an Android AI client maintained by the EveryTalk project maintainers. This policy explains how EveryTalk handles information when you use chat, image, voice, web search, Model Context Protocol (MCP), and AI content reporting features.

EveryTalk 是一款由 EveryTalk 项目维护者维护的 Android AI 客户端。本政策说明你使用聊天、图像、语音、联网搜索、模型上下文协议（MCP）及 AI 内容举报功能时，EveryTalk 如何处理信息。

Contact / 联系方式: [EveryTalk GitHub Issues](https://github.com/roseforljh/EveryTalk/issues)

---

## English

### 1. Information stored on your device

EveryTalk stores chat history, model service configurations, API keys, groups, pinned status, generated content references, and other app settings in the app-private storage on your device. EveryTalk disables Android system cloud backup and device-to-device transfer for this private app data.

Files you explicitly export or save through the Android system picker are stored at the location you choose and are managed by Android or the selected storage provider.

### 2. Information processed by network services

EveryTalk does not provide its own AI model. When you actively use an online feature, the app sends only the information needed to complete that request to the service you selected or configured:

| Feature | Information that may be transmitted | Recipient and purpose |
| --- | --- | --- |
| AI chat | Prompts, conversation context, system instructions, and selected attachments | Your selected AI provider, to generate a response |
| Image generation or editing | Prompts and images you select | Your selected image service, to generate or edit an image |
| Voice features | Audio you record and text required for speech processing | Your selected speech-to-text, AI, or text-to-speech provider |
| Web search and page reading | Search terms and URLs you request | The configured search or page-reading service |
| MCP tools | Tool arguments, relevant conversation context, and tool results | The MCP server you configure, to execute the requested tool |
| AI content reports | Report category, optional details, up to 4,000 characters from the relevant AI reply, image count, model and provider names, app version, identifiers, and timestamp | The configured EveryTalk report endpoint, to review reported AI content |

These services process information under their own terms and privacy policies. Do not connect EveryTalk to a service you do not trust or submit sensitive information to it.

### 3. Permissions

- **Internet:** Connects to AI, speech, image, search, MCP, and report services.
- **Microphone:** Captures audio only after you start a voice feature.
- **Camera:** Takes a photo only after you choose the camera option.
- **Bluetooth connection:** Supports compatible paired audio devices.

Optional permissions are requested when the related feature is used. Denying an optional permission does not prevent unrelated features from working.

### 4. Collection, sharing, and advertising

EveryTalk does not include advertising or analytics SDKs and does not sell personal information. Information leaves the device only when required by a feature you actively use, as described above. The selected or configured third-party provider may independently collect and process that information under its own privacy policy.

### 5. AI content safety and reports

EveryTalk locally blocks certain high-risk generation requests and passes safety instructions to supported model providers. AI replies and generated images include an in-app reporting option.

Reports are stored temporarily in app-private storage. When a valid HTTPS report endpoint is configured, pending reports are submitted to that endpoint. After successful submission, the locally stored reply excerpt and optional details are cleared, while a minimal receipt is retained to prevent duplicate reports. If no endpoint is configured, the report remains a local flag and the app informs you that the receiving service is unavailable. API keys and the full conversation are not included in a report.

### 6. Retention and deletion

Local data remains until you delete the related chat or configuration, clear app data, or uninstall EveryTalk. Exported files remain in the location you selected until you delete them there. Pending AI reports remain in app-private storage until they are successfully submitted, removed with app data, or deleted when the app is uninstalled.

### 7. Data security

EveryTalk blocks cleartext network traffic for production app connections and stores its database and configuration in app-private storage. Network security also depends on the provider, endpoint, network, and device you use. Keep API keys confidential and use HTTPS endpoints from providers you trust.

### 8. Accounts

EveryTalk does not create or manage an EveryTalk user account. Removing local data or uninstalling the app does not delete an account or data held independently by a third-party provider. Requests concerning a provider account must be directed to that provider.

### 9. Policy updates and contact

This policy may be updated when EveryTalk features or legal requirements change. The effective date at the top identifies the current version. Questions or privacy requests may be submitted through [EveryTalk GitHub Issues](https://github.com/roseforljh/EveryTalk/issues).

---

## 简体中文

### 1. 设备本地保存的信息

EveryTalk 会在设备的应用私有存储中保存聊天记录、模型服务配置、API 密钥、分组、置顶状态、生成内容引用及其他应用设置。EveryTalk 已关闭这些应用私有数据的 Android 系统云备份和设备间迁移。

你通过 Android 系统选择器主动导出或保存的文件，会存放在你选择的位置，并由 Android 或相应存储服务管理。

### 2. 网络服务处理的信息

EveryTalk 本身不提供 AI 模型。当你主动使用联网功能时，应用仅会把完成该次请求所需的信息发送给你选择或配置的服务：

| 功能 | 可能传输的信息 | 接收方与用途 |
| --- | --- | --- |
| AI 聊天 | 提示词、相关会话上下文、系统指令及你选择的附件 | 你选择的 AI 服务商，用于生成回复 |
| 图像生成或编辑 | 提示词及你选择的图片 | 你选择的图像服务，用于生成或编辑图片 |
| 语音功能 | 你录制的音频及语音处理所需文本 | 你选择的语音识别、AI 或语音合成服务商 |
| 联网搜索与网页读取 | 你提交的搜索词和网址 | 已配置的搜索或网页读取服务 |
| MCP 工具 | 工具参数、相关会话上下文及工具结果 | 你配置的 MCP 服务器，用于执行所请求的工具 |
| AI 内容举报 | 举报类别、可选补充说明、相关 AI 回复中最多 4,000 个字符、图片数量、模型和服务商名称、应用版本、标识符及时间戳 | 已配置的 EveryTalk 举报接收接口，用于审核被举报的 AI 内容 |

这些服务会依据各自的条款和隐私政策处理信息。请勿连接不受信任的服务，也不要向其提交敏感信息。

### 3. 权限用途

- **网络：** 连接 AI、语音、图像、搜索、MCP 及举报服务。
- **麦克风：** 仅在你主动启动语音功能后采集音频。
- **相机：** 仅在你选择相机功能后拍摄图片。
- **蓝牙连接：** 支持兼容的已配对音频设备。

可选权限会在使用相关功能时请求。拒绝可选权限不会影响无关功能。

### 4. 信息收集、共享与广告

EveryTalk 不包含广告或分析统计 SDK，也不出售个人信息。信息仅会在你主动使用相关功能时，按照上述说明离开设备。你选择或配置的第三方服务商可能依据其自身隐私政策独立收集和处理这些信息。

### 5. AI 内容安全与举报

EveryTalk 会在本地拦截部分高风险生成请求，并向支持的模型服务商传递安全约束。AI 回复和生成图片均提供应用内举报入口。

举报会暂存在应用私有存储中。配置有效的 HTTPS 举报接收接口后，待处理举报会提交至该接口。提交成功后，本地保存的回复片段和可选补充说明会被清除，仅保留用于防止重复举报的最小回执。未配置接收接口时，举报只会作为本地标记保存，应用会明确提示接收服务不可用。举报不会包含 API 密钥或整段会话。

### 6. 保存期限与删除

本地数据会保留到你删除相应聊天或配置、清除应用数据或卸载 EveryTalk。导出的文件会保留在你选择的位置，直到你从该位置删除。待处理 AI 举报会保留在应用私有存储中，直到成功提交、随应用数据被清除或因卸载应用而删除。

### 7. 数据安全

EveryTalk 会阻止生产版本应用连接使用明文网络流量，并将数据库和配置保存在应用私有存储中。网络安全同时取决于你使用的服务商、接口地址、网络和设备。请妥善保管 API 密钥，并仅使用可信服务商提供的 HTTPS 接口。

### 8. 账号

EveryTalk 不创建或管理 EveryTalk 用户账号。删除本地数据或卸载应用不会删除第三方服务商独立持有的账号或数据。涉及第三方服务商账号的请求，需要直接向相应服务商提出。

### 9. 政策更新与联系

本政策可能随 EveryTalk 功能或法律要求变化而更新，页面顶部的生效日期用于标识当前版本。隐私相关问题或请求可通过 [EveryTalk GitHub Issues](https://github.com/roseforljh/EveryTalk/issues) 提交。
