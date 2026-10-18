# EveryTalk Web 实施与验收状态

更新时间：2026-10-17。本文区分本地实现、正式站版本和真实账号验收，不能把接口返回 401 当作完整功能已上线。

## 当前结果

代码位于 `C:/Users/33039/Desktop/EveryTalk/web/`，基于现有官网源码继续开发，保留 Next.js、React、Tailwind 及官网页面；`/chat` 使用参考 QoneAgent 的侧栏、中央对话、设置面板及移动端布局。官网基线来自 `roseforljh/Evt`，提交 `afe95f46e46498e6c217ef74f971160e686554df`。

已实现登录门禁和 Android 0.2.0 数据协议的双向同步。本地 Web 客户端与真实 HTTP 后端、PostgreSQL 的数据往返已验证；真实手机和正式账号的最终验收尚未完成。

## 已实现范围

- 邮箱验证码登录、Google/GitHub 登录入口、PKCE 回调、令牌刷新和退出。未登录不挂载账号工作区；数据库按认证项目、同步地址和用户 ID 隔离。
- 会话、消息、记忆、设置和附件同步；支持启动、恢复网络、切回页面、定时及手动同步。记录、待传队列和游标在同一 IndexedDB 事务中保存。
- 可变数据比较 `clientModifiedAt`，较新修改优先。消息正文按安卓协议不可覆盖，只补缺字段，修改需新 UUID；删除墓碑防止旧设备复活数据。“较新”依据设备提供的修改时间，不是文件上传时间。
- 游标分页、批处理、Bearer、协议版本、密钥代次、401 单次刷新、429 延后重试、取消和大小限制；Web Locks 串行化同源多标签同步。
- 配置按 kind/key 合并 canonical ID；上传期间的新编辑不会被旧确认删除。云端 epoch 变化时暂停并允许导出本地备份。
- 敏感配置使用 AES-256-GCM，AAD、字段路径和密钥版本与安卓合同一致。密钥换代后旧密文不能靠“重试”恢复上传，需用新密钥重新编辑保存。
- OpenAI 兼容、Anthropic、Gemini 三种模型协议、流式响应和图片输入；不完整生成草稿不上传。网页有 `/api/model` 中转，后端源码新增 `/v1/chat`；正式 `sync.everytalk.cc/v1/chat` 当前能对无效 JWT 返回 401，说明路由和认证门存在，但真实账号模型转发仍未验收。
- 联网搜索代码支持 Tavily、Exa、Bocha、SerpAPI，密钥加密保存，结果作为不可信资料传入模型并随回复同步。正式站当前尚未包含搜索路由。
- 附件分块预约、上传、下载、单块及整体 SHA-256 校验；历史归档按安卓 gzip + AES-GCM 合同解密后仅展示。浏览器文件处理上限 64 MiB；模型图片另有请求大小预算。
- 浏览器语音输入和朗读。音频不进入 EveryTalk 同步记录，但识别及朗读是否联网取决于浏览器和所选语音服务，不能保证完全本地处理。
- MCP、电脑、工作区、技能等配置可保存和同步。浏览器执行这些能力仍未实现；配置同步不等于执行功能可用。Web 本地代码已支持 Gemini、OpenAI 兼容、Seedream、Modal Z-Image-Turbo 和 Qwen 图像接口，结果作为经过大小校验的图片附件同步到安卓端；这些专用供应商接口尚未在正式环境用真实密钥验收，供应商语音仍未接入 Web。

## 本轮继续实施：图片历史和图片生成协议扩展

- Android 同步不再排除 `isImageGeneration` 会话；文字和图片会话、消息及图片附件共用同一套 LWW、删除墓碑、分块上传和 SHA-256 校验。`last_open_*` 仍是设备缓存，不上传。
- Web 新增登录保护的 `/api/image` 中转，支持 Gemini `generateContent`、OpenAI 兼容 `images/generations`、Qwen `image_base64` 和 Modal 图片 GET。供应商返回的短期图片 URL由服务端固定公网地址下载并限制为单张 64 MiB，再交给现有附件同步；API Key、base64 和供应商 URL不写入同步消息。
- Web 可以创建图片对话、使用 Gemini/Seedream/Qwen/OpenAI 参考图片输入、传递 Gemini 3 尺寸与宽高比、通用推理参数及 Seedream 水印设置，并处理 Modal 宽高参数；图片结果可显示、同步到安卓端并下载原始附件。OpenAI 图像编辑支持单张参考图 multipart；专用供应商接口只完成本地协议实现，不能据此声称正式环境和 Android 全部图片入口已经验收。

## 本轮审查修复

1. `web/src/auth.ts`：Supabase 实际返回 `external.google` / `external.github` 布尔值，原实现误读 `external.providers`，导致已启用入口被隐藏。现已修复，并拒绝将错误 HTTP 响应当成配置。
2. `web/src/sync-store.ts`：将 `SECRETS_EPOCH_CHANGED` 纳入不可直接重试状态，避免旧密文换代后重新上传；重新保存可替换旧队列项。
3. `web/test/auth.test.ts`、`web/test/sync-store.test.ts`：增加上述回归检查。`web/test/sync-client.test.ts` 修正模拟服务回读，并检查远端序号、游标和调用顺序。
4. `web/src/voice.ts` 和本文：修正“语音完全本地处理”、OAuth 未启用、测试数量及部署状态等不准确说明。
5. Android `CloudSyncSettings.kt` 及 `values/strings.xml`、`values-zh/strings.xml`：同步提示改用中英文资源。`AgentSafePauseTest.kt` 修正旧断言，区分用户输入和合法 runtime context；未通过改变生产上下文来迎合断言。
6. `web/src/sync-core.ts`、`sync.ts`、`use-workspace-sync.ts`、`Workspace.tsx`：拒绝状态区分可重试记录和密钥换代、父级删除等永久拒绝；永久拒绝会提示重新编辑，不再显示无效的“重试”按钮。
7. Android `ChatDao`、`RoomSyncStore`、`SyncSettingsProjector`、`SyncEngine` 和 `SyncWorker`：图片会话从同步排除名单移入正式同步流程；同步测试更新为验证图片历史进入队列、`last_open_*` 继续排除。
8. Web `src/model.ts`、`app/api/image/route.ts`、`lib/model-upstream.ts`、`Workspace.tsx`：加入 Gemini/OpenAI 兼容图片请求、图片结果大小校验、附件持久化和图片预览。
9. Web `src/model.ts`、`app/api/image/route.ts`、`SettingsPanel.tsx`：加入 Seedream、Qwen 图像编辑和 Modal Z-Image-Turbo 请求合同，以及 Gemini 3 尺寸、宽高比、推理参数和 Seedream 水印配置；修正 Android `imageSize` 到 Gemini 3 专用字段的映射，并增加 Modal 宽高比回归测试。
10. Web `SettingsPanel.tsx` 和 `src/model.ts`：编辑缺少 `protocolOverride` 的旧 Android 模型配置时，按 provider、模型名和地址恢复 Gemini/Anthropic/OpenAI 协议，避免保存时把旧配置改成 OpenAI。
11. `web/src/sync.ts`、`resources.ts`、`crypto.ts`、`Workspace.tsx`：附件、账号密钥和历史密钥请求现在与同步请求一样，在 401 时只刷新一次令牌并校验账号不变；新增资源请求回归测试，避免令牌恰好过期时上传、下载或设置保存直接失败。
12. `web/src/auth.ts`、`model.ts`、`Workspace.tsx`：模型、图片和联网搜索的登录代理请求复用同一账号令牌刷新逻辑；新增 401 重试回归检查，避免同步刷新成功后其他入口仍使用过期令牌。
13. `web/src/model.ts`、`app/api/image/route.ts`、`lib/model-upstream.ts`：补齐 OpenAI 图像编辑单张参考图 multipart 请求，沿用公网目标校验、请求大小限制和附件同步；增加图片编辑请求回归测试。
14. `web/package.json`：将 Node 引擎固定为 `22.x`，避免 `>=22.0.0` 让 Vercel 自动选择 Node 24.x；Next 16 和 TypeScript 依赖的引擎检查均通过。

工作区存在其他并发修改。本文不把电脑、Markdown 等全部未提交变更归入本次 Web 任务。

## 验证结果

### Web

在 `C:/Users/33039/Desktop/EveryTalk/web/` 执行：

```powershell
bun test test
bun run check:workspace
bun run lint
bun run build
```

25 项测试通过，0 失败；workspace 检查、类型检查、lint、生产构建通过。覆盖安卓加密向量、模型端点与图片协议、OpenAI 图片编辑、旧配置协议识别、搜索、同步合并、事务竞态、父子墓碑、同步及资源请求 401 刷新、模型代理 401 刷新、密钥换代、拒绝状态和登录入口识别。

Playwright 打开本地生产构建 `/chat`，实际显示邮箱地址、发送验证码、Google、GitHub 按钮，未登录不显示工作区。直接 HTTP 冒烟确认 `/chat` 返回 200，未登录同步返回 401，模型、搜索和图片入口拒绝无效来源或未登录请求。没有触发验证码邮件或第三方授权。

本轮重新启动生产构建于本机 `127.0.0.1:55438`，HTTP `/chat` 返回 200，未认证 `/api/cloud/sync/pull` 返回 401；进程已停止。

### 后端真实数据库

后端目录：`C:/Users/33039/Desktop/everytalk-srv/`。普通 `bun test` 为 62 通过、52 跳过、0 失败。随后使用本机 PostgreSQL 18 的独立临时实例，仅监听 `127.0.0.1:55437`，设置测试专用 `TEST_DATABASE_URL` 后重新执行：

```powershell
bun test
bun run typecheck
```

结果：104 通过、2 跳过、0 失败；类型检查通过。剩余跳过项为 Linux 文件权限和真实 Redis 检查。临时实例与生产隔离，验证后停止并删除数据目录。

覆盖真实数据库迁移、并发序号、时间冲突、账号隔离、幂等上传、事务回滚、删除传播、密钥恢复和文件校验。对象存储使用内存适配器，不能据此认定生产 R2 已验收。

另外直接运行 Web 的 `SyncClient`、`AccountCrypto` 和附件代码，连接真实本地 HTTP 后端及 PostgreSQL，六项断言全部通过：

1. Web 写入会话和消息后，安卓协议 pull 能读取相同数据。
2. 安卓协议较新更新覆盖 Web 离线旧版本；Web 更晚更新能被安卓协议读取。
3. 服务端恢复账号密钥，Web 加密设置上传后能按相同合同解密。
4. 附件预约、HTTP 上传、下载及摘要校验通过，对象存储使用内存适配器。
5. 另一账号不能读取测试用户的数据。
6. 会话删除传回 Web，使消息和附件保留删除状态。

此检查使用临时签名测试身份，不代表真实 Supabase 登录、Android 应用或生产环境已验收。

### Android

在 `C:/Users/33039/Desktop/EveryTalk/app1/` 执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.android.everytalk.data.sync.*" --tests "com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ComputerSelectionCardTest" --tests "com.android.everytalk.ui.computer.ComputerUiAlignmentRulesTest" --no-daemon
```

119 项通过，其中同步相关 95 项、相关界面 24 项，0 失败。另一次 AgentSafePause、并行工具、提示缓存、语言配置定向运行 43 项通过。

本轮图片同步改动后，重新运行 ChatDao、SyncEngine 和 SyncResourceSynchronizer 定向测试，共 87 项通过，0 失败；Android Kotlin 编译通过。

此前全量运行曾有 2296 项、2 失败、1 跳过；期间有并发代码变更，后续未重跑全量，不能宣称当前 Android 全量通过。没有执行手机实机或 instrumentation 验收。

## 正式环境现状

2026-10-17 只读核对：

| 项目 | 状态及含义 |
| --- | --- |
| `https://sync.everytalk.cc/health` | HTTP 200，仅证明当前服务健康 |
| `https://sync.everytalk.cc/openapi.yaml` | HTTP 404；线上未公开本地新增接口合同 |
| `https://sync.everytalk.cc/v1/chat` 无认证 | HTTP 401；确认正式域已有认证路由，不能证明真实账号模型转发已验收 |
| 正式站 `/chat` | HTTP 200，入口存在；未完成真实登录 |
| 正式站 `/api/cloud/sync/pull` 无认证 | HTTP 401，拒绝未认证访问 |
| 正式站 `/api/search` | HTTP 404，搜索尚未在正式域生效 |
| Supabase 公共设置 | HTTP 200，邮箱、Google、GitHub 均启用；仍需真实登录验证回调 |
| 正式域当前部署 | `dpl_FWRqd4Wk9VsFRWhFceLHKLDyatoc`，`READY`，项目 `evt`；本轮新部署未接管域名 |
| 后续生产部署 | `dpl_EaVwogrTg3WhM2PHsMzVQ6nndGJP`，`BLOCKED`，未接管正式域 |
| 本轮 CLI 预览部署 | `dpl_GiYX5zPJERsdgsMzrELSKX65cFcE`，构建阶段显示 `READY`，部署最终 `BLOCKED`；没有可用构建输出，未接管任何域名 |
| 本轮远程生产部署 | `dpl_Cs3AtLz1FrB22cEDkwMC6GrtEQwF`，当前源码已上传，部署最终 `BLOCKED`；没有创建正式域别名 |

本轮修正 Node 引擎后再次执行 `vercel deploy --prod --force --yes --archive=tgz`：创建部署 `dpl_5p3BjpCE3FrBywcbDvU92VESQXZF`，预览地址为 `https://evt-ne0ae0ygy-kkkunzs-projects.vercel.app`，生产别名仍显示为 `https://evt-kkkunzs-projects.vercel.app`；约 6 分钟后状态仍为 `UNKNOWN`、构建耗时 `0ms`，命令未进入构建阶段。没有接管 `www.everytalk.cc` 或 `everytalk.cc`。

随后使用 `vercel inspect --json` 读取同一部署的原始状态：`readyState=BLOCKED`；内部 build `bld_f2rdp2clu` 的 `readyState=READY`，但 `output=[]`。CLI 的摘要把该状态显示为 `UNKNOWN`，平台 JSON 才明确表示是团队配置阻塞。

本轮又执行一次 `vercel deploy --prod --skip-domain --force --yes --archive=tgz` 作为对照：新部署 `dpl_7VXF8UZCfZN47Ux8v6PxSJEvfdUp` 仍为 `readyState=BLOCKED`，内部 build `bld_nmkwvqj7p` 为 `READY` 但 `output=[]`。跳过域名接管没有改变结果，阻塞点确认在团队配置，不是正式域名 promotion。

2026-10-17 13:14（太平洋时间）再次只读请求正式域和预览域：

- `https://www.everytalk.cc/chat` 返回 `200`，命中现有 `/chat` 静态缓存。
- `https://www.everytalk.cc/api/cloud/sync/pull` 未带令牌返回 `401`，同步代理仍在线。
- `https://www.everytalk.cc/api/search` 和 `/api/image` 均返回 `404`，本轮新增路由尚未进入正式域。
- `https://evt-a61r2o51z-kkkunzs-projects.vercel.app/` 及其 `/chat`、`/api/search`、`/api/image` 路径均被 Vercel 团队 SSO 重定向到 `vercel.com/sso-api`，匿名请求无法用来确认预览产物。

这次只读核对没有重新创建或发布部署，也没有绕过 Vercel 团队 SSO；在团队配置解除阻塞前，线上功能验收仍以正式域实际响应为准。

2026-10-17 13:20（太平洋时间）通过 Vercel CLI 复核：当时最新生产部署 `evt-a61r2o51z-kkkunzs-projects.vercel.app` 状态 `UNKNOWN`，构建耗时 `0ms`，没有可用构建产物；其别名仍是 `https://evt-kkkunzs-projects.vercel.app`。正式域路由结果未变化。后端和 QoneAgent 代码审查也确认：当前后端没有 MCP、电脑或技能执行 HTTP 路由，QoneAgent 执行依赖本地 runtime、stdio、浏览器桥接及 SSH/Cloudflare 凭据，不能在没有新的受控执行服务和权限模型时直接从浏览器安全调用。

随后使用 `agent-browser` 只读打开 `https://www.everytalk.cc/chat`：页面标题和登录门禁正常，匿名页面只呈现“邮箱地址”和“发送验证码”，没有工作区；正式缓存中未出现本地源码已实现的 Google/GitHub 登录入口。这没有触发验证码、OAuth 或任何数据写入。

本轮在 `web/` 执行 `vercel build --prod` 和 `vercel build --prod --standalone`：Next 编译、TypeScript、静态页面、同步代理、模型、搜索和图片路由全部生成成功，最后均因 Windows 无法创建 Vercel 函数目录符号链接而以 `EPERM` 退出。该本地 CLI 权限问题不改变普通 `bun run build` 已通过的结果；正式部署仍需在 Vercel 远程构建或具备符号链接权限的 CI 环境完成。

Vercel 阻塞部署的错误链接指向 [团队配置排查](https://vercel.com/docs/deployments/troubleshoot-project-collaboration#team-configuration)。项目只读信息还显示团队 SSO 保护已启用、项目 `live=false`；当前源码的远程生产部署仍为 `BLOCKED`。正式 `dpl_FWR...` 的产物清单只包含 `api/model` 和同步代理，没有 `api/search`、`api/image`，与正式域 404 相符。没有绕过平台门槛，也没有因为构建上传成功而宣称最新版已上线。本轮登录入口、密钥重试修复仍只在本地源码与构建中。

本轮 Vercel CLI 可以读取项目 `kkkunzs-projects/evt`，确认项目 Node.js 为 `22.x`；Vercel MCP 对同一团队返回 403，不能据此证明部署权限或阻塞状态已经改变。Web 已将 Node 引擎固定为 `22.x`，并创建了上方记录的新生产部署，但该部署仍未进入构建。

Production 已配置 `NEXT_PUBLIC_SUPABASE_URL`、`NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY`、`EVERYTALK_SYNC_BASE_URL`。本机 Next.js 配置仅复用 Android `local.properties` 的对应公开值；部署不依赖该文件。

后端源码、README 和 ops 文档均显示本地新增 `/v1/chat` 的完整实现，但没有可直接使用的服务器部署入口或真实登录凭据。生产该路径对无效 JWT 返回 401，已确认认证门存在；尚不能确认带真实用户 JWT 的模型转发和上游响应。线上 `openapi.yaml` 返回 404。未读取 `everytalk-master-key.txt`，未提交或推送代码。

## 集中待处理事项

| 事项 | 当前缺口 | 最终验收方式 |
| --- | --- | --- |
| 最新 Web 发布 | 正式部署和本轮远程生产部署均被 Vercel 团队配置阻止；当前源码能上传但部署状态为 `BLOCKED` | 解决 `kkkunzs-projects` 团队配置后重新发布，核对正式域版本、OAuth 按钮及 `/api/search` |
| 生产后端模型代理 | 无法从本机完成部署、确认运行版本 | 按服务器现有运维方式部署后，用真实账号调用 `/v1/chat` |
| 真实账号登录 | 无此次验收可用的验证码或第三方授权会话 | 使用 Android 同账号完成 Web 登录，验证邮箱/OAuth 回调及刷新 |
| Android/Web 实机互通 | 验证到了真实数据库与安卓协议，没有真实手机 | 两端各新建、修改、删除会话，交错离线编辑，核对较新版本、附件、记忆和配置 |
| 模型、搜索及生产存储 | 未用真实模型和搜索密钥执行调用，未做生产 R2 往返 | 登录后用已同步配置发送消息、图片和联网问题，从另一端下载附件 |
| 完整能力一致 | MCP、电脑、技能执行和供应商语音仍没有 Web 执行实现；Seedream/Modal/Qwen/OpenAI 图片接口仅完成本地协议实现，未做正式环境验收 | 需要相应服务端执行与权限方案；登录后用真实供应商配置完成图片生成、编辑和附件往返 |
| Android 发布质量 | 定向验证通过，最新全量及实机未验收 | 并发改动收敛后按发布流程测试与设备验收 |

## 本地运行

```powershell
cd C:/Users/33039/Desktop/EveryTalk/web
bun install
bun run dev
```

默认端口 4173。本机此次监听该端口返回 `EACCES`，已用 55438 完成运行和浏览器检查。可指定可用端口：

```powershell
bun run dev -- --port 55438
```

环境变量示例见 `web/.env.example`。复现后端集成测试时，`TEST_DATABASE_URL` 必须指向独立测试库；测试执行迁移及写入删除，不应指向生产库。

## 主要文件职责

- `web/src/app.tsx`、`auth.ts`、`LoginPage.tsx`：登录门禁、会话生命周期和登录表单。
- `web/src/Workspace.tsx`、`SettingsPanel.tsx`：工作区、会话与配置操作。
- `web/src/use-workspace-sync.ts`、`sync.ts`、`sync-core.ts`、`sync-store.ts`：页面生命周期、网络协议、合并规则及 IndexedDB 事务。
- `web/src/crypto.ts`、`resources.ts`：密钥、字段加密、附件及历史归档。
- `web/src/model.ts`、`search.ts`、`voice.ts`：模型协议、图片生成请求、搜索和浏览器语音。
- `web/app/api/image/route.ts`、`web/lib/model-upstream.ts`：登录后的图片生成中转和公网图片字节校验。
- `web/app/api/cloud/[...path]/route.ts`、`api/model/route.ts`、`api/search/route.ts`：固定上游同步代理、登录校验后的模型和搜索代理。
- `web/test/`：协议及数据完整性回归检查。
- `C:/Users/33039/Desktop/everytalk-srv/src/model/`、`src/app.ts`、`openapi.yaml` 及相关测试：后端模型代理和接口合同。

完整上线目标尚未完成，待处理项以上表为准。
