'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { authClient, SessionExpiredError } from './auth'
import { AccountCrypto } from './crypto'
import { SyncClient, type SyncSnapshot } from './sync'
import { syncKey, type AuthSession, type JsonObject, type StoredRecord, type SyncEntity } from './types'

export interface WorkspaceRuntime {
  client: SyncClient
  crypto: AccountCrypto
  alive: boolean
  ready: boolean
  channel?: BroadcastChannel
}
const empty: SyncSnapshot = { records: [], cursor: 0, rejected: 0, retryableRejected: 0, pendingCount: 0 }

/**
 * 本地免登录预览会话：用于在未登录云端账号时直接进入完整工作区，体验所有交互组件、对话框、AI 执行时间线与设置卡片。
 */
export const LOCAL_PREVIEW_SESSION: AuthSession = {
  access_token: 'local-preview-token',
  refresh_token: 'local-preview-refresh',
  expires_at: Math.floor(Date.now() / 1000) + 86400 * 30,
  user: {
    id: 'local-preview-user',
    email: 'preview@everytalk.local',
    user_metadata: {
      everytalk_name: 'EveryTalk 预览体验',
    },
  },
}

function makeRecord(entity: SyncEntity, id: string, payload: JsonObject): StoredRecord {
  return {
    key: syncKey(entity, id),
    entity,
    id,
    payload,
    deleted: false,
  }
}

/**
 * 演示用的轻量 SVG 赛博朋克概念图 Data URI，支持在图片消息中真实渲染、点击打开全屏灯箱及作为参考图编辑。
 */
const DEMO_CYBERPUNK_IMAGE_SVG = `data:image/svg+xml;utf8,${encodeURIComponent(`
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" width="1280" height="720">
  <defs>
    <linearGradient id="sky" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#0a0a14"/>
      <stop offset="60%" stop-color="#140b2a"/>
      <stop offset="100%" stop-color="#080811"/>
    </linearGradient>
    <linearGradient id="neonPink" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" stop-color="#FF2E93"/>
      <stop offset="100%" stop-color="#FF8A00"/>
    </linearGradient>
    <linearGradient id="neonBlue" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#00F0FF"/>
      <stop offset="100%" stop-color="#7000FF"/>
    </linearGradient>
  </defs>
  <rect width="1280" height="720" fill="url(#sky)"/>
  <!-- 远景摩天大楼剪影 -->
  <rect x="80" y="160" width="120" height="560" fill="#10101c"/>
  <rect x="230" y="220" width="90" height="500" fill="#0d0d18"/>
  <rect x="360" y="90" width="160" height="630" fill="#141424"/>
  <rect x="560" y="180" width="110" height="540" fill="#0d0d17"/>
  <rect x="710" y="120" width="150" height="600" fill="#121222"/>
  <rect x="900" y="240" width="130" height="480" fill="#0b0b14"/>
  <rect x="1060" y="140" width="140" height="580" fill="#131326"/>
  <!-- 霓虹灯招牌 -->
  <text x="390" y="180" fill="url(#neonPink)" font-family="sans-serif" font-weight="900" font-size="28" letter-spacing="4">EVERYTALK AI</text>
  <text x="730" y="210" fill="url(#neonBlue)" font-family="sans-serif" font-weight="800" font-size="24" letter-spacing="3">CYBER CITY 2026</text>
  <!-- 雨夜湿漉地面反光 -->
  <rect x="0" y="560" width="1280" height="160" fill="#07070d"/>
  <ellipse cx="440" cy="620" rx="280" ry="32" fill="#FF2E93" opacity="0.28"/>
  <ellipse cx="800" cy="640" rx="340" ry="36" fill="#00F0FF" opacity="0.22"/>
  <!-- 雨丝 -->
  <line x1="200" y1="40" x2="160" y2="180" stroke="#00F0FF" stroke-width="1.2" opacity="0.35"/>
  <line x1="480" y1="80" x2="440" y2="240" stroke="#FF2E93" stroke-width="1.2" opacity="0.35"/>
  <line x1="820" y1="60" x2="780" y2="220" stroke="#00F0FF" stroke-width="1.2" opacity="0.35"/>
  <line x1="1040" y1="100" x2="1000" y2="280" stroke="#FF2E93" stroke-width="1.2" opacity="0.35"/>
</svg>
`)}`

/**
 * 首次进入本地免登录预览模式时，写入覆盖完整 UI 组件与业务场景的高质量模拟数据。
 * 注意：所有 StoredRecord 的 id 必须是严格合规的标准 UUID，否则会被 sync.ts 的 isUuid() 拦截抛错。
 */
async function ensureLocalPreviewSeed(client: SyncClient): Promise<void> {
  const existing = await client.snapshot()
  const PREVIEW_SEED_VERSION = 'v5_mcp_skills_servers_complete'
  const versionRecord = existing.records.find(
    r => r.entity === 'user_settings' && !r.deleted && r.payload.kind === 'preview_seed_version'
  )

  // 如果已经初始化过相同版本的种子数据，且至少有会话，直接跳过
  if (
    versionRecord &&
    versionRecord.payload.key === PREVIEW_SEED_VERSION &&
    existing.records.some(r => r.entity === 'sessions' && !r.deleted)
  ) {
    return
  }

  const now = Date.now()

  // 1. 定义合规的标准 UUID 会话与附件标识
  const S_CORE_TIMELINE = '10000000-0000-4000-8000-000000000001'
  const S_WEB_SEARCH = '20000000-0000-4000-8000-000000000002'
  const S_IMAGE_GEN = '30000000-0000-4000-8000-000000000003'
  const S_CODE_PREVIEW = '40000000-0000-4000-8000-000000000004'
  const S_SYSTEM_PROMPT = '50000000-0000-4000-8000-000000000005'
  const S_WELCOME_EMPTY = '60000000-0000-4000-8000-000000000006'

  const IMG_ATTACHMENT_ID = '70000000-0000-4000-8000-000000000007'

  // 同步初始化分组本地 localStorage
  try {
    localStorage.setItem(
      'everytalk-web-groups:local-preview-user',
      JSON.stringify(['工作与调研', '创作与设计', '技术架构', '日常助手'])
    )
  } catch {
    /* 忽略存储受限 */
  }

  const longUserText = [
    '请帮我全面检查一下 EveryTalk Web 端与安卓端的 UI 对齐情况，重点包括：',
    '1. 字体排印是否已采用 QoneAgent 的字体栈与 430 默认字重；',
    '2. 顶部悬浮模型胶囊、同步状态与右侧双按钮胶囊（开启新对话 + 更多操作）；',
    '3. 左侧可折叠抽屉、分组管理、置顶分组与会话悬浮卡片菜单；',
    '4. AI 思考过程（扫光状态字、3 行纯文本思考预览、工具调用聚合与完整垂直时间线弹窗）；',
    '5. 代码块卡片（24px 圆角、吸顶语言头、HTML/SVG 实时预览切换、全屏查看与复制）；',
    '6. 网页来源叠放图标胶囊与来源列表弹窗；',
    '7. 底部自适应输入栏、+ 号圆形功能面板、生图比例与清晰度弹窗、语音对话大屏；',
    '8. 消息底部操作栏（Token 用量统计胶囊、切换模型重答确认框、7 项单选卡片式举报弹窗）与设置面板（模型筛选、四大搜索服务商卡片与拨杆、全局记忆开关）。',
    '这是一段超过 8 行的长用户消息，用来直观展示安卓端气泡的长文本渐变遮罩与展开/收起交互效果！',
  ].join('\n')

  const aiReplyTimelineText = [
    '已完成全部组件与对话框的对齐检查！你可以直接在当前界面交互体验以下模块：',
    '',
    '1. **思考与工具执行时间线**：点击上方「已思考 6 秒」折叠头可收起或展开 3 行思考预览与工具调用步骤；点击「详情」按钮可打开完整的垂直时间线弹窗。',
    '2. **网页来源胶囊与消息底部工具栏**：点击下方的「3 个网页来源」胶囊可打开来源详情弹窗；点击底部的 Token 胶囊、`...` 更多菜单或左下角设置图标，即可查看所有对齐后的弹窗与开关。',
  ].join('\n')

  const codeReplyText = [
    '下面是一个带实时交互预览的 SVG 矢量图与 TypeScript 架构代码块，点击右上角的「渲染预览」或全屏按钮即可直接查看：',
    '',
    '```xml',
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 180" width="100%" height="100%">',
    '  <rect width="420" height="180" rx="20" fill="#18191C" stroke="rgba(255,255,255,0.14)" stroke-width="1.5"/>',
    '  <circle cx="56" cy="90" r="28" fill="#3B82F6" opacity="0.2"/>',
    '  <circle cx="56" cy="90" r="16" fill="#3B82F6"/>',
    '  <text x="100" y="78" fill="#FFFFFF" font-family="sans-serif" font-weight="bold" font-size="16">EveryTalk SVG Preview</text>',
    '  <text x="100" y="104" fill="#A1A1AA" font-family="sans-serif" font-size="13">28px 圆角卡片 · 全屏代码查看 · 实时渲染</text>',
    '  <rect x="100" y="122" width="94" height="24" rx="12" fill="#10B981" opacity="0.2"/>',
    '  <text x="114" y="138" fill="#10B981" font-family="sans-serif" font-weight="bold" font-size="11">已实时渲染</text>',
    '</svg>',
    '```',
    '',
    '```ts',
    '// EveryTalk 跨端加密数据同步接口',
    'export interface SyncPayload {',
    '  conversationId: string',
    '  clientModifiedAt: number',
    '  deviceFingerprint: string',
    '}',
    '```',
  ].join('\n')

  const records: StoredRecord[] = [
    // 种子数据版本记录
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000001', {
      clientModifiedAt: now,
      kind: 'preview_seed_version',
      key: PREVIEW_SEED_VERSION,
      value: { version: PREVIEW_SEED_VERSION },
    }),

    // 会话 1：置顶核心演示会话（包含思考时间线、网页来源、Token统计）
    makeRecord('sessions', S_CORE_TIMELINE, {
      clientModifiedAt: now,
      title: 'EveryTalk 核心组件与执行时间线',
      creationTimestamp: now - 360_000,
      lastModifiedTimestamp: now,
      isImageGeneration: false,
    }),
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000002', {
      clientModifiedAt: now,
      kind: 'pinned_items',
      key: S_CORE_TIMELINE,
      value: {},
    }),

    // 会话 2：工作与调研（开启联网搜索，输入框展示带 X 号的联网搜索标签）
    makeRecord('sessions', S_WEB_SEARCH, {
      clientModifiedAt: now - 30_000,
      title: '2026 前端与全栈技术生态调研',
      creationTimestamp: now - 300_000,
      lastModifiedTimestamp: now - 30_000,
      isImageGeneration: false,
    }),
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000003', {
      clientModifiedAt: now,
      kind: 'conversation_settings',
      key: S_WEB_SEARCH,
      value: { webSearchEnabled: true },
    }),
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000004', {
      clientModifiedAt: now,
      kind: 'group_members',
      key: `VUkx.${S_WEB_SEARCH}`,
      value: { groupName: '工作与调研', conversationId: S_WEB_SEARCH },
    }),

    // 会话 3：图像创作模式（包含画幅比例 16:9 · 2K 标签，输入框带 X 号，附带生图结果）
    makeRecord('sessions', S_IMAGE_GEN, {
      clientModifiedAt: now - 60_000,
      title: '赛博朋克雨夜霓虹街道 (16:9)',
      creationTimestamp: now - 240_000,
      lastModifiedTimestamp: now - 60_000,
      isImageGeneration: true,
    }),
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000005', {
      clientModifiedAt: now,
      kind: 'conversation_settings',
      key: S_IMAGE_GEN,
      value: {
        imageAspectRatio: '16:9',
        imageQualitySize: '2K',
      },
    }),
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000006', {
      clientModifiedAt: now,
      kind: 'group_members',
      key: `VUkx.${S_IMAGE_GEN}`,
      value: { groupName: '创作与设计', conversationId: S_IMAGE_GEN },
    }),

    // 会话 4：技术架构（包含 SVG / HTML 代码卡片全屏与预览）
    makeRecord('sessions', S_CODE_PREVIEW, {
      clientModifiedAt: now - 90_000,
      title: '交互式矢量图与全栈架构代码',
      creationTimestamp: now - 180_000,
      lastModifiedTimestamp: now - 90_000,
      isImageGeneration: false,
    }),
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000007', {
      clientModifiedAt: now,
      kind: 'group_members',
      key: `VUkx.${S_CODE_PREVIEW}`,
      value: { groupName: '技术架构', conversationId: S_CODE_PREVIEW },
    }),

    // 会话 5：日常助手（开启对话风格，输入框展示带 X 号的对话风格标签）
    makeRecord('sessions', S_SYSTEM_PROMPT, {
      clientModifiedAt: now - 120_000,
      title: '系统架构导师对话',
      creationTimestamp: now - 140_000,
      lastModifiedTimestamp: now - 120_000,
      isImageGeneration: false,
    }),
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000008', {
      clientModifiedAt: now,
      kind: 'conversation_settings',
      key: S_SYSTEM_PROMPT,
      value: {
        systemPrompt: '你是一位精通分布式系统与前端架构的资深系统架构师，回答请直接指出核心瓶颈并给出最精简的实现代码。',
        promptEnabled: true,
      },
    }),
    makeRecord('user_settings', 'a0000000-0000-4000-8000-000000000009', {
      clientModifiedAt: now,
      kind: 'group_members',
      key: `VUkx.${S_SYSTEM_PROMPT}`,
      value: { groupName: '日常助手', conversationId: S_SYSTEM_PROMPT },
    }),

    // 会话 6：空白文本会话（未分组，展示 2x2 欢迎卡片）
    makeRecord('sessions', S_WELCOME_EMPTY, {
      clientModifiedAt: now - 150_000,
      title: '新建自由对话',
      creationTimestamp: now - 150_000,
      lastModifiedTimestamp: now - 150_000,
      isImageGeneration: false,
    }),

    // 长期记忆全局开关与 4 条代表性长期记忆
    makeRecord('user_settings', 'b0000000-0000-4000-8000-000000000001', {
      clientModifiedAt: now,
      kind: 'memory_switches',
      key: 'default',
      value: { memoryEnabled: true },
    }),
    makeRecord('memories', 'c0000000-0000-4000-8000-000000000001', {
      content: '所有前端工程默认优先使用 Bun、TypeScript、React 与 Tailwind CSS，组件优先复用已有组件库。',
      category: 'PREFERENCE',
      origin: 'MANUAL',
      enabled: true,
      createdAt: now - 3600_000,
      updatedAt: now - 1800_000,
      clientModifiedAt: now - 1800_000,
    }),
    makeRecord('memories', 'c0000000-0000-4000-8000-000000000002', {
      content: '严格遵循极简开发法则：能用一行代码解决的不写十行，优先使用标准库和原生平台特性。',
      category: 'CODING_STYLE',
      origin: 'MANUAL',
      enabled: true,
      createdAt: now - 3000_000,
      updatedAt: now - 1200_000,
      clientModifiedAt: now - 1200_000,
    }),
    makeRecord('memories', 'c0000000-0000-4000-8000-000000000003', {
      content: '界面排版严格遵循 QoneAgent 字体栈规范，所有对话框统一 28px 圆角与 48px 胶囊形按钮。',
      category: 'DESIGN_SYSTEM',
      origin: 'MANUAL',
      enabled: true,
      createdAt: now - 2400_000,
      updatedAt: now - 900_000,
      clientModifiedAt: now - 900_000,
    }),
    makeRecord('memories', 'c0000000-0000-4000-8000-000000000004', {
      content: '回答时先给关键结论，再提供最简方案，保持客观严谨，少说空话，禁止过度抽象。',
      category: 'COMMUNICATION',
      origin: 'MANUAL',
      enabled: true,
      createdAt: now - 1800_000,
      updatedAt: now - 600_000,
      clientModifiedAt: now - 600_000,
    }),

    // 自定义模型配置（供设置面板和模型切换器体验）
    makeRecord('user_settings', 'd0000000-0000-4000-8000-000000000001', {
      clientModifiedAt: now,
      kind: 'api_configs',
      key: 'model-claude-thinking',
      value: {
        name: 'Claude 3.7 Sonnet (Thinking)',
        provider: 'Anthropic',
        channel: 'Anthropic',
        address: 'https://api.anthropic.com/v1',
        model: 'claude-3-7-sonnet-20250219',
        apiKey: 'sk-ant-preview-demo-key',
        outputLimit: 8192,
        imageConfig: false,
        modelParameters: {
          contextLimit: 200000,
          reasoningEffort: 'high',
          protocolOverride: 'ANTHROPIC',
        },
      },
    }),
    makeRecord('user_settings', 'd0000000-0000-4000-8000-000000000002', {
      clientModifiedAt: now,
      kind: 'api_configs',
      key: 'model-deepseek-r1',
      value: {
        name: 'DeepSeek-R1 (满血推理版)',
        provider: 'DeepSeek',
        channel: 'OpenAI兼容',
        address: 'https://api.deepseek.com/v1',
        model: 'deepseek-reasoner',
        apiKey: 'sk-deepseek-demo-key',
        outputLimit: 8192,
        imageConfig: false,
        modelParameters: {
          contextLimit: 64000,
          reasoningEffort: 'max',
          protocolOverride: 'OPENAI_COMPATIBLE',
        },
      },
    }),
    makeRecord('user_settings', 'd0000000-0000-4000-8000-000000000003', {
      clientModifiedAt: now,
      kind: 'api_configs',
      key: 'model-flux-schnell',
      value: {
        name: 'Flux.1 Schnell 旗舰生图',
        provider: 'Black Forest Labs',
        channel: 'OpenAI兼容',
        address: 'https://api.bfl.ml/v1',
        model: 'flux.1-schnell',
        apiKey: 'bfl-preview-demo-key',
        imageConfig: true,
        imageSize: '2K',
        aspectRatio: '16:9',
        modelParameters: {
          imageApiFormat: 'openai',
          imageAspectRatio: '16:9',
        },
      },
    }),

    // 外部搜索服务商配置（Tavily 与 Exa 已配置密钥，Tavily 为默认搜索引擎）
    makeRecord('user_settings', 'e0000000-0000-4000-8000-000000000001', {
      clientModifiedAt: now,
      kind: 'selected_models',
      key: 'web_search',
      value: { providerId: 'tavily' },
    }),
    makeRecord('user_settings', 'e0000000-0000-4000-8000-000000000002', {
      clientModifiedAt: now,
      kind: 'web_search_configs',
      key: 'tavily',
      value: {
        name: 'Tavily',
        providerId: 'tavily',
        apiKey: 'tvly-demo-preview-key-everytalk',
      },
    }),
    makeRecord('user_settings', 'e0000000-0000-4000-8000-000000000003', {
      clientModifiedAt: now,
      kind: 'web_search_configs',
      key: 'exa',
      value: {
        name: 'Exa',
        providerId: 'exa',
        apiKey: 'exa-preview-demo-key-12345',
      },
    }),

    // MCP 服务器连接配置（对齐安卓端 McpDialogs.kt 与 McpSettingsContent）
    makeRecord('user_settings', 'e1000000-0000-4000-8000-000000000001', {
      clientModifiedAt: now,
      kind: 'mcp_configs',
      key: 'context7',
      value: {
        id: 'context7',
        name: 'Context7',
        url: 'https://mcp.context7.com/mcp',
        transportType: 'HTTP',
        preset: 'CONTEXT7',
        enabled: true,
        status: 'connected',
        description: '实时拉取最新开源框架官方文档与版本准确的代码示例',
        tools: [
          { name: 'resolve-library-id', description: '将开源库名称解析为 Context7 标准文档标识符' },
          { name: 'get-library-docs', description: '按主题与版本获取指定开源库的最新官方文档与 API 示例' },
        ],
      },
    }),
    makeRecord('user_settings', 'e1000000-0000-4000-8000-000000000002', {
      clientModifiedAt: now,
      kind: 'mcp_configs',
      key: 'exa-mcp',
      value: {
        id: 'exa-mcp',
        name: 'Exa Search MCP',
        url: 'https://mcp.exa.ai/mcp?tools=web_search_exa,get_code_context_exa',
        transportType: 'HTTP',
        preset: 'EXA_SEARCH',
        enabled: true,
        status: 'connected',
        description: 'Exa 神经语义检索与全网开源代码上下文查询服务',
        tools: [
          { name: 'web_search_exa', description: '执行深度语义网页检索并返回高相关性结构化摘要' },
          { name: 'get_code_context_exa', description: '检索 GitHub 与技术文档中的精准代码实现片段' },
        ],
      },
    }),
    makeRecord('user_settings', 'e1000000-0000-4000-8000-000000000003', {
      clientModifiedAt: now,
      kind: 'mcp_configs',
      key: 'firecrawl-mcp',
      value: {
        id: 'firecrawl-mcp',
        name: 'Firecrawl',
        url: 'https://mcp.firecrawl.dev/v2/mcp',
        transportType: 'HTTP',
        preset: 'FIRECRAWL',
        enabled: true,
        status: 'connected',
        description: '动态网页抓取、整站地图测绘与 Markdown 结构化提取',
        tools: [
          { name: 'firecrawl_scrape', description: '抓取单个网页并清理为干净的 Markdown 文本' },
          { name: 'firecrawl_map', description: '快速扫描并发现目标网站的所有子页面链接' },
          { name: 'firecrawl_extract', description: '按 Schema 从网页中提取结构化 JSON 数据' },
        ],
      },
    }),
    makeRecord('user_settings', 'e1000000-0000-4000-8000-000000000004', {
      clientModifiedAt: now,
      kind: 'mcp_configs',
      key: 'github-mcp',
      value: {
        id: 'github-mcp',
        name: 'GitHub Copilot MCP',
        url: 'https://api.githubcopilot.com/mcp/',
        transportType: 'SSE',
        preset: 'CUSTOM',
        enabled: false,
        status: 'idle',
        description: '仓库源码检索、Issue 跟踪与 Pull Request 审查工具集',
        tools: [
          { name: 'search_repositories', description: '按关键词搜索 GitHub 开源仓库' },
          { name: 'get_file_contents', description: '读取指定分支下的源码文件内容' },
          { name: 'list_pull_requests', description: '列出仓库的 Pull Request 状态与变更摘要' },
        ],
      },
    }),

    // 已安装技能包配置（对齐安卓端 SkillScreen.kt、SkillDetailScreen.kt 与 SkillDownloadScreen.kt）
    makeRecord('user_settings', 'e2000000-0000-4000-8000-000000000001', {
      clientModifiedAt: now,
      kind: 'skill_packages',
      key: 'vercel-labs-nextjs-react',
      value: {
        packageId: 'vercel-labs-nextjs-react',
        name: 'vercel-labs/nextjs-react-skills',
        sourceType: 'REMOTE',
        sourceRepository: 'https://github.com/vercel-labs/nextjs-react-skills',
        currentHash: 'a8f4c291e0',
        updateHash: '',
        enabled: true,
        description: 'React 19、Next.js App Router、流式 SSR 与 Tailwind 设计系统工程规范',
        children: [
          {
            skillId: 'react-19-patterns',
            name: 'react-19-patterns',
            description: 'React 19 Actions、useOptimistic 与服务端组件数据流最佳实践',
          },
          {
            skillId: 'tailwind-design-system',
            name: 'tailwind-design-system',
            description: '高对比度 OLED 暗黑模式、响应式间距令牌与平滑微交互动效规范',
          },
        ],
        markdown: [
          '---',
          'name: nextjs-react-skills',
          'description: React 19 与 Next.js App Router 生产级开发规范',
          '---',
          '',
          '# React 19 & Next.js 核心规则',
          '1. 优先使用函数式组件与原生 Hooks，保持状态最小化。',
          '2. 所有展开/收起交互必须使用 CSS Grid `0fr -> 1fr` 或实测 `max-height` 平滑过渡。',
          '3. 深色模式采用 OLED 纯黑 `#000000` 主背景与高对比度分层卡片。',
        ].join('\n'),
        files: [
          { path: 'SKILL.md', size: 2480, sha256: 'a8f4c291e04b' },
          { path: 'references/rsc-streaming.md', size: 4120, sha256: '7c31d902fa18' },
          { path: 'templates/collapsible-card.tsx', size: 1560, sha256: 'e901b44512cd' },
        ],
      },
    }),
    makeRecord('user_settings', 'e2000000-0000-4000-8000-000000000002', {
      clientModifiedAt: now,
      kind: 'skill_packages',
      key: 'everytalk-system-architect',
      value: {
        packageId: 'everytalk-system-architect',
        name: 'everytalk/system-architect',
        sourceType: 'USER_CREATED',
        sourceRepository: '本地自建技能',
        currentHash: 'f39d104b82',
        updateHash: '',
        enabled: true,
        description: '高并发分布式架构评审、PostgreSQL + Drizzle ORM 调优与极简代码审计',
        children: [
          {
            skillId: 'system-architect',
            name: 'system-architect',
            description: '分布式架构瓶颈诊断、Redis Streams 消息队列与数据库索引优化指南',
          },
        ],
        markdown: [
          '---',
          'name: system-architect',
          'description: 分布式系统架构与极简代码审查技能',
          '---',
          '',
          '# 架构设计准则',
          '1. 遵循 YAGNI 原则，拒绝未被明确要求的抽象层与样板代码。',
          '2. 修复问题直达根因（Root Cause），在共享入口一次性守卫所有调用链。',
          '3. 数据库查询必须验证索引覆盖率，高并发推送优先采用 SSE + Redis Streams。',
        ].join('\n'),
        files: [
          { path: 'SKILL.md', size: 1890, sha256: 'f39d104b82cc' },
          { path: 'scripts/pg-index-check.sql', size: 940, sha256: '4b1289ac01de' },
        ],
      },
    }),
    makeRecord('user_settings', 'e2000000-0000-4000-8000-000000000003', {
      clientModifiedAt: now,
      kind: 'skill_packages',
      key: 'owasp-security-audit',
      value: {
        packageId: 'owasp-security-audit',
        name: 'owasp/code-security-audit',
        sourceType: 'LOCAL_IMPORT',
        sourceRepository: 'owasp-security-audit.zip',
        currentHash: 'c51209ea73',
        updateHash: 'd98120bc41',
        enabled: false,
        description: 'OWASP Top 10 安全漏洞审计、端到端加密验证与鉴权边界检查规则',
        children: [
          {
            skillId: 'owasp-top10-audit',
            name: 'owasp-top10-audit',
            description: '注入攻击防护、敏感凭据零残留与跨站脚本防御检查表',
          },
        ],
        markdown: [
          '---',
          'name: owasp-security-audit',
          'description: OWASP Top 10 代码安全审计规范',
          '---',
          '',
          '# 安全审计清单',
          '1. 严禁在日志或前端明文存储未加密的长期私钥。',
          '2. 所有外部输入必须在信任边界完成 Schema 严格校验。',
        ].join('\n'),
        files: [
          { path: 'SKILL.md', size: 3150, sha256: 'c51209ea7311' },
          { path: 'references/owasp-2026-checklist.md', size: 5420, sha256: '81a09bc43ef2' },
        ],
      },
    }),

    // 远程服务器 / 计算机配置（对齐安卓端 ComputerScreen.kt、ComputerDetailScreen.kt 与 ComputerWorkspaceUi.kt）
    makeRecord('user_settings', 'e3000000-0000-4000-8000-000000000001', {
      clientModifiedAt: now,
      kind: 'computer_servers',
      key: 'server-hk-docker',
      value: {
        id: 'server-hk-docker',
        name: '香港生产开发节点 (Docker 隔离)',
        provider: 'SSH',
        host: '43.154.88.120',
        port: 22,
        username: 'ubuntu',
        authKind: 'PRIVATE_KEY',
        runMode: 'CONTAINER',
        status: 'READY',
        permissionMode: 'ASK',
        privateNetworkAllowed: false,
        hostKeyFingerprint: 'SHA256:9xK8mPqR4vL2nWz7jF5tY1cB6vN8mQ3dH0sA4eG9wJ2',
        activeTasks: 1,
        containers: 2,
        workspaces: [
          {
            id: 'ws-core-timeline',
            name: 'EveryTalk 核心组件与执行时间线',
            status: 'READY',
            hostPath: '/var/lib/everytalk/workspaces/core-timeline',
            lastUsedAt: now - 120_000,
            secrets: [
              { name: 'DATABASE_URL', updatedAt: now - 3600_000 },
              { name: 'REDIS_STREAM_KEY', updatedAt: now - 1800_000 },
            ],
            previews: [
              {
                id: 'prev-4173',
                port: 4173,
                protocol: 'HTTP',
                visibility: 'PRIVATE',
                url: 'https://preview-4173.everytalk.internal',
              },
            ],
          },
          {
            id: 'ws-fullstack-2026',
            name: '2026 前端与全栈技术生态调研',
            status: 'READY',
            hostPath: '/var/lib/everytalk/workspaces/fullstack-2026',
            lastUsedAt: now - 600_000,
            secrets: [{ name: 'TAVILY_API_KEY', updatedAt: now - 7200_000 }],
            previews: [],
          },
        ],
      },
    }),
    makeRecord('user_settings', 'e3000000-0000-4000-8000-000000000002', {
      clientModifiedAt: now,
      kind: 'computer_servers',
      key: 'server-cf-sandbox',
      value: {
        id: 'server-cf-sandbox',
        name: 'Cloudflare Edge Sandbox',
        provider: 'CLOUDFLARE',
        host: 'sandbox.everytalk.workers.dev',
        port: 443,
        username: 'cloudflare-oauth',
        authKind: 'OAUTH',
        runMode: 'CONTAINER',
        status: 'READY',
        permissionMode: 'AUTO',
        privateNetworkAllowed: false,
        cloudflareAccountName: 'EveryTalk Team Account',
        cloudflareAccountId: 'cf-acc-98234a1b',
        cloudflareIdentity: 'preview@everytalk.local',
        hostKeyFingerprint: 'Cloudflare Zero Trust Managed TLS',
        activeTasks: 0,
        containers: 1,
        workspaces: [
          {
            id: 'ws-cf-svg',
            name: '交互式矢量图与全栈架构代码',
            status: 'READY',
            hostPath: 'cf://containers/svg-preview-sandbox',
            lastUsedAt: now - 900_000,
            secrets: [{ name: 'CF_WORKER_TOKEN', updatedAt: now - 5400_000 }],
            previews: [
              {
                id: 'prev-8787',
                port: 8787,
                protocol: 'HTTP',
                visibility: 'PUBLIC',
                url: 'https://svg-preview.everytalk.workers.dev',
              },
            ],
          },
        ],
      },
    }),

    // 会话 1 的消息数据
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000001', {
      sessionId: S_CORE_TIMELINE,
      sender: 'User',
      text: longUserText,
      timestamp: now - 30_000,
      isError: false,
      outputType: 'general',
    }),
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000002', {
      sessionId: S_CORE_TIMELINE,
      sender: 'AI',
      text: aiReplyTimelineText,
      timestamp: now - 22_000,
      isError: false,
      outputType: 'general',
      modelName: 'gemini-2.5-pro',
      providerName: 'Google Gemini',
      reasoning: [
        '正在核对 QoneAgent 桌面端字体栈变量与字重配置（--q-font-sans, --q-font-mono, 430 字重）。',
        '正在比对安卓端 ThinkingUI.kt 与 ThinkingExecutionTimeline.kt 的推理预览卡片和工具调用节点样式。',
        '正在检查所有对话框（举报弹窗、切换模型重答弹窗、设置卡片、生图比例弹窗）的 28px 圆角与 48px 胶囊按钮。',
      ].join('\n'),
      executionStartedAt: now - 28_000,
      executionFinishedAt: now - 22_000,
      executionDurationMs: 6000,
      executionSteps: [
        {
          id: 'step-search-1',
          type: 'search',
          title: '搜索网页：EveryTalk Android Compose UI 规范',
          labels: ['EveryTalk Android Compose UI 规范', 'QoneAgent typography scale'],
          completed: true,
        },
        {
          id: 'step-web-1',
          type: 'web',
          title: '读取网页：github.com/roseforljh/KunTalkwithAi',
          labels: [
            'https://github.com/roseforljh/KunTalkwithAi',
            'https://developer.android.com/develop/ui/compose/designsystems/material3',
          ],
          completed: true,
        },
        {
          id: 'step-tool-1',
          type: 'tool',
          title: '调用工具：everytalk_memory · 检索设计偏好记忆',
          labels: ['everytalk_memory', 'query: 字体栈与弹窗圆角'],
          completed: true,
        },
      ],
      webSearchResults: [
        {
          title: 'EveryTalk (KunTalkwithAi) 开源仓库 · 跨平台 AI 助手',
          url: 'https://github.com/roseforljh/KunTalkwithAi',
          snippet: 'EveryTalk 支持多模态对话、图像生成、联网搜索、工具调用时间线与跨设备端到端加密同步。',
        },
        {
          title: 'Jetpack Compose Material 3 组件与圆角卡片设计指南',
          url: 'https://developer.android.com/develop/ui/compose/designsystems/material3',
          snippet: 'Material 3 推荐使用 24dp/28dp 大圆角浮层卡片、胶囊形操作按钮与清晰的视觉层级。',
        },
        {
          title: 'MDN Web Docs · CSS System Font Stack 与排版抗锯齿优化',
          url: 'https://developer.mozilla.org/zh-CN/docs/Web/CSS/font-family',
          snippet: '通过系统原生无衬线字体栈与等宽代码字体栈，在桌面浏览器实现清晰锐利的阅读体验。',
        },
      ],
    }),

    // 会话 2 的消息数据（联网搜索结果展示）
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000003', {
      sessionId: S_WEB_SEARCH,
      sender: 'User',
      text: '请通过外部联网搜索，汇总 2026 年现代全栈开发的核心技术选型与架构趋势。',
      timestamp: now - 24_000,
      isError: false,
      outputType: 'general',
    }),
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000004', {
      sessionId: S_WEB_SEARCH,
      sender: 'AI',
      text: [
        '通过 Tavily 全网实时检索，2026 年全栈技术栈呈现以下三大演变特点：',
        '',
        '1. **运行时极简与高性能**：Bun 与 Rust 工具链进一步普及，冷启动时间缩减至毫秒级。',
        '2. **端到端类型安全**：TypeScript 5.x 配合 Drizzle ORM 与 tRPC / Hono，形成免代码生成的端到端推导方案。',
        '3. **AI 原生集成模式**：工具调用执行时间线与流式响应（SSE）成为现代协同工作区的标配。',
      ].join('\n'),
      timestamp: now - 18_000,
      isError: false,
      outputType: 'general',
      modelName: 'gpt-4.1-mini',
      providerName: 'OpenAI',
      executionStartedAt: now - 23_000,
      executionFinishedAt: now - 18_000,
      executionDurationMs: 5000,
      executionSteps: [
        {
          id: 'step-tavily-search',
          type: 'search',
          title: 'Tavily 实时搜索：2026 modern fullstack architecture stack',
          labels: ['modern fullstack architecture', 'TypeScript Bun ecosystem'],
          completed: true,
        },
      ],
      webSearchResults: [
        {
          title: 'The Modern Full-Stack Stack in 2026 · Architecture Overview',
          url: 'https://everytalk.dev/architecture/fullstack-2026',
          snippet: 'An overview of minimal-abstraction, high-performance web engineering stacks.',
        },
        {
          title: 'Drizzle ORM & Serverless PostgreSQL Best Practices',
          url: 'https://orm.drizzle.team/docs/overview',
          snippet: 'Lightweight, type-safe TypeScript ORM with zero code-generation overhead.',
        },
      ],
    }),

    // 会话 3 的消息数据（生图会话 + 真实图片附件与提示词）
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000005', {
      sessionId: S_IMAGE_GEN,
      sender: 'User',
      text: '生成一幅赛博朋克风格的雨夜霓虹街道，画幅 16:9，电影级画质，雨水反光与霓虹招牌细节丰富。',
      timestamp: now - 50_000,
      isError: false,
      outputType: 'general',
    }),
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000006', {
      sessionId: S_IMAGE_GEN,
      sender: 'AI',
      text: '已为你渲染生成 16:9 画幅赛博朋克雨夜霓虹街道视觉图像，点击图片可放大预览或作为参考图继续编辑：',
      timestamp: now - 42_000,
      isError: false,
      outputType: 'general',
      modelName: 'Flux.1 Schnell',
      providerName: 'Black Forest Labs',
      executionStartedAt: now - 48_000,
      executionFinishedAt: now - 42_000,
      executionDurationMs: 6000,
    }),
    makeRecord('attachments', IMG_ATTACHMENT_ID, {
      messageId: 'f0000000-0000-4000-8000-000000000006',
      sessionId: S_IMAGE_GEN,
      name: 'cyberpunk-neon-street-16-9.svg',
      kind: 'image',
      mimeType: 'image/svg+xml',
      size: 4096,
      dataUrl: DEMO_CYBERPUNK_IMAGE_SVG,
      clientModifiedAt: now - 42_000,
    }),

    // 会话 4 的代码与 SVG 渲染消息
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000007', {
      sessionId: S_CODE_PREVIEW,
      sender: 'User',
      text: '请生成一个可以在网页端直接预览的 SVG 图标卡片组件与相关 TypeScript 接口定义。',
      timestamp: now - 80_000,
      isError: false,
      outputType: 'general',
    }),
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000008', {
      sessionId: S_CODE_PREVIEW,
      sender: 'AI',
      text: codeReplyText,
      timestamp: now - 72_000,
      isError: false,
      outputType: 'general',
      modelName: 'gpt-4.1-mini',
      providerName: 'OpenAI',
    }),

    // 会话 5 的系统提示词/对话风格消息
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000009', {
      sessionId: S_SYSTEM_PROMPT,
      sender: 'User',
      text: '如何设计一个高并发 WebSocket/SSE 服务，避免多实例节点间的消息广播丢失？',
      timestamp: now - 110_000,
      isError: false,
      outputType: 'general',
    }),
    makeRecord('messages', 'f0000000-0000-4000-8000-000000000010', {
      sessionId: S_SYSTEM_PROMPT,
      sender: 'AI',
      text: [
        '【架构师分析】核心采用 Redis Streams 替代传统 Pub/Sub，实现可靠消费组（Consumer Groups）与断线重放机制：',
        '',
        '1. **持久化与游标**：Redis Streams `XADD` 自动分配递增消息 ID，客户端通过 `Last-Event-ID` 建立重连断点续传。',
        '2. **节点水平扩展**：各 API 节点作为独立 Worker 订阅 Stream，通过 `XREADGROUP` 避免竞争与消息丢失。',
        '3. **广播降级**：轻量级心跳走 Redis Pub/Sub，业务核心变更数据走 Stream，保障吞吐与一致性平衡。',
      ].join('\n'),
      timestamp: now - 102_000,
      isError: false,
      outputType: 'general',
      modelName: 'Claude 3.7 Sonnet',
      providerName: 'Anthropic',
    }),
  ]

  await client.saveRecords(records)
}

/** 生命周期只跟账号绑定。刷新令牌不会重开数据库，StrictMode 清理后使用全新连接。 */
export function useWorkspaceSync(session: AuthSession, onExpired: () => void) {
  const sessionRef = useRef(session)
  sessionRef.current = session
  const expiredRef = useRef(onExpired)
  expiredRef.current = onExpired
  const runtime = useRef<WorkspaceRuntime | undefined>(undefined)
  const [snapshot, setSnapshot] = useState<SyncSnapshot>(empty)
  const [status, setStatus] = useState('正在打开')
  const [error, setError] = useState('')
  const [ready, setReady] = useState(false)
  const refreshing = useRef<Promise<void> | undefined>(undefined)
  // 本地每次写入完成（删除、编辑、配置保存）后递增。同步结果的快照是读取时刻的状态，
  // 早于某次本地写入开始的同步结束后不能覆盖该写入的界面，否则卡片会先消失又重现。
  const writeSeq = useRef(0)
  const isLocalPreview = session.user.id === LOCAL_PREVIEW_SESSION.user.id

  const refresh = useCallback(async (): Promise<void> => {
    const current = runtime.current
    if (!current?.alive || !current.ready) return
    const startSeq = writeSeq.current
    if (isLocalPreview) {
      const local = await current.client.snapshot()
      if (!current.alive) return
      if (writeSeq.current === startSeq) setSnapshot(local)
      setStatus('本地预览')
      setError('')
      return
    }
    if (refreshing.current) return refreshing.current
    // 本轮同步期间发生本地写入时，本轮结果对界面已过期；结束后补一次刷新拿最新状态。
    let superseded = false
    const operation = (async () => {
      setStatus('同步中')
      try {
        const result = await current.client.sync(sessionRef.current, async () => {
          const renewed = await authClient.refresh(sessionRef.current)
          sessionRef.current = renewed
          return renewed
        })
        if (!current.alive) return
        if (writeSeq.current === startSeq) { setSnapshot(result.snapshot) } else { superseded = true }
        const names = { ok: '已同步', disabled: '服务未配置', paused: '同步已暂停', deferred: '等待重试', pending: '仍有待同步内容' }
        setStatus(names[result.status])
        setError('')
      } catch (value) {
        if (!current.alive) return
        if (value instanceof SessionExpiredError) {
          expiredRef.current()
          return
        }
        setStatus(navigator.onLine ? '同步失败' : '离线保存')
        setError(value instanceof Error ? value.message : '同步暂时失败，本地内容已保留')
        try {
          const local = await current.client.snapshot()
          if (!current.alive) return
          if (writeSeq.current === startSeq) { setSnapshot(local) } else { superseded = true }
        } catch {
          /* 退出关闭后不再更新页面。 */
        }
      }
    })()
    refreshing.current = operation
    await operation.finally(() => {
      if (refreshing.current === operation) refreshing.current = undefined
    })
    // 本轮被本地写入超越：用一次新同步把远端变化补上，而不是回滚到旧界面。
    if (superseded && current.alive) void refresh()
  }, [isLocalPreview])

  const changed = useCallback(async (): Promise<void> => {
    const current = runtime.current
    if (!current?.alive || !current.ready) throw new Error('工作区尚未准备好')
    // 本地写入已经提交，先标序号再读快照；更早开始的同步不能再用旧结果覆盖这次变化。
    const seq = ++writeSeq.current
    const local = await current.client.snapshot()
    if (!current.alive) return
    if (writeSeq.current === seq) setSnapshot(local)
    current.channel?.postMessage('changed')
    void refresh()
  }, [refresh])

  useEffect(() => {
    const current: WorkspaceRuntime = {
      client: new SyncClient(),
      crypto: new AccountCrypto(session.user.id),
      alive: true,
      ready: false,
    }
    runtime.current = current
    setReady(false)
    setSnapshot(empty)
    if (typeof BroadcastChannel !== 'undefined') {
      current.channel = new BroadcastChannel(`everytalk:${session.user.id}`)
      current.channel.onmessage = () => {
        void refresh()
      }
    }
    void current.client
      .open(session.user.id)
      .then(async () => {
        if (!current.alive) return
        if (isLocalPreview) {
          await ensureLocalPreviewSeed(current.client)
        }
        if (!current.alive) return
        current.ready = true
        const local = await current.client.snapshot()
        if (!current.alive) return
        setSnapshot(local)
        setReady(true)
        await refresh()
      })
      .catch(value => {
        if (current.alive) {
          setError(value instanceof Error ? value.message : '无法打开浏览器数据库')
          setStatus('无法打开')
        }
      })
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refresh()
    }, 20_000)
    const online = () => {
      void refresh()
    }
    const visible = () => {
      if (document.visibilityState === 'visible') void refresh()
    }
    window.addEventListener('online', online)
    document.addEventListener('visibilitychange', visible)
    return () => {
      current.alive = false
      current.client.close()
      current.crypto.close()
      current.channel?.close()
      window.clearInterval(interval)
      window.removeEventListener('online', online)
      document.removeEventListener('visibilitychange', visible)
      refreshing.current = undefined
      if (runtime.current === current) runtime.current = undefined
    }
  }, [session.user.id, isLocalPreview, refresh])
  return { runtime, sessionRef, snapshot, status, error, setError, ready, refresh, changed }
}
