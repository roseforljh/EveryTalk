# EveryTalk Privacy Policy / EveryTalk 隐私政策

**Effective date / 生效日期: October 16, 2026 / 2026 年 10 月 16 日**

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

### 2A. Local mode and automatic account sync

You can choose LocalOnly mode and use the app without an account. In this mode, EveryTalk does not connect to its own server. Your locally entered API keys, chats, roles, prompts, and settings stay on the device. Requests to third party models are sent directly to the endpoints you configure when you use them, and those providers handle the requests under their own policies.

When a sync server is configured, signing in automatically merges local and account sessions, message text, reasoning, long term memories, pinned items, and conversation groups. There is no separate sync switch. Signing out stops account sync and keeps local copies. Signing back into the same account includes changes made in local mode. Switching to another account asks how to handle existing local data before uploading it. Account sync includes model/voice/search configurations and keys, MCP headers and authorization, remote computer settings and SSH credentials, Skill files and variable values, chat images and attachments, and complete text-chat history including tool traces and results. Credentials and complete history archives are encrypted with AES-GCM before upload. The server stores account recovery keys wrapped under a separate deployment key and can decrypt these synchronized secrets; this is not encryption that only you can unlock. New devices recover keys after account authentication and store credentials in their own Android Keystore. Image generation conversations, drafts, pending commands, port forwards and live connection state are excluded. External providers can revoke authorizations; restoring a credential does not prove that a remote connection is currently usable.

The sync service stores this data on servers in Germany. If you use the app in mainland China, signing in involves cross border transfer of the listed data. Encrypted server backups may remain for up to 7 days after deletion. The Android client is open source; server side permission, membership, and quota decisions are made by the server.

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

EveryTalk offers optional Google and email-code account sign-in through the configured Supabase Auth service. Email sign-in transmits the email address and verification code. Google sign-in requests basic identity information (openid, email, profile), not Gmail or Drive access. Supabase stores the account ID, email, linked login identities, registration and login records, and processes authentication requests under its privacy policy. Model API keys are included in account sync as encrypted fields, with account-managed recovery as described in section 2A.

Signing in automatically syncs only the fields described in section 2A. You can choose local mode to use the app without account sync.

The app encrypts session credentials using Android Keystore. Signing out clears the local account session and retains local chats and API configurations. Removing local data or uninstalling does not delete the server account. Contact the maintainers through the project page to request account deletion; do not publish verification codes or credentials in public issues. Separate third-party service accounts must be managed with their respective providers.

### 9. Google user data, Gmail MCP and Limited Use

#### Google user data and Gmail MCP

Google account sign-in and Gmail connection are separate features. Account sign-in uses only basic identity information (openid, email and profile). Gmail MCP accesses mail only after you separately connect Gmail and grant gmail.modify. Current features search and read messages, list labels, add or remove message labels, and send plain-text email at your request. There are no tools for creating drafts or permanently deleting mail.

To provide these features, the app may process your mailbox address, message and thread IDs, senders and recipients, subjects, bodies, message dates, labels, search queries and the content you request to send. Google identity information is used only for account authentication and display. Gmail data is used only for visible mail operations and related responses, not for unrelated purposes.

#### Processing path and third-party AI services

Gmail requests communicate with the Google Gmail API through the EveryTalk OAuth/MCP Worker hosted on Cloudflare. The Worker exchanges or refreshes tokens and processes mail operations during requests; its application code does not write tokens, mail bodies or user profiles to persistent storage. Google access and refresh tokens are encrypted on Android and are not sent as model prompts. When signed in, linked MCP authorization material is also synchronized as an encrypted field through account-managed recovery; the sync service can recover it as described in section 2A.

When you use AI for a Gmail task, mail information returned by tools may be sent as conversation context to your currently selected model service to interpret your request, display or summarize mail and generate related responses. If a proxy or aggregator provides the model, its operator and upstream model providers may also process that content. AI mail features therefore do not mean that mail always stays on your device.

Google data may be transferred to necessary processors only with user consent to provide the visible features described above. AI services and their upstream providers receiving Google data must follow the same Limited Use restrictions and must not use it for generalized model training. EveryTalk supports custom endpoints, but currently does not automatically verify every endpoint, plan or upstream provider's training or retention policy. Services whose compliance has not been verified must not receive Gmail data; user consent does not waive the prohibition on training.

#### Limited Use commitment and prohibited uses

EveryTalk's use and transfer to any other app of information received from Google APIs, including raw Gmail data and its aggregated, anonymized or derived forms, will adhere to the Google API Services User Data Policy and the Google Workspace API User Data and Developer Policy, including the Limited Use requirements. This section takes precedence over general third-party service disclosures in this policy; a third party's policy cannot expand the permitted uses of Google data.

EveryTalk does not use, sell or transfer Google user data to create, train or improve foundational or generalized AI or machine learning models, or for advertising, remarketing, data brokerage, credit assessment or lending decisions. Aggregation, anonymization, derived summaries and user authorization must not be used to bypass these restrictions.

Maintainers must not read Google user data unless you explicitly consent to access to specific data, it is necessary for a security investigation or legal compliance, or aggregated and anonymized data is used for internal operations as permitted by Google's policy. Legal or security processing is limited to what is necessary. When reporting an issue or requesting support, provide only content you agree to have reviewed; do not upload real mail or credentials in public issues.

#### Google data retention, deletion and revocation

Gmail tool results and related responses may be retained in local chat history, Agent execution records or summaries and may be supplied to the current model again with the relevant conversation. You can delete the relevant conversation and associated records; clearing app data or uninstalling removes data in private app storage. Exports and separately saved copies must be deleted at their respective locations. Retention and deletion of data already received by third parties depend on the actual service and plan and must comply with the Google data restrictions above; deleting local records alone cannot delete third-party copies.

Signing out of the Gmail connection deletes local Gmail authorization tokens; it does not automatically delete earlier chats or messages in your Google mailbox. You can also revoke EveryTalk access in your Google account's third-party connections settings to prevent further access. Signing out of the EveryTalk account and disconnecting Gmail are separate operations. Contact the maintainers through the project page for Google data privacy or deletion requests and obtain a private channel first; do not publicly submit mail bodies, passwords, verification codes or tokens.

[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy) · [Google Workspace API User Data and Developer Policy](https://developers.google.com/workspace/workspace-api-user-data-developer-policy) · [Google third-party connections / Google 第三方连接](https://myaccount.google.com/connections)

### 10. Policy updates and contact

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

### 2A. 本地模式与账号自动同步

你可以选择本地模式，不登录账号也能使用应用。本地模式不会连接 EveryTalk 自有服务器。你自己填写的 API 密钥、聊天记录、角色、提示词和设置会保存在设备上。使用第三方模型时，请求会直接发送到你配置的接口，具体由相应服务商按自己的政策处理。

配置同步服务器时，登录后会自动合并本地与账号的会话、消息文字、思考过程、长期记忆、置顶和分组，不需要另外开启同步。退出后停止账号同步并保留本机副本；重新登录同一账号时，会同步游客期间的新数据。换成另一个账号时，会先询问如何处理已有本地数据，再决定是否上传。账号同步包括模型、语音和搜索配置及 Key、MCP headers 和授权、远程电脑设置及 SSH 凭据、Skill 文件与变量值、聊天图片和附件，以及文字会话的完整正文、思考、工具轨迹和结果。凭据和完整历史归档会先用 AES-GCM 加密。服务端以独立部署密钥包裹账号恢复密钥，具有解密这些同步机密的能力，不是只有你能解密。新设备通过账号鉴权自动恢复，再存入该设备自己的 Android Keystore。生图对话、草稿、待执行命令、端口转发和实时连接状态不复制。第三方可撤销授权，恢复凭据不代表远程连接此刻可用。

同步数据存储在德国的服务器上。中国大陆用户登录后会涉及上述数据的跨境传输。服务器的加密备份在删除后最多保留 7 天。客户端代码开源，但具体权限、会员资格和额度由服务器判断。

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

EveryTalk 通过配置的 Supabase Auth 服务提供可选的 Google 和邮箱验证码登录。邮箱登录会发送邮箱地址和验证码；Google 登录只申请基本身份信息（openid、email、profile），不申请 Gmail 或网盘访问权限。Supabase 保存账号 ID、邮箱、关联登录身份、注册和登录记录，并依据其隐私政策处理认证请求。登录后，应用会按第 2A 节自动同步规定范围内的账号数据；选择本地模式则不进行账号同步。

应用通过 Android Keystore 加密保存会话凭据。退出登录会清除本地账号会话，保留聊天记录和 API 配置；删除本地数据或卸载应用不会删除服务端账号。可通过项目页面联系维护者提出账号删除申请，请勿在公开 Issue 中发布验证码或凭据。其他第三方服务账号仍需向相应服务商申请管理或删除。

### 9. Google 用户数据、Gmail MCP 与受限使用

#### Google 用户数据与 Gmail MCP

Google 账号登录与 Gmail 连接是两个独立功能。账号登录仅使用 openid、email 和 profile 基本身份信息；只有你单独连接 Gmail 并授予 gmail.modify 权限后，Gmail MCP 才能访问邮件。当前功能包括搜索和读取邮件、列出标签、给邮件添加或移除标签，以及按你的要求发送纯文本邮件；不提供创建草稿或永久删除邮件的工具。

为完成这些功能，应用可能处理邮箱地址、邮件和会话标识符、发件人与收件人、主题、正文、邮件时间、标签、搜索条件，以及你要求发送的内容。Google 身份信息仅用于账号认证和展示；Gmail 数据仅用于你可见的邮件操作及相关回答，不用于与这些功能无关的目的。

#### 处理路径与第三方 AI 服务

Gmail 请求通过部署在 Cloudflare 的 EveryTalk OAuth/MCP Worker 与 Google Gmail API 通信。Worker 使用授权凭据换取或刷新令牌，并在请求期间处理邮件操作；其应用代码不将令牌、邮件正文或用户资料写入持久存储。Google 访问令牌和刷新令牌在 Android 端加密保存，不作为模型提示词发送。登录后，关联的 MCP 授权材料也会作为加密字段同步，账号同步服务可按第 2A 节恢复这些凭据。

当你使用 AI 执行 Gmail 任务时，工具返回的邮件信息可能作为会话上下文发送到当前选择的模型服务，用于理解请求、展示或总结邮件及生成相关回答。若模型经代理或聚合平台提供，网关运营方和上游模型服务商也可能处理这些内容。因此，使用 AI 邮件功能并不意味着邮件始终只在设备本地处理。

Google 数据只能在用户同意的范围内，为提供上述可见功能而传输给必要的处理方。接收 Google 数据的 AI 服务及其上游必须遵守同样的受限使用要求，不得将这些数据用于通用模型训练。EveryTalk 支持用户自定义接口，但当前不会自动核验每个接口、套餐和上游服务的训练或保存政策；未经核实符合这些要求的服务不得接收 Gmail 数据，用户同意也不能豁免禁止训练的限制。

#### 受限使用承诺与禁止用途

EveryTalk 对通过 Google API 获得的信息（包括 Gmail 原始数据及其汇总、匿名化或派生数据）的使用和向其他应用的传输，将遵守 Google API Services User Data Policy 和 Google Workspace API User Data and Developer Policy，包括 Limited Use（受限使用）要求。本节优先于本政策中一般性的第三方服务说明；第三方政策不能扩大 Google 数据的允许用途。

EveryTalk 不使用、出售或传输 Google 用户数据来创建、训练或改进基础模型或通用 AI/机器学习模型，不将其用于广告投放、再营销、数据经纪、信用评估或借贷决策。不得通过汇总、匿名化、派生摘要或用户授权规避这些限制。

维护者不得人工阅读 Google 用户数据，除非获得你对具体数据的明确同意、为必要的安全调查或遵守法律，或在 Google 政策允许的范围内使用汇总匿名数据进行内部运营。需要法律或安全目的的数据处理仅限必要范围。主动举报或寻求支持时，请仅提供你同意审核的内容，不要在公开 Issue 中上传真实邮件或凭据。

#### Google 数据的保存、删除与撤销授权

Gmail 工具结果及相关回答可能保存在设备的聊天记录、Agent 执行记录或摘要中，并可能随相关会话再次提供给当前模型。可删除相关会话与关联记录；清除应用数据或卸载会移除应用私有存储中的数据。导出文件和另行保存的副本需要在各自位置删除。第三方已经收到的数据的保存期限和删除方式取决于实际服务及套餐，必须符合上述 Google 数据限制；EveryTalk 不能仅通过删除本地记录来删除第三方副本。

在应用中退出 Gmail 连接会删除本地 Gmail 授权令牌，不会自动删除此前的聊天记录，也不会删除 Google 邮箱中的邮件。还可在 Google 账号的第三方连接设置中撤销 EveryTalk 的访问权限，以阻止后续访问。普通 EveryTalk 账号退出与 Gmail 断开连接是独立操作。Google 数据隐私或删除请求可通过项目页面联系维护者，请先获取私密联系渠道，不要公开提交邮件正文、密码、验证码或令牌。

EveryTalk's use and transfer to any other app of information received from Google APIs will adhere to the Google API Services User Data Policy, including the Limited Use requirements.

[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy) · [Google Workspace API User Data and Developer Policy](https://developers.google.com/workspace/workspace-api-user-data-developer-policy) · [Google third-party connections / Google 第三方连接](https://myaccount.google.com/connections)

### 10. 政策更新与联系

本政策可能随 EveryTalk 功能或法律要求变化而更新，页面顶部的生效日期用于标识当前版本。隐私相关问题或请求可通过 [EveryTalk GitHub Issues](https://github.com/roseforljh/EveryTalk/issues) 提交。
