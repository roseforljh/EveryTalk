/* eslint-disable @next/next/no-img-element */
'use client'

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import Link from 'next/link'
import {
  ArrowDown,
  ArrowUp,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Code2,
  Copy,
  Database,
  Edit3,
  Eye,
  FileDown,
  Flag,
  Folder,
  FolderMinus,
  FolderOpen,
  Globe,
  Image as ImageIcon,
  LogOut,
  Maximize2,
  Menu,
  Mic,
  MoreHorizontal,
  MoreVertical,
  PanelLeft,
  PanelLeftClose,
  Paperclip,
  Pin,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  Settings,
  SlidersHorizontal,
  Sparkles,
  Square,
  Trash2,
  Info,
  Palette,
  Server,
  Share2,
  Volume2,
  Wrench,
  Blocks,
  Camera,
  Terminal,
  X,
} from 'lucide-react'
import { ThemeToggle } from '@/components/ui/ThemeProvider'
import { builtinModels, isBuiltinModel, withBuiltinModels } from './builtin-models'
import { SettingsPanel, type SettingsTab } from './SettingsPanel'
import {
  canonicalSettingKey,
  contractKindOf,
  isCanonicalUuid,
  isLegacySettingKind,
  settingViewToContract,
  settingsViewRecords,
  writeSettingExtras,
} from './settings-contract'

/**
 * 1:1 还原安卓端 ParticlesBackground.kt 的 3D 球体粒子点云背景特效组件。
 * 采用确定性种子 7920 在单位球内生成 200 颗粒子，随时间执行三维旋转矩阵、三轴正弦漂移、透视投影、景深衰减与闪烁。
 */
interface SphereParticle {
  x: number
  y: number
  z: number
  r0: number
  r1: number
  r2: number
  r3: number
}

const PARTICLE_SPEED = 0.1
const PARTICLE_SPREAD = 10
const PARTICLE_DEPTH = 10
const PARTICLE_CAMERA = 20
const PARTICLE_NEAR = 0.1
const PARTICLE_FAR = 100
const PARTICLE_BASE_SIZE = 100
const PARTICLE_TWINKLE = 0.3
const PARTICLE_INTRO_SECONDS = 1.8
const PARTICLE_FOCAL = 1 / Math.tan((7.5 * Math.PI) / 180)

function createSeededParticles(count = 200, seed = 7920): SphereParticle[] {
  let state = seed >>> 0
  const nextFloat = (): number => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
  const list: SphereParticle[] = []
  for (let i = 0; i < count; i++) {
    let x = 0
    let y = 0
    let z = 0
    let lenSq = 0
    do {
      x = nextFloat() * 2 - 1
      y = nextFloat() * 2 - 1
      z = nextFloat() * 2 - 1
      lenSq = x * x + y * y + z * z
    } while (lenSq > 1 || lenSq === 0)
    const r = Math.cbrt(nextFloat())
    const invLen = r / Math.sqrt(lenSq)
    list.push({
      x: x * invLen,
      y: y * invLen,
      z: z * invLen,
      r0: nextFloat(),
      r1: nextFloat(),
      r2: nextFloat(),
      r3: nextFloat(),
    })
  }
  return list
}

const GLOBAL_SPHERE_PARTICLES = createSeededParticles(200, 7920)

function ParticlesBackground({ forceWhite = false }: { forceWhite?: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    let rafId = 0
    const startMs = performance.now()

    const renderFrame = (nowMs: number) => {
      const parent = canvas.parentElement
      const cssW = parent ? parent.clientWidth : window.innerWidth
      const cssH = parent ? parent.clientHeight : window.innerHeight
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      const targetW = Math.max(1, Math.round(cssW * dpr))
      const targetH = Math.max(1, Math.round(cssH * dpr))
      if (canvas.width !== targetW || canvas.height !== targetH) {
        canvas.width = targetW
        canvas.height = targetH
      }

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.clearRect(0, 0, cssW, cssH)

      const elapsed = Math.max(0, (nowMs - startMs) / 1000)
      const t = elapsed * PARTICLE_SPEED
      const reveal = Math.min(1, Math.max(0, elapsed / PARTICLE_INTRO_SECONDS))

      const minSide = Math.min(cssW, cssH)
      const centerX = cssW * 0.5
      const centerY = cssH * 0.5
      const scaleX = minSide * 0.5
      const scaleY = minSide * 0.5

      const rx = Math.sin(t * 0.2) * 0.1
      const ry = Math.cos(t * 0.5) * 0.15
      const rz = t * 0.6

      const cx = Math.cos(rx)
      const sx = Math.sin(rx)
      const cy = Math.cos(ry)
      const sy = Math.sin(ry)
      const cz = Math.cos(rz)
      const sz = Math.sin(rz)

      const m00 = cy * cz
      const m01 = sx * sy * cz - cx * sz
      const m02 = cx * sy * cz + sx * sz
      const m10 = cy * sz
      const m11 = sx * sy * sz + cx * cz
      const m12 = cx * sy * sz - sx * cz
      const m20 = -sy
      const m21 = sx * cy
      const m22 = cx * cy

      const isLight =
        !forceWhite &&
        typeof document !== 'undefined' &&
        document.documentElement.dataset.theme === 'light'
      const rgb = isLight ? '20, 28, 46' : '255, 255, 255'
      const baseAlphaBoost = isLight ? 0.65 : 0.92

      for (let i = 0; i < GLOBAL_SPHERE_PARTICLES.length; i++) {
        const p = GLOBAL_SPHERE_PARTICLES[i]
        const px = p.x * PARTICLE_SPREAD + Math.sin(t * p.r2 + 6.2831855 * p.r0) * (0.1 + 1.4 * p.r0)
        const py = p.y * PARTICLE_SPREAD + Math.sin(t * p.r1 + 6.2831855 * p.r3) * (0.1 + 1.4 * p.r3)
        const pz = p.z * PARTICLE_DEPTH + Math.sin(t * p.r3 * 4 + 6.2831855 * p.r1) * (0.1 + 1.4 * p.r2)

        const wx = m00 * px + m01 * py + m02 * pz
        const wy = m10 * px + m11 * py + m12 * pz
        const wz = m20 * px + m21 * py + m22 * pz - PARTICLE_CAMERA

        const depth = -wz
        if (depth <= PARTICLE_NEAR || depth >= PARTICLE_FAR) continue

        const invZ = PARTICLE_FOCAL / depth
        const sxPx = centerX + wx * invZ * scaleX
        const syPx = centerY - wy * invZ * scaleY
        const radius = ((PARTICLE_BASE_SIZE * (0.5 + p.r0)) / depth) * 0.27

        if (sxPx + radius * 3 < 0 || sxPx - radius * 3 > cssW || syPx + radius * 3 < 0 || syPx - radius * 3 > cssH) {
          continue
        }

        const depthFactor = Math.min(1, Math.max(0, 1 - (depth - PARTICLE_NEAR) / (PARTICLE_FAR - PARTICLE_NEAR)))
        const twinkle =
          PARTICLE_TWINKLE > 0
            ? 1 - PARTICLE_TWINKLE * 0.5 + PARTICLE_TWINKLE * 0.5 * Math.sin(elapsed * 2 + p.r1 * 6.2831855)
            : 1
        const alpha = Math.min(1, Math.max(0, (0.28 + 0.72 * depthFactor) * twinkle * reveal * baseAlphaBoost))
        if (alpha <= 0.01) continue

        if (radius > 1.2) {
          ctx.beginPath()
          ctx.arc(sxPx, syPx, radius * 2.4, 0, Math.PI * 2)
          ctx.fillStyle = `rgba(${rgb}, ${(alpha * 0.14).toFixed(3)})`
          ctx.fill()
        }

        ctx.beginPath()
        ctx.arc(sxPx, syPx, Math.max(0.8, radius), 0, Math.PI * 2)
        ctx.fillStyle = `rgba(${rgb}, ${alpha.toFixed(3)})`
        ctx.fill()
      }

      rafId = window.requestAnimationFrame(renderFrame)
    }

    rafId = window.requestAnimationFrame(renderFrame)
    return () => window.cancelAnimationFrame(rafId)
  }, [forceWhite])

  return <canvas ref={canvasRef} className="et-particles-canvas" aria-hidden="true" />
}

/**
 * 1:1 还原安卓端 ChatInputArea.kt 中 liquidNeckWaist 与 liquidComposerPath 的水滴拉丝融合/分离动力学几何算法。
 */
function liquidNeckWaist(centerDistance: number, radius: number): number {
  if (centerDistance <= 0) return 0
  const t = Math.min(1, Math.max(0, (centerDistance - radius) / (radius * 1.5)))
  return t >= 1 ? 0 : radius * Math.sqrt(1 - t)
}

function traceRoundedRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number
) {
  const radius = Math.max(0, Math.min(r, w / 2, h / 2))
  ctx.beginPath()
  ctx.roundRect(x, y, Math.max(0, w), Math.max(0, h), radius)
}

/**
 * 底部输入区液态融合/分离容器（对齐安卓端 ChatInputArea.kt 弹簧参数 StiffnessMediumLow 与三次贝塞尔水滴桥接）。
 * - 合并态 (p = 0)：左侧 + 按钮与右侧输入胶囊完全融合成单一胶囊，无中间接缝，文本左内边距自动让位 48px；
 * - 分离态 (p = 0 -> 1)：+ 按钮水滴拉伸（48 - 4p + 18*sin(πp)），中间三次贝塞尔颈部桥接带随中心距拉长变细直至断开。
 */
function LiquidComposerRow({
  separated,
  plusSlot,
  pillSlot,
}: {
  separated: boolean
  plusSlot: (plusWidth: number) => React.ReactNode
  pillSlot: (style: { marginLeft: number; paddingLeft: number }) => React.ReactNode
}) {
  const rowRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const progressRef = useRef(separated ? 1 : 0)
  const velocityRef = useRef(0)
  const targetRef = useRef(separated ? 1 : 0)
  const [layoutMetrics, setLayoutMetrics] = useState(() => {
    const p = separated ? 1 : 0
    const plusRawWidth = 48 - 4 * p + 18 * Math.sin(Math.PI * p)
    const inputLeft = 28 * p * (1 + p)
    const textStartPadding = Math.min(48, Math.max(18, plusRawWidth - inputLeft + 2))
    return { plusWidth: plusRawWidth, inputLeft, textStartPadding }
  })

  const drawLiquidCanvas = useCallback((p: number) => {
    const row = rowRef.current
    const canvas = canvasRef.current
    if (!row || !canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const W = row.clientWidth
    const H = row.clientHeight
    if (W <= 0 || H <= 0) return

    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const pad = 16
    const cw = Math.round((W + pad * 2) * dpr)
    const ch = Math.round((H + pad * 2) * dpr)
    if (canvas.width !== cw || canvas.height !== ch) {
      canvas.width = cw
      canvas.height = ch
    }

    ctx.setTransform(dpr, 0, 0, dpr, pad * dpr, pad * dpr)
    ctx.clearRect(-pad, -pad, W + pad * 2, H + pad * 2)

    const isLight =
      typeof document !== 'undefined' && document.documentElement.dataset.theme === 'light'
    const bgColor = isLight ? '#FFFFFF' : '#1F1F1F'
    const borderColor = isLight ? 'rgba(15, 23, 42, 0.13)' : 'rgba(255, 255, 255, 0.11)'
    const shadowColor = isLight ? 'rgba(15, 23, 42, 0.08)' : 'rgba(0, 0, 0, 0.42)'

    const plusH = Math.min(H, 48 - 4 * p)
    const plusR = plusH / 2
    const cy = H / 2
    const plusRawWidth = 48 - 4 * p + 18 * Math.sin(Math.PI * p)
    const inputLeft = 28 * p * (1 + p)
    const inputW = Math.max(plusR * 2, W - inputLeft)
    const inputR = Math.min(24, H / 2)

    const x1 = plusRawWidth - plusR
    const x2 = inputLeft + plusR
    const waist = liquidNeckWaist(x2 - x1, plusR)

    const traceNeck = () => {
      if (waist <= 0.1) return false
      const sinA = Math.min(0.999, Math.max(0, (1 + waist / plusR) / 2))
      const cosA = Math.sqrt(Math.max(0, 1 - sinA * sinA))
      const p1x = x1 + plusR * cosA
      const p2x = x2 - plusR * cosA
      if (p2x <= p1x) return false
      const mid = (x1 + x2) / 2
      const h = (mid - p1x) * 0.55
      const topY = cy - plusR * sinA
      const botY = cy + plusR * sinA

      ctx.beginPath()
      ctx.moveTo(p1x, topY)
      ctx.bezierCurveTo(p1x + h, topY, mid - h, cy - waist, mid, cy - waist)
      ctx.bezierCurveTo(mid + h, cy - waist, p2x - h, topY, p2x, topY)
      ctx.lineTo(p2x, botY)
      ctx.bezierCurveTo(p2x - h, botY, mid + h, cy + waist, mid, cy + waist)
      ctx.bezierCurveTo(mid - h, cy + waist, p1x + h, botY, p1x, botY)
      ctx.closePath()
      return true
    }

    // 1. 绘制柔和环境阴影
    ctx.save()
    ctx.shadowColor = shadowColor
    ctx.shadowBlur = 14
    ctx.shadowOffsetY = 4
    ctx.fillStyle = bgColor
    traceRoundedRect(ctx, 0, cy - plusR, plusRawWidth, plusH, plusR)
    ctx.fill()
    traceRoundedRect(ctx, inputLeft, 0, inputW, H, inputR)
    ctx.fill()
    if (traceNeck()) ctx.fill()
    ctx.restore()

    // 2. 先以 2px 线宽描边三个几何体，再用不透明背景色填充内部，使重合边完全消除、仅保留外轮廓 1px 连续描边
    ctx.strokeStyle = borderColor
    ctx.lineWidth = 2
    traceRoundedRect(ctx, 0, cy - plusR, plusRawWidth, plusH, plusR)
    ctx.stroke()
    traceRoundedRect(ctx, inputLeft, 0, inputW, H, inputR)
    ctx.stroke()
    if (traceNeck()) ctx.stroke()

    ctx.fillStyle = bgColor
    traceRoundedRect(ctx, 0, cy - plusR, plusRawWidth, plusH, plusR)
    ctx.fill()
    traceRoundedRect(ctx, inputLeft, 0, inputW, H, inputR)
    ctx.fill()
    if (traceNeck()) ctx.fill()
  }, [])

  useEffect(() => {
    targetRef.current = separated ? 1 : 0
    let rafId = 0
    let lastTime = performance.now()

    const step = (now: number) => {
      const dt = Math.min(0.032, Math.max(0.001, (now - lastTime) / 1000))
      lastTime = now

      const target = targetRef.current
      let p = progressRef.current
      let v = velocityRef.current

      // 对齐安卓端 spring(dampingRatio = NoBouncy, stiffness = StiffnessMediumLow = 400f)
      const stiffness = 360
      const damping = 36
      const force = -stiffness * (p - target) - damping * v
      v += force * dt
      p += v * dt

      const done = Math.abs(target - p) < 0.002 && Math.abs(v) < 0.01
      if (done) {
        p = target
        v = 0
      }

      progressRef.current = p
      velocityRef.current = v

      const clampedP = Math.min(1, Math.max(0, p))
      const plusRawWidth = 48 - 4 * clampedP + 18 * Math.sin(Math.PI * clampedP)
      const inputLeft = 28 * clampedP * (1 + clampedP)
      const textStartPadding = Math.min(48, Math.max(18, plusRawWidth - inputLeft + 2))

      setLayoutMetrics({
        plusWidth: plusRawWidth,
        inputLeft,
        textStartPadding,
      })
      drawLiquidCanvas(clampedP)

      if (!done) {
        rafId = window.requestAnimationFrame(step)
      }
    }

    rafId = window.requestAnimationFrame(step)
    return () => window.cancelAnimationFrame(rafId)
  }, [separated, drawLiquidCanvas])

  useEffect(() => {
    const row = rowRef.current
    if (!row) return
    const rerender = () => drawLiquidCanvas(Math.min(1, Math.max(0, progressRef.current)))
    rerender()
    window.addEventListener('resize', rerender)
    window.addEventListener('storage', rerender)
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(rerender)
      ro.observe(row)
      return () => {
        ro.disconnect()
        window.removeEventListener('resize', rerender)
        window.removeEventListener('storage', rerender)
      }
    }
    return () => {
      window.removeEventListener('resize', rerender)
      window.removeEventListener('storage', rerender)
    }
  }, [drawLiquidCanvas])

  return (
    <div ref={rowRef} className={`et-chat-input-row ${separated ? 'is-separated' : 'is-connected'}`}>
      <canvas ref={canvasRef} className="et-liquid-composer-canvas" aria-hidden="true" />
      {plusSlot(layoutMetrics.plusWidth)}
      {pillSlot({
        marginLeft: layoutMetrics.inputLeft,
        paddingLeft: layoutMetrics.textStartPadding,
      })}
    </div>
  )
}

/**
 * 严格对齐安卓端 EveryTalkLoadingIndicator 的 Material 风格圆环进度指示器。
 */
function AndroidLoadingRing({ size = 18, strokeWidth = 2.2 }: { size?: number; strokeWidth?: number }) {
  const r = (size - strokeWidth) / 2
  const c = 2 * Math.PI * r
  return (
    <span
      className="et-android-ring"
      style={{ width: size, height: size }}
      role="progressbar"
      aria-label="加载中"
    >
      <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size}>
        <circle
          className="et-android-ring-circle"
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke="currentColor"
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * 0.25}
        />
      </svg>
    </span>
  )
}

/**
 * 用户长消息气泡平滑展开/收起容器（对齐安卓端 animateContentSize 与底部渐变遮罩动效）。
 * 实时测量内容真实高度并通过显式 max-height 像素值驱动 CSS 过渡动画。
 */
function UserBubbleClamp({
  isLong,
  expanded,
  onToggle,
  children,
}: {
  isLong: boolean
  expanded: boolean
  onToggle: () => void
  children: React.ReactNode
}) {
  const innerRef = useRef<HTMLDivElement>(null)
  const [contentHeight, setContentHeight] = useState(520)

  useLayoutEffect(() => {
    const el = innerRef.current
    if (!el || !isLong) return
    const measure = () => {
      const next = el.scrollHeight
      if (next > 0) setContentHeight(next)
    }
    measure()
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(measure)
      observer.observe(el)
      return () => observer.disconnect()
    }
  }, [isLong, expanded, children])

  if (!isLong) {
    return (
      <div className="et-user-clamp-wrap">
        <div className="et-message-text">{children}</div>
      </div>
    )
  }

  const targetMaxHeight = expanded ? `${Math.max(contentHeight + 12, 240)}px` : '210px'

  return (
    <>
      <div
        className={`et-user-clamp-wrap is-animatable ${expanded ? 'is-expanded' : 'is-collapsed'}`}
        style={{ maxHeight: targetMaxHeight }}
      >
        <div ref={innerRef} className="et-message-text">
          {children}
        </div>
        <div className={`et-user-fade ${expanded ? 'is-hidden' : ''}`} aria-hidden="true" />
      </div>
      <button type="button" className="et-user-expand-btn" onClick={onToggle}>
        <span>{expanded ? '收起' : '展开全文'}</span>
        <ChevronDown size={14} className={`et-user-expand-chevron ${expanded ? 'is-expanded' : ''}`} />
      </button>
    </>
  )
}

import { ModelIcon } from './ModelIcon'
import { authClient, authenticatedFetch, apiBaseUrl } from './auth'
import { useWorkspaceSync } from './use-workspace-sync'
import { useFileDownloads } from './use-file-downloads'
import { MessageFileLink, MessageFileText, messageFileParts } from './MessageFileLink'
import { MessageFilePreview } from './MessageFilePreview'
import { receiveFileForPreview } from './file-preview'
import { attachmentDownloadKey } from './file-downloads'
import {
  generate,
  generateImage,
  imageRequest,
  modelRequest,
  nativeWebSearch,
  withNativeWebSearch,
  type ExecutionStepItem,
  type ModelAttachment,
  type ModelResult,
} from './model'
import { readHistory, uploadResource } from './resources'
import { archiveDisplay, bodySegments, displaySegments, type ArchiveDisplay } from './message-segments'
import { isObject } from './sync-core'
import { syncKey, type AuthSession, type JsonObject, type StoredRecord, type SyncEntity } from './types'
import type { SyncClient } from './sync'
import { getSpeechRecognition, normalizeVoiceLanguage, speakText, type BrowserSpeechRecognition } from './voice'
import { normalizeSearchProvider, readSearchResults, type SearchResult } from './search'
import { conversationMemory } from './conversation-memory'
import { builtinTools } from './builtin-tools'
import { combineTools } from './model-tools'
import { blockedInputMessage, stableSystemPrompt } from './system-prompt'
import { reportCategories } from './report'

const str = (value: unknown, fallback = ''): string => (typeof value === 'string' ? value : fallback)
const valueOf = (record?: StoredRecord): JsonObject => (record && isObject(record.payload.value) ? record.payload.value : {})
const recordOf = (entity: SyncEntity, id: string, payload: JsonObject, deleted = false): StoredRecord => ({
  key: syncKey(entity, id),
  entity,
  id,
  payload,
  deleted,
})
const updated = (old?: StoredRecord): number => Math.max(Date.now(), Number(old?.payload.clientModifiedAt ?? 0) + 1)
const encodeGroupKey = (groupName: string): string =>
  btoa(unescape(encodeURIComponent(groupName.trim())))
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')

const MAX_MODEL_IMAGE_BYTES = 3 * 1024 * 1024
const MAX_GENERATED_IMAGE_BYTES = 32 * 1024 * 1024

/**
 * 与安卓端 ImageRatio.DEFAULT_RATIOS 对齐的生图比例预设表。
 */
const IMAGE_RATIO_OPTIONS: Array<{ ratio: string; label: string; size2k: string; widthPx: number; heightPx: number }> = [
  { ratio: '1:1', label: '方形 1:1', size2k: '1024×1024', widthPx: 18, heightPx: 18 },
  { ratio: '16:9', label: '横屏 16:9', size2k: '1344×768', widthPx: 22, heightPx: 13 },
  { ratio: '9:16', label: '竖屏 9:16', size2k: '768×1344', widthPx: 13, heightPx: 22 },
  { ratio: '4:3', label: '标准横版 4:3', size2k: '1184×864', widthPx: 20, heightPx: 15 },
  { ratio: '3:4', label: '标准竖版 3:4', size2k: '864×1184', widthPx: 15, heightPx: 20 },
  { ratio: '3:2', label: '摄影横版 3:2', size2k: '1248×832', widthPx: 21, heightPx: 14 },
  { ratio: '2:3', label: '摄影竖版 2:3', size2k: '832×1248', widthPx: 14, heightPx: 21 },
  { ratio: '21:9', label: '电影宽幅 21:9', size2k: '1536×672', widthPx: 24, heightPx: 11 },
]

/**
 * 解析顶部胶囊栏显示的模型家族名称与主题色，与安卓端 AppTopBar.kt 的 resolveTopBarModelDisplayInfo 保持一致。
 */
function resolveTopBarModelDisplayInfo(modelName: string, fallbackLabel: string): { label: string; color?: string; isGemini?: boolean } {
  const lower = modelName.toLowerCase()
  if (lower.includes('gemini')) return { label: 'Gemini', isGemini: true }
  if (lower.includes('gemma')) return { label: 'Gemma', color: '#2196F3' }
  if (lower.includes('gpt') || lower.includes('o1') || lower.includes('o3') || lower.includes('o4') || lower.includes('openai')) return { label: 'GPT' }
  if (lower.includes('claude') || lower.includes('anthropic')) return { label: 'Claude', color: '#D97757' }
  if (lower.includes('deepseek')) return { label: 'DeepSeek', color: '#4D6BFE' }
  if (lower.includes('qwen') || lower.includes('qwq') || lower.includes('通义')) return { label: 'Qwen', color: '#615CED' }
  if (lower.includes('doubao') || lower.includes('seedream')) return { label: 'Doubao', color: '#1E37FC' }
  if (lower.includes('kimi') || lower.includes('moonshot')) return { label: 'Kimi', color: '#1783FF' }
  if (lower.includes('glm') || lower.includes('zhipu')) return { label: 'GLM', color: '#3859FF' }
  return { label: fallbackLabel || '选择模型' }
}

/**
 * 清理思考内容的 Markdown 符号，用于与安卓端 ThinkingUI.kt 一致的 3 行纯文本思考预览卡。
 */
function stripMarkdownToPlainPreview(raw: string): string {
  return raw
    .replace(/```[\s\S]*?(?:```|$)/g, '[代码片段]')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*>\s+/gm, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/[*_`~]/g, '')
    .replace(/\n{2,}/g, '\n')
    .trim()
}

/**
 * 提取网页域名与网站图标地址，供网页胶囊和来源列表展示。
 */
function extractHost(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '') || url
  } catch {
    return url
  }
}

function siteFaviconUrl(url: string): string {
  const host = extractHost(url)
  return `https://www.google.com/s2/favicons?sz=64&domain_url=${encodeURIComponent(host)}`
}

/**
 * 从已持久化的消息记录中恢复工具执行步骤列表。
 */
function readExecutionSteps(raw: unknown): ExecutionStepItem[] {
  if (!Array.isArray(raw)) return []
  return raw
    .filter(isObject)
    .map(item => ({
      id: str(item.id, crypto.randomUUID()),
      type: (item.type === 'search' || item.type === 'web' ? item.type : 'tool') as 'search' | 'web' | 'tool',
      title: str(item.title, '调用工具'),
      labels: Array.isArray(item.labels) ? item.labels.filter((l): l is string => typeof l === 'string') : [],
      completed: item.completed !== false,
      isError: item.isError === true,
      resultPreview: typeof item.resultPreview === 'string' ? item.resultPreview : undefined,
    }))
}

/**
 * 上下文用量环形进度条组件（完全对齐安卓端 AiContextUsagePopup.kt 中的 ContextUsageRing 17dp 环形图）。
 * 进度 <= 50% 从绿色平滑过渡到琥珀色，> 50% 过渡到警示红色。
 */
function ContextUsageRing({ fraction, hasData }: { fraction: number; hasData: boolean }) {
  const stroke = 2
  const radius = 7
  const circumference = 2 * Math.PI * radius
  const p = Math.max(0, Math.min(1, fraction))
  const strokeDashoffset = circumference * (1 - p)
  const color = !hasData
    ? 'rgba(255, 255, 255, 0.32)'
    : p <= 0.5
      ? '#22C55E'
      : p <= 0.8
        ? '#F59E0B'
        : '#EF4444'
  return (
    <svg className="et-context-ring-svg" viewBox="0 0 18 18" aria-hidden="true">
      <circle cx="9" cy="9" r={radius} fill="none" stroke="currentColor" strokeWidth={stroke} opacity={0.16} />
      {hasData && p > 0 && (
        <circle
          cx="9"
          cy="9"
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth={stroke}
          strokeDasharray={circumference}
          strokeDashoffset={strokeDashoffset}
          strokeLinecap="round"
        />
      )}
    </svg>
  )
}

/**
 * 将消息文本拆分为普通文本段落与围栏代码块（对齐安卓端 CodeBlockCard 渲染）。
 */
type ParsedMessageSegment =
  | { kind: 'text'; content: string }
  | { kind: 'code'; language: string; code: string }

function parseMessageSegments(text: string): ParsedMessageSegment[] {
  const regex = /```([a-zA-Z0-9_+-]*)\n?([\s\S]*?)```/g
  const segments: ParsedMessageSegment[] = []
  let lastIndex = 0
  for (const match of text.matchAll(regex)) {
    const start = match.index ?? 0
    if (start > lastIndex) {
      segments.push({ kind: 'text', content: text.slice(lastIndex, start) })
    }
    segments.push({
      kind: 'code',
      language: (match[1] || 'code').trim().toLowerCase(),
      code: (match[2] || '').replace(/\n$/, ''),
    })
    lastIndex = start + match[0].length
  }
  if (lastIndex < text.length || segments.length === 0) {
    segments.push({ kind: 'text', content: text.slice(lastIndex) })
  }
  return segments
}

/**
 * 代码块卡片组件（对齐安卓端 CodeBlockCard.kt：24px 圆角、吸顶语言栏、HTML/SVG 预览切换、全屏查看与一键复制）。
 */
function CodeBlockCard({
  language,
  code,
  onOpenFullscreen,
}: {
  language: string
  code: string
  onOpenFullscreen: (item: { language: string; code: string; preview: boolean }) => void
}) {
  const canPreview = language === 'html' || language === 'svg' || language === 'xml'
  const [previewMode, setPreviewMode] = useState(false)
  const [copiedCode, setCopiedCode] = useState(false)

  const handleCopy = async () => {
    await navigator.clipboard.writeText(code)
    setCopiedCode(true)
    window.setTimeout(() => setCopiedCode(false), 1500)
  }

  return (
    <div className="et-code-card">
      <div className="et-code-header">
        <span className="et-code-lang">{language || 'CODE'}</span>
        <div className="et-code-actions">
          {canPreview && (
            <button
              type="button"
              className={`et-code-capsule ${previewMode ? 'is-active' : ''}`}
              onClick={() => setPreviewMode(prev => !prev)}
            >
              {previewMode ? <Code2 size={12} /> : <Eye size={12} />}
              <span>{previewMode ? '代码' : '预览'}</span>
            </button>
          )}
          <button
            type="button"
            className="et-code-capsule"
            title="全屏查看代码"
            onClick={() => onOpenFullscreen({ language: language || 'code', code, preview: previewMode })}
          >
            <Maximize2 size={12} />
            <span>全屏</span>
          </button>
          <button type="button" className="et-code-capsule" title="复制代码" onClick={() => void handleCopy()}>
            {copiedCode ? <Check size={12} /> : <Copy size={12} />}
            <span>{copiedCode ? '已复制' : '复制'}</span>
          </button>
        </div>
      </div>
      {previewMode && canPreview ? (
        <iframe
          title={`${language} 预览`}
          className="et-code-preview-frame"
          sandbox=""
          srcDoc={language === 'svg' ? `<!doctype html><html><body style="margin:0;display:grid;place-items:center;min-height:100vh">${code}</body></html>` : code}
        />
      ) : (
        <pre className="et-code-pre">
          <code>{code}</code>
        </pre>
      )}
    </div>
  )
}

/**
 * AI 思考与工具执行链内联组件（对齐安卓端 ThinkingUI.kt：支持扫光文字、耗时统计、3 行思考预览、工具聚合组及自动折叠）。
 */
function ThinkingExecutionBlock({
  reasoning,
  steps,
  searchResults,
  isStreaming,
  hasMainText,
  activeStatus,
  durationSeconds,
  onOpenTimeline,
}: {
  reasoning: string
  steps: ExecutionStepItem[]
  searchResults: SearchResult[]
  isStreaming: boolean
  hasMainText: boolean
  activeStatus?: string
  durationSeconds?: number
  onOpenTimeline: () => void
}) {
  const shouldAutoExpand = isStreaming && !hasMainText
  const [manualExpanded, setManualExpanded] = useState<boolean | undefined>(undefined)
  useEffect(() => {
    if (isStreaming && hasMainText) setManualExpanded(false)
  }, [isStreaming, hasMainText])

  const expanded = manualExpanded ?? shouldAutoExpand
  const plainPreview = useMemo(() => {
    if (!reasoning.trim()) return ''
    const lines = stripMarkdownToPlainPreview(reasoning).split('\n').filter(Boolean)
    return lines.slice(-3).join('\n')
  }, [reasoning])

  const headerLabel = useMemo(() => {
    if (isStreaming && !hasMainText) {
      const base = activeStatus || (reasoning.trim() ? '思考中' : '等待首个响应')
      return typeof durationSeconds === 'number' ? `${base} · ${durationSeconds}s` : base
    }
    if (typeof durationSeconds === 'number' && durationSeconds > 0) {
      return `已思考 ${durationSeconds} 秒`
    }
    if (reasoning.trim()) return '思考过程'
    if (steps.length > 0) return `执行了 ${steps.length} 步工具调用`
    if (searchResults.length > 0) return `检索了 ${searchResults.length} 个网页来源`
    return '思考完成'
  }, [isStreaming, hasMainText, activeStatus, reasoning, durationSeconds, steps.length, searchResults.length])

  const searchSteps = steps.filter(s => s.type === 'search')
  const webSteps = steps.filter(s => s.type === 'web')
  const toolSteps = steps.filter(s => s.type === 'tool')

  return (
    <div className="et-exec-chain">
      <div className="et-exec-header-row">
        <button
          type="button"
          className="et-exec-header"
          onClick={() => setManualExpanded(prev => !(prev ?? shouldAutoExpand))}
        >
          <span className={isStreaming && !hasMainText ? 'et-shimmer-text' : ''}>{headerLabel}</span>
          <ChevronDown size={14} className={`et-exec-chevron ${expanded ? 'is-expanded' : ''}`} />
        </button>
      </div>

      <div className={`et-exec-collapsible ${expanded ? 'is-open' : ''}`}>
        <div className="et-exec-collapsible-inner">
          <div className="et-exec-body">
            {plainPreview && (
              <div
                className={`et-reasoning-preview-card ${isStreaming && !hasMainText ? 'et-shimmer-text' : ''}`}
                title="点击查看完整思考与执行时间线"
                onClick={onOpenTimeline}
              >
                {plainPreview}
              </div>
            )}

            {steps.length > 0 && (
              <div className="et-exec-tool-group">
                {(searchSteps.length > 0 || webSteps.length > 0 || toolSteps.length > 0) && (
                  <button type="button" className="et-exec-group-btn" onClick={onOpenTimeline}>
                    <span>
                      {[
                        searchSteps.length ? `执行了 ${searchSteps.length} 次搜索` : '',
                        webSteps.length ? `读取了 ${webSteps.length} 个网页` : '',
                        toolSteps.length ? `调用了 ${toolSteps.length} 次工具` : '',
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                    <ChevronRight size={13} />
                  </button>
                )}
                {steps.map(step => (
                  <div key={step.id} className="et-exec-inline-step">
                    <span
                      className={`et-exec-step-dot ${
                        !step.completed ? 'is-active' : step.isError ? 'is-error' : 'is-done'
                      }`}
                    />
                    <span className={!step.completed ? 'et-shimmer-text' : ''}>{step.title}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

/** 把图片接口返回的 data URL 变成附件文件，正文只保存稳定文字和附件元数据。 */
function dataUrlFile(dataUrl: string, index: number): File {
  const match = /^data:(image\/[a-z0-9.+-]+);base64,([a-z0-9+/=]+)$/i.exec(dataUrl)
  if (!match) throw new Error('图片返回格式无效')
  const binary = atob(match[2])
  const bytes = new Uint8Array(binary.length)
  for (let offset = 0; offset < binary.length; offset++) bytes[offset] = binary.charCodeAt(offset)
  if (bytes.length < 1 || bytes.length > MAX_GENERATED_IMAGE_BYTES) throw new Error('生成图片超过 32 MiB 限制')
  return new File([bytes], `generated-${index + 1}.${match[1].split('/')[1].replace('jpeg', 'jpg')}`, { type: match[1] })
}

/**
 * 与安卓端 AiContentReportCategory 完全对齐的举报原因副标题说明。
 */
const REPORT_CATEGORY_DESCRIPTIONS: Record<string, string> = {
  CHILD_SAFETY: '涉及未成年人不当、危险或违法内容',
  SEXUAL_CONTENT: '露骨色情、性暗示或成人不当内容',
  VIOLENCE_SELF_HARM: '宣扬暴力、自残、极端或危险行为',
  HATE_HARASSMENT: '歧视、侮辱、骚扰或人身攻击',
  DECEPTION_IMPERSONATION: '钓鱼诈骗、虚假身份或误导性欺骗',
  MALICIOUS_CODE: '病毒木马、漏洞利用或网络攻击指令',
  OTHER: '其他违反法律法规或社区规范的内容',
}

/** 图片缩略图与文件预览共享本地缓存，消息刷新时不再次请求远端文件；支持点击打开全屏大图与作为参考图继续编辑。 */
function SyncedImage({
  attachment,
  records,
  client,
  session,
  refreshSession,
  onOpenLightbox,
}: {
  attachment: StoredRecord
  records: StoredRecord[]
  client: SyncClient
  session: AuthSession
  refreshSession: () => Promise<AuthSession>
  onOpenLightbox?: (info: { attachment: StoredRecord; src: string; file: File }) => void
}) {
  const [source, setSource] = useState('')
  const [loadedFile, setLoadedFile] = useState<File>()
  useEffect(() => {
    let cancelled = false
    let objectUrl = ''
    void receiveFileForPreview(client, session, records, attachment, refreshSession)
      .then(file => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(file)
        setSource(objectUrl)
        setLoadedFile(file)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [attachment, client, records, session, refreshSession])
  return source ? (
    <img
      src={source}
      alt={str(attachment.payload.name, '图片')}
      style={{ cursor: onOpenLightbox ? 'zoom-in' : undefined }}
      onClick={() => {
        if (onOpenLightbox && loadedFile) onOpenLightbox({ attachment, src: source, file: loadedFile })
      }}
    />
  ) : (
    <span className="et-image-loading">加载图片…</span>
  )
}

/**
 * 只把小图片转成模型需要的 data URL。
 * 原文件已经由附件上传保存，非图片和超出本次模型预算的文件继续保留为可下载附件。
 */
async function modelAttachments(files: File[]): Promise<ModelAttachment[]> {
  let total = 0
  const result: ModelAttachment[] = []
  for (const file of files) {
    if (!file.type.startsWith('image/') || file.size < 1 || total + file.size > MAX_MODEL_IMAGE_BYTES) continue
    const bytes = new Uint8Array(await file.arrayBuffer())
    let binary = ''
    for (let offset = 0; offset < bytes.length; offset += 0x8000) binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000))
    result.push({ mime: file.type, dataUrl: `data:${file.type};base64,${btoa(binary)}` })
    total += file.size
  }
  return result
}

/**
 * 聊天主工作区组件。
 * 完整对齐 EveryTalk 安卓端的顶栏胶囊、分组抽屉、AI 思考与工具时间线、代码块卡片、网页来源弹窗、生图比例弹窗、语音大屏与账号应用中心。
 */
export function Workspace({ session, onSignOut }: { session: AuthSession; onSignOut: () => Promise<void> }) {
  const sync = useWorkspaceSync(session, () => {
    void onSignOut()
  })
  const { snapshot, ready, runtime, sessionRef, changed, refresh, setError } = sync
  const [selected, setSelected] = useState<string>()
  const [draft, setDraft] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [query, setQuery] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [mobile, setMobile] = useState(false)
  const [drawerCollapsed, setDrawerCollapsed] = useState(false)
  const [sidebarWidth, setSidebarWidth] = useState(300)
  const [sidebarResizing, setSidebarResizing] = useState(false)
  const [settings, setSettings] = useState<SettingsTab>()
    const [inputFocused, setInputFocused] = useState(false)

  // 读取并持久化 QoneAgent 风格的可拖拽侧边栏宽度与折叠状态
  useEffect(() => {
    try {
      const savedWidth = Number(localStorage.getItem('everytalk-web-sidebar-width'))
      if (Number.isFinite(savedWidth) && savedWidth >= 240 && savedWidth <= 460) {
        setSidebarWidth(Math.round(savedWidth))
      }
      const savedCollapsed = localStorage.getItem('everytalk-web-sidebar-collapsed')
      if (savedCollapsed === '1') setDrawerCollapsed(true)
    } catch {
      /* 忽略存储受限 */
    }
  }, [])

  const toggleDesktopDrawer = useCallback((nextCollapsed?: boolean) => {
    setDrawerCollapsed(prev => {
      const next = nextCollapsed ?? !prev
      try {
        localStorage.setItem('everytalk-web-sidebar-collapsed', next ? '1' : '0')
      } catch {
        /* 忽略存储受限 */
      }
      return next
    })
  }, [])

  const resetSidebarWidth = useCallback(() => {
    setSidebarWidth(300)
    try {
      localStorage.setItem('everytalk-web-sidebar-width', '300')
    } catch {
      /* 忽略存储受限 */
    }
  }, [])

  const handleSidebarResizeStart = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (event.button !== 0 || drawerCollapsed) return
      event.preventDefault()
      const startX = event.clientX
      const startWidth = sidebarWidth
      let latestWidth = startWidth
      setSidebarResizing(true)
      document.body.style.cursor = 'col-resize'
      document.body.style.userSelect = 'none'

      const handleMove = (moveEvent: PointerEvent) => {
        const rawWidth = startWidth + (moveEvent.clientX - startX)
        if (rawWidth <= 136) {
          finish()
          toggleDesktopDrawer(true)
          return
        }
        latestWidth = Math.min(460, Math.max(240, Math.round(rawWidth)))
        setSidebarWidth(latestWidth)
      }

      const finish = () => {
        window.removeEventListener('pointermove', handleMove)
        window.removeEventListener('pointerup', finish)
        window.removeEventListener('pointercancel', finish)
        setSidebarResizing(false)
        document.body.style.cursor = ''
        document.body.style.userSelect = ''
        try {
          localStorage.setItem('everytalk-web-sidebar-width', String(latestWidth))
        } catch {
          /* 忽略存储受限 */
        }
      }

      window.addEventListener('pointermove', handleMove)
      window.addEventListener('pointerup', finish)
      window.addEventListener('pointercancel', finish)
    },
    [drawerCollapsed, sidebarWidth, toggleDesktopDrawer]
  )

  // 悬浮卡片菜单状态（对齐 AppFloatingCardPopup）
  const [modelDropdownOpen, setModelDropdownOpen] = useState(false)
  const [topMoreOpen, setTopMoreOpen] = useState(false)
  const [fnPanelOpen, setFnPanelOpen] = useState(false)
  const [sessionMenuId, setSessionMenuId] = useState<string>()
  const [groupMenuName, setGroupMenuName] = useState<string>()
  const [aiMenuMessageId, setAiMenuMessageId] = useState<string>()
  const [aiMenuPickerMode, setAiMenuPickerMode] = useState(false)
  const [contextPopupMessageId, setContextPopupMessageId] = useState<string>()
  const [expandedUserMessages, setExpandedUserMessages] = useState<Record<string, boolean>>({})

  // 分组折叠与本地空分组列表
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({ '工作与调研': true })
  const [groupSectionExpanded, setGroupSectionExpanded] = useState(true)
  const [pinnedExpanded, setPinnedExpanded] = useState(true)
  const customGroupsKey = `everytalk-web-groups:${session.user.id}`
  const [customGroups, setCustomGroups] = useState<string[]>([])
  useEffect(() => {
    try {
      const saved: unknown = JSON.parse(localStorage.getItem(customGroupsKey) ?? '[]')
      setCustomGroups(Array.isArray(saved) ? saved.filter((g): g is string => typeof g === 'string' && g.trim().length > 0) : [])
    } catch {
      setCustomGroups([])
    }
  }, [customGroupsKey])
  const saveCustomGroups = (next: string[]) => {
    const unique = [...new Set(next.map(item => item.trim()).filter(Boolean))]
    setCustomGroups(unique)
    try {
      localStorage.setItem(customGroupsKey, JSON.stringify(unique))
    } catch {
      /* 忽略存储受限 */
    }
  }

  // 各类与安卓端对齐的对话框状态
  const [renameDialog, setRenameDialog] = useState<{ session: StoredRecord; title: string }>()
  const [deleteDialog, setDeleteDialog] = useState<StoredRecord>()
  const [clearAllOpen, setClearAllOpen] = useState(false)
  const [createGroupOpen, setCreateGroupOpen] = useState(false)
  const [newGroupName, setNewGroupName] = useState('')
  const [renameGroupDialog, setRenameGroupDialog] = useState<{ oldName: string; newName: string }>()
  const [deleteGroupDialog, setDeleteGroupDialog] = useState<string>()
  const [moveGroupDialog, setMoveGroupDialog] = useState<StoredRecord>()
  const [systemPromptOpen, setSystemPromptOpen] = useState(false)
  const [systemPromptDraft, setSystemPromptDraft] = useState('')
  const [ratioDialogOpen, setRatioDialogOpen] = useState(false)
  const [aboutOpen, setAboutOpen] = useState(false)
  const [voiceOverlayOpen, setVoiceOverlayOpen] = useState(false)
  const [sourcesDialog, setSourcesDialog] = useState<SearchResult[]>()
  const [fullscreenCode, setFullscreenCode] = useState<{ language: string; code: string; preview: boolean }>()
  const [confirmRegenerate, setConfirmRegenerate] = useState<{
    message: StoredRecord
    modelKey: string
    modelLabel: string
  }>()
  const [imageLightbox, setImageLightbox] = useState<{
    attachment: StoredRecord
    src: string
    file: File
  }>()
  const [timelineModal, setTimelineModal] = useState<{
    reasoning: string
    steps: ExecutionStepItem[]
    searchResults: SearchResult[]
    durationSeconds?: number
    isStreaming?: boolean
  }>()

  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [streaming, setStreaming] = useState<ModelResult & { sessionId: string; startedAt: number }>()
  const [elapsedSeconds, setElapsedSeconds] = useState(0)
  const [listening, setListening] = useState(false)
  const [speakingMessage, setSpeakingMessage] = useState('')
  const [showScrollBottom, setShowScrollBottom] = useState(false)
  const controller = useRef<AbortController | undefined>(undefined)
  const recognition = useRef<BrowserSpeechRecognition | undefined>(undefined)
  const voiceBase = useRef('')
  const [history, setHistory] = useState<{ id: string; value: JsonObject }>()
  // 大消息的完整正文来自加密归档，只用于当前页面显示，绝不写回同步队列。
  const [archiveViews, setArchiveViews] = useState<Record<string, ArchiveDisplay>>({})
  const archiveAttempts = useRef(new Map<string, number>())
  const archiveLoading = useRef(false)
  const [previewFile, setPreviewFile] = useState<File>()
  useEffect(() => {
    setPreviewFile(undefined)
  }, [session.user.id])
  const [copied, setCopied] = useState('')
  const [fileSharing, setFileSharing] = useState(false)
  const [report, setReport] = useState<{ message: StoredRecord; category: string; details: string; sending: boolean }>()
  // 与安卓本地举报记录一样，同一条消息只举报一次；按账号保存在本机。
  const reportedKey = `everytalk-web-reported:${session.user.id}`
  const [reported, setReported] = useState<string[]>([])
  useEffect(() => {
    try {
      const saved: unknown = JSON.parse(localStorage.getItem(reportedKey) ?? '[]')
      setReported(Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : [])
    } catch {
      setReported([])
    }
  }, [reportedKey])

  const conversationScrollRef = useRef<HTMLDivElement>(null)
  const messagesEnd = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLTextAreaElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const imageInput = useRef<HTMLInputElement>(null)
  const cameraInput = useRef<HTMLInputElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const liveRecords = useRef(snapshot.records)
  liveRecords.current = snapshot.records

  const records = useMemo(() => withBuiltinModels(snapshot.records), [snapshot.records])
  // 设置面板仍按网页旧 kind/字段渲染，同步合同值在这里转换；保存时 saveSetting 再转回合同。
  const settingsPanelRecords = useMemo(() => settingsViewRecords(records, session.user.id), [records, session.user.id])
  const imageClient = runtime.current?.client
  const settingsRows = useMemo(() => records.filter(r => r.entity === 'user_settings' && !r.deleted), [records])
  const setting = (kind: string, key: string) => settingsRows.find(r => r.payload.kind === kind && r.payload.key === key)
  const models = settingsRows.filter(r => r.payload.kind === 'api_configs')
  const pinned = new Set(settingsRows.filter(r => r.payload.kind === 'pinned_items').map(r => str(r.payload.key)))
  const memberships = settingsRows.filter(r => r.payload.kind === 'group_members')
  const groups = useMemo(
    () => [...new Set([...memberships.map(r => str(valueOf(r).groupName)).filter(Boolean), ...customGroups])],
    [memberships, customGroups]
  )
  const sessions = useMemo(
    () =>
      records
        .filter(r => r.entity === 'sessions' && !r.deleted)
        .sort((a, b) => Number(b.payload.lastModifiedTimestamp ?? 0) - Number(a.payload.lastModifiedTimestamp ?? 0)),
    [records]
  )
  const sortedSessions = [...sessions].sort((a, b) => Number(pinned.has(b.id)) - Number(pinned.has(a.id)))
  const activeId = selected && sessions.some(s => s.id === selected) ? selected : sessions[0]?.id
  const active = sessions.find(s => s.id === activeId)
  const messages = useMemo(
    () =>
      records
        .filter(r => r.entity === 'messages' && !r.deleted && r.payload.sessionId === activeId)
        .sort((a, b) => Number(a.payload.timestamp) - Number(b.payload.timestamp)),
    [records, activeId]
  )
  const conversation = setting('conversation_settings', activeId ?? '')
  const conversationOptions = valueOf(conversation)
  const webSearchEnabled = conversationOptions.webSearchEnabled === true
  const mcpEnabled = conversationOptions.mcpEnabled === true
  const agentEnabled = conversationOptions.agentEnabled === true
  const currentSystemPrompt = str(conversationOptions.systemPrompt)
  const promptEnabled = conversationOptions.promptEnabled === true && Boolean(currentSystemPrompt.trim())
  const imageAspectRatio = str(conversationOptions.imageAspectRatio)
  const imageQualitySize = (str(conversationOptions.imageQualitySize) === '4K' ? '4K' : '2K') as '2K' | '4K'
  const currentRatioMeta = IMAGE_RATIO_OPTIONS.find(item => item.ratio === imageAspectRatio)

  const searchSetting = setting('selected_models', 'web_search')
  const searchConfigs = settingsRows.filter(record => record.payload.kind === 'web_search_configs')
  const selectedSearchId = str(valueOf(searchSetting).providerId)
  const selectedSearch = searchConfigs.find(record => str(record.payload.key) === selectedSearchId) ?? searchConfigs[0]
  // 旧图片会话仅在尚未保存逐会话模型时沿用原图片默认选择。
  const imageSession = active?.payload.isImageGeneration === true
  const preferredModel = str(valueOf(conversation).modelId, str(valueOf(setting('selected_models', imageSession ? 'image' : 'text')).configId))
  // 没有选择时按会话类型取第一个同类模型，避免新账号只有内置生图模型时文字会话默认进入生图。
  const selectedModel = models.find(m => m.payload.key === preferredModel) ?? models.find(m => (valueOf(m).imageConfig === true) === imageSession) ?? models[0]
  const imageMode = valueOf(selectedModel).imageConfig === true
  const modelLabel = str(valueOf(selectedModel).name, str(valueOf(selectedModel).model, '选择模型'))
  const topBarModel = resolveTopBarModelDisplayInfo(str(valueOf(selectedModel).model, modelLabel), modelLabel)

  const voiceSetting = settingsRows.find(record => record.payload.kind === 'voice_configs')
  const voiceLanguage = normalizeVoiceLanguage(valueOf(voiceSetting).language)
  const accountProfile = valueOf(setting('account_profile', 'default'))
  const name = str(
    accountProfile.displayName,
    str(session.user.user_metadata?.everytalk_name, str(session.user.user_metadata?.full_name, session.user.email?.split('@')[0] ?? 'EveryTalk 用户'))
  )

  // 实时更新思考与工具执行秒表（与安卓端 LoadingStageIndicator / ExecutionChainHeader 对齐）
  const streamingStartedAt = streaming?.startedAt
  useEffect(() => {
    if (!streamingStartedAt) {
      setElapsedSeconds(0)
      return
    }
    const updateTimer = () => {
      setElapsedSeconds(Math.max(0, Math.floor((Date.now() - streamingStartedAt) / 1000)))
    }
    updateTimer()
    const timer = window.setInterval(updateTimer, 500)
    return () => window.clearInterval(timer)
  }, [streamingStartedAt])

  useEffect(
    () => () => {
      controller.current?.abort()
      recognition.current?.stop()
      window.speechSynthesis?.cancel()
    },
    []
  )
  useEffect(() => {
    setFileSharing(typeof navigator.share === 'function' && typeof navigator.canShare === 'function')
  }, [])
  useEffect(() => {
    messagesEnd.current?.scrollIntoView({ block: 'end', behavior: 'instant' })
  }, [messages.length, streaming?.text, activeId])

  // 输入框自适应高度（单行 36px，胶囊总高 48px 与左侧 + 按钮完全对齐；多行最大 150px）
  useLayoutEffect(() => {
    const editor = input.current
    if (!editor) return
    editor.style.height = '36px'
    editor.style.height = `${Math.min(Math.max(editor.scrollHeight, 36), 150)}px`
    if (editor.selectionStart === draft.length && editor.selectionEnd === draft.length) {
      editor.scrollTop = editor.scrollHeight
    }
  }, [draft])

  useEffect(() => {
    setTopMoreOpen(false)
    setModelDropdownOpen(false)
    setFnPanelOpen(false)
    setSessionMenuId(undefined)
    setGroupMenuName(undefined)
    setHistory(undefined)
    setShowScrollBottom(false)
  }, [activeId])

  const handleConversationScroll = () => {
    const el = conversationScrollRef.current
    if (!el) return
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
    setShowScrollBottom(distanceFromBottom > 160)
  }

  const scrollToBottom = () => {
    messagesEnd.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
    setShowScrollBottom(false)
  }

  const closeAllMenus = () => {
    setModelDropdownOpen(false)
    setTopMoreOpen(false)
    setFnPanelOpen(false)
    setSessionMenuId(undefined)
    setGroupMenuName(undefined)
    setAiMenuMessageId(undefined)
    setAiMenuPickerMode(false)
    setContextPopupMessageId(undefined)
  }

  const run = async (work: () => Promise<void>): Promise<void> => {
    try {
      await work()
    } catch (value) {
      if (runtime.current?.alive) setError(value instanceof Error ? value.message : '操作失败')
    }
  }
  const currentRuntime = () => {
    const current = runtime.current
    if (!current?.ready || !current.alive) throw new Error('工作区尚未准备好')
    return current
  }
  /** 资源和密钥请求共用同一刷新出口；刷新后只替换内存令牌，不重建工作区。 */
  const refreshSession = useCallback(async (): Promise<AuthSession> => {
    const renewed = await authClient.refresh(sessionRef.current)
    sessionRef.current = renewed
    return renewed
  }, [sessionRef])
  const downloads = useFileDownloads(ready ? imageClient : undefined, records, session, refreshSession)

  // 消息超过服务端上限时，完整正文只存在于 message_archive 附件里。
  // 打开会话时下载并解密回填到显示层，只影响显示，不写回同步队列。
  useEffect(() => {
    const current = runtime.current
    if (!current?.ready || !current.alive || archiveLoading.current) return
    const pending = messages.flatMap(message => {
      const attachment = records.find(r => r.entity === 'attachments' && !r.deleted && r.payload.messageId === message.id &&
        r.payload.role === 'message_archive' && (!r.payload.chunkCount || r.payload.chunkIndex === 0))
      if (!attachment || archiveViews[message.id]) return []
      const key = `${attachment.id}:${String(attachment.payload.resourceVersion ?? '')}`
      // 分块可能后到；失败先保留附件链接，等记录变化后再试，最多三次。
      const attempts = archiveAttempts.current.get(key) ?? 0
      if (attempts >= 3) return []
      archiveAttempts.current.set(key, attempts + 1)
      return [{ attachment }]
    })
    if (!pending.length) return
    archiveLoading.current = true
    void (async () => {
      try {
        for (const item of pending) {
          try {
            const value = await readHistory(current.client, current.crypto, sessionRef.current, records, item.attachment, refreshSession)
            const view = archiveDisplay(value)
            if (view && current.alive && runtime.current === current) setArchiveViews(prev => ({ ...prev, [String(item.attachment.payload.messageId)]: view }))
          } catch {
            // 网络或解密失败：继续显示原来的“完整历史”附件链接。
          }
        }
      } finally {
        archiveLoading.current = false
      }
    })()
  }, [messages, records, ready, refreshSession, archiveViews])

  const startSpeechSession = (openOverlay: boolean): void => {
    const current = getSpeechRecognition(window)
    if (!current) {
      setError('当前浏览器不支持语音输入，请使用最新版 Chrome 或 Edge')
      return
    }
    if (openOverlay) setVoiceOverlayOpen(true)
    voiceBase.current = draft.trim() ? `${draft.trim()} ` : ''
    current.lang = voiceLanguage
    current.continuous = false
    current.interimResults = true
    current.onresult = event => {
      let transcript = ''
      for (let index = 0; index < event.results.length; index++) transcript += event.results[index]?.[0]?.transcript ?? ''
      setDraft(`${voiceBase.current}${transcript}`)
    }
    current.onerror = event => {
      setListening(false)
      recognition.current = undefined
      if (event.error !== 'aborted') setError(`语音输入失败：${event.error || '浏览器未返回原因'}`)
    }
    current.onend = () => {
      setListening(false)
      recognition.current = undefined
    }
    recognition.current = current
    setError('')
    setListening(true)
    try {
      current.start()
    } catch {
      setListening(false)
      recognition.current = undefined
      setError('语音输入无法启动，请检查浏览器麦克风权限')
    }
  }

  const toggleVoiceInput = (): void => {
    if (listening) {
      recognition.current?.stop()
      return
    }
    startSpeechSession(false)
  }

  const readAloud = (messageId: string, text: string): void => {
    if (!window.speechSynthesis) {
      setError('当前浏览器不支持语音朗读')
      return
    }
    if (speakingMessage === messageId) {
      window.speechSynthesis.cancel()
      setSpeakingMessage('')
      return
    }
    window.speechSynthesis.cancel()
    if (speakText(window, text, voiceLanguage, () => setSpeakingMessage(current => (current === messageId ? '' : current)))) setSpeakingMessage(messageId)
  }

  const saveSetting = async (kind: string, key: string, value: JsonObject, old?: StoredRecord, deleted = false): Promise<void> => {
    if (!key) throw new Error('配置标识不能为空')
    const existing = old ?? liveRecords.current.find(r => r.entity === 'user_settings' && r.payload.kind === kind && r.payload.key === key)
    if (kind === 'api_configs' && (isBuiltinModel(value, key) || (existing && isBuiltinModel(valueOf(existing), key)))) throw new Error('内置默认配置不可修改或删除')
    const webKind = isLegacySettingKind(kind) ? kind : undefined
    // 网页旧 kind 统一改写成安卓合同 kind；已有记录沿用其同步 key，新记录用 UUID。
    const targetKind = webKind ? contractKindOf(webKind) : kind
    const targetKey = webKind
      ? existing
        ? isCanonicalUuid(str(existing.payload.key))
          ? str(existing.payload.key).toLowerCase()
          : await canonicalSettingKey(targetKind, str(existing.payload.key, key))
        : crypto.randomUUID()
      : key
    let extras: JsonObject | undefined
    if (webKind && !deleted) {
      const mapped = settingViewToContract(webKind, targetKey, value)
      value = mapped.value
      extras = mapped.extras
    } else if (webKind) {
      // 删除只发墓碑；网页视图里的本地字段（apiKey/workspaces 等）不能进入服务端扫描。
      value = {}
    }
    const current = currentRuntime()
    if (!deleted) {
      await current.crypto.unlock(current.client, sessionRef.current, snapshot.secretsEpoch ?? 0, refreshSession)
      const encoded = await current.crypto.encodeSettings(value, targetKind, targetKey)
      if (!isObject(encoded)) throw new Error('配置格式无效')
      value = encoded
    }
    await current.client.saveLocal('user_settings', existing?.id ?? crypto.randomUUID(), { clientModifiedAt: updated(existing), kind: targetKind, key: targetKey, value }, deleted)
    if (webKind) writeSettingExtras(sessionRef.current.user.id, targetKind, targetKey, deleted ? undefined : extras)
    await changed()
  }

  const saveMemory = async (content: string, old?: StoredRecord, deleted = false): Promise<void> => {
    if (!content.trim() && !deleted) throw new Error('记忆不能为空')
    const now = updated(old)
    await currentRuntime().client.saveLocal(
      'memories',
      old?.id ?? crypto.randomUUID(),
      {
        ...old?.payload,
        content: content.trim(),
        category: old?.payload.category ?? 'OTHER',
        origin: old?.payload.origin ?? 'MANUAL',
        enabled: old?.payload.enabled ?? true,
        createdAt: old?.payload.createdAt ?? now,
        updatedAt: now,
        clientModifiedAt: now,
      },
      deleted
    )
    await changed()
  }

  const newSession = async (targetGroup?: string): Promise<StoredRecord> => {
    const id = crypto.randomUUID(),
      now = Date.now()
    // 会话归属跟随新建时选中的模型类型，与安卓文字/生图列表一致。
    const record = recordOf('sessions', id, {
      clientModifiedAt: now,
      title: '新对话',
      creationTimestamp: now,
      lastModifiedTimestamp: now,
      isImageGeneration: imageMode,
    })
    await currentRuntime().client.saveRecords([record])
    if (selectedModel) await saveSetting('conversation_settings', id, { modelId: str(selectedModel.payload.key) })
    if (targetGroup?.trim()) {
      const encoded = encodeGroupKey(targetGroup)
      await saveSetting('group_members', `${encoded}.${id}`, { groupName: targetGroup.trim(), conversationId: id })
    }
    await changed()
    setSelected(id)
    setMobile(false)
    setDraft('')
    setFiles([])
    return record
  }

  const chooseModel = async (configId: string): Promise<void> => {
    if (activeId) await saveSetting('conversation_settings', activeId, { ...valueOf(conversation), modelId: configId }, conversation)
    else {
      // 没有会话时更新对应类型的全局默认（安卓 selected_models/text 或 image）。
      const scope = valueOf(models.find(m => m.payload.key === configId)).imageConfig === true ? 'image' : 'text'
      await saveSetting('selected_models', scope, { ...valueOf(setting('selected_models', scope)), configId }, setting('selected_models', scope))
    }
  }

  const toggleWebSearch = async (): Promise<void> => {
    const chat = active ?? (await newSession())
    const chatConv = setting('conversation_settings', chat.id)
    const opts = valueOf(chatConv)
    await saveSetting('conversation_settings', chat.id, { ...opts, webSearchEnabled: !(opts.webSearchEnabled === true) }, chatConv)
  }

  const toggleMcp = async (): Promise<void> => {
    const chat = active ?? (await newSession())
    const chatConv = setting('conversation_settings', chat.id)
    const opts = valueOf(chatConv)
    await saveSetting('conversation_settings', chat.id, { ...opts, mcpEnabled: !(opts.mcpEnabled === true) }, chatConv)
  }

  const toggleAgent = async (): Promise<void> => {
    const chat = active ?? (await newSession())
    const chatConv = setting('conversation_settings', chat.id)
    const opts = valueOf(chatConv)
    await saveSetting('conversation_settings', chat.id, { ...opts, agentEnabled: !(opts.agentEnabled === true) }, chatConv)
  }

  const saveConversationSystemPrompt = async (promptText: string, enabled: boolean): Promise<void> => {
    const chat = active ?? (await newSession())
    const chatConv = setting('conversation_settings', chat.id)
    const opts = valueOf(chatConv)
    await saveSetting(
      'conversation_settings',
      chat.id,
      {
        ...opts,
        systemPrompt: promptText,
        promptEnabled: enabled && Boolean(promptText.trim()),
      },
      chatConv
    )
  }

  const saveConversationImageRatio = async (ratio: string, quality: '2K' | '4K'): Promise<void> => {
    const chat = active ?? (await newSession())
    const chatConv = setting('conversation_settings', chat.id)
    const opts = valueOf(chatConv)
    await saveSetting(
      'conversation_settings',
      chat.id,
      {
        ...opts,
        imageAspectRatio: ratio,
        imageQualitySize: quality,
      },
      chatConv
    )
  }

  const moveSessionToGroup = async (targetSession: StoredRecord, targetGroup: string | null): Promise<void> => {
    const currentMemberships = memberships.filter(r => str(valueOf(r).conversationId) === targetSession.id)
    for (const m of currentMemberships) {
      await saveSetting('group_members', str(m.payload.key), valueOf(m), m, true)
    }
    if (targetGroup && targetGroup.trim()) {
      const encoded = encodeGroupKey(targetGroup)
      await saveSetting('group_members', `${encoded}.${targetSession.id}`, { groupName: targetGroup.trim(), conversationId: targetSession.id })
    }
  }

  const renameGroup = async (oldName: string, nextName: string): Promise<void> => {
    const trimmed = nextName.trim()
    if (!trimmed || trimmed === oldName) return
    const matched = memberships.filter(r => str(valueOf(r).groupName) === oldName)
    for (const m of matched) {
      const convId = str(valueOf(m).conversationId)
      await saveSetting('group_members', str(m.payload.key), valueOf(m), m, true)
      if (convId) {
        const encoded = encodeGroupKey(trimmed)
        await saveSetting('group_members', `${encoded}.${convId}`, { groupName: trimmed, conversationId: convId })
      }
    }
    saveCustomGroups(customGroups.map(g => (g === oldName ? trimmed : g)))
  }

  const deleteGroup = async (groupName: string): Promise<void> => {
    const matched = memberships.filter(r => str(valueOf(r).groupName) === groupName)
    for (const m of matched) {
      await saveSetting('group_members', str(m.payload.key), valueOf(m), m, true)
    }
    saveCustomGroups(customGroups.filter(g => g !== groupName))
  }

  const clearAllSessions = async (): Promise<void> => {
    const current = currentRuntime()
    for (const item of sessions) {
      await current.client.saveLocal('sessions', item.id, { ...item.payload, clientModifiedAt: updated(item) }, true)
    }
    await changed()
    setSelected(undefined)
  }

  const reply = async (chat: StoredRecord, context: StoredRecord[], filesForModel: File[] = []): Promise<void> => {
    const current = currentRuntime()
    const chosen = selectedModel
    if (!chosen) {
      setSettings('models')
      throw new Error('消息已保存。请添加或同步模型后，点击「继续生成」')
    }
    if (!navigator.onLine) throw new Error('消息已离线保存，联网后可继续生成回复')
    const state = await current.client.snapshot()
    if (!isBuiltinModel(valueOf(chosen), str(chosen.payload.key))) await current.crypto.unlock(current.client, sessionRef.current, state.secretsEpoch ?? 0, refreshSession)
    const config = isBuiltinModel(valueOf(chosen), str(chosen.payload.key))
      ? builtinModels.find(model => model.model === valueOf(chosen).model)
      : await current.crypto.decodeSettings(valueOf(chosen), 'api_configs', str(chosen.payload.key))
    if (!isObject(config)) throw new Error('模型配置无效')
    const chatSetting = liveRecords.current.find(r => r.entity === 'user_settings' && !r.deleted && r.payload.kind === 'conversation_settings' && r.payload.key === chat.id)
    const options = valueOf(chatSetting)
    const lastUserText = str([...context].reverse().find(message => message.payload.sender === 'User')?.payload.text)
    // 安卓固定前缀 + 会话自定义指令，记忆资料追加在后。
    let system = stableSystemPrompt(lastUserText, options.promptEnabled === true ? str(options.systemPrompt) : '')
    const memory = conversationMemory(
      state.records,
      chat.id,
      async () => {
        if (!current.alive) throw new Error('当前账号工作区已关闭')
        return (await current.client.snapshot()).records
      },
      lastUserText
    )
    if (memory) system += `\n\n${memory.prompt}`
    controller.current = new AbortController()
    const abort = controller.current
    const startedAt = Date.now()
    setStreaming({ text: '', reasoning: '', sessionId: chat.id, startedAt })
    try {
      // 本地免登录预览模式下，若选用内置演示模型，则执行实时流式思考、工具调用与回复演示，无需云端令牌即可完整体验动态效果。
      if (session.user.id === 'local-preview-user' && isBuiltinModel(valueOf(chosen), str(chosen.payload.key))) {
        const sleep = (ms: number) => new Promise<void>(resolve => window.setTimeout(resolve, ms))
        const demoStep: ExecutionStepItem = {
          id: crypto.randomUUID(),
          type: options.webSearchEnabled === true ? 'search' : 'tool',
          title: options.webSearchEnabled === true ? `搜索网页：${lastUserText.slice(0, 28) || '实时检索'}` : '调用工具：everytalk_memory · 检索上下文',
          labels: [lastUserText.slice(0, 24) || '当前输入', str(config.model)],
          completed: false,
        }
        const reasoningLines = [
          `正在解析用户输入：「${lastUserText.slice(0, 36) || '演示请求'}」…`,
          '正在执行工具调用并整合多端同步上下文…',
          '推理完成，准备生成结构化回复与代码卡片。',
        ]
        let accReasoning = ''
        for (const line of reasoningLines) {
          if (!current.alive || abort.signal.aborted) return
          accReasoning = accReasoning ? `${accReasoning}\n${line}` : line
          setStreaming({
            text: '',
            reasoning: accReasoning,
            executionSteps: [demoStep],
            activeStatus: '思考与工具执行中',
            sessionId: chat.id,
            startedAt,
          })
          await sleep(450)
        }
        const finishedStep: ExecutionStepItem = { ...demoStep, completed: true }
        const demoReplyChunks = [
          `收到你的消息：「**${lastUserText.slice(0, 48) || '预览测试'}**」。\n\n`,
          '当前处于 **本地免登录预览模式**，已完整展示实时扫光思考、工具步骤流转与正文流式输出效果：\n\n',
          '```ts\n// EveryTalk Web × Android UI 对齐示例\nexport const dialogRadius = "28px";\nexport const pillButtonHeight = "48px";\n```\n\n',
          '你可以在左下角「设置」中配置任意自定义 API Key 进行真实模型对话，或点击消息底部的胶囊与菜单体验全部弹窗。',
        ]
        let accText = ''
        for (const chunk of demoReplyChunks) {
          if (!current.alive || abort.signal.aborted) return
          accText += chunk
          setStreaming({
            text: accText,
            reasoning: accReasoning,
            executionSteps: [finishedStep],
            sessionId: chat.id,
            startedAt,
          })
          await sleep(260)
        }
        const fresh = await current.client.snapshot()
        const storedChat = fresh.records.find(r => r.entity === 'sessions' && r.id === chat.id)
        const now = updated(storedChat ?? chat)
        const id = crypto.randomUUID()
        await current.client.saveRecords([
          recordOf('messages', id, {
            sessionId: chat.id,
            text: accText,
            sender: 'AI',
            timestamp: now,
            isError: false,
            outputType: 'general',
            modelName: str(config.model),
            providerName: str(config.provider),
            reasoning: accReasoning,
            executionSteps: [finishedStep] as unknown as JsonObject[],
            executionStartedAt: startedAt,
            executionFinishedAt: now,
            executionDurationMs: Math.max(1000, now - startedAt),
          }),
          recordOf('sessions', chat.id, { ...(storedChat ?? chat).payload, clientModifiedAt: now, lastModifiedTimestamp: now }),
        ])
        await changed()
        return
      }
      if (valueOf(chosen).imageConfig === true) {
        const prompt = str([...context].reverse().find(message => message.payload.sender === 'User')?.payload.text).trim()
        if (!prompt) throw new Error('图片提示词不能为空')
        const effectiveRatio = str(options.imageAspectRatio)
        const effectiveQuality = str(options.imageQualitySize) === '4K' ? '4K' : '2K'
        const imageConfigOverride: JsonObject = {
          ...config,
          ...(effectiveRatio
            ? {
                aspectRatio: effectiveRatio,
                modelParameters: {
                  ...(isObject(config.modelParameters) ? config.modelParameters : {}),
                  imageAspectRatio: effectiveRatio,
                },
              }
            : {}),
          ...(effectiveQuality ? { imageSize: effectiveQuality, geminiImageSize: effectiveQuality } : {}),
        }
        const result = await generateImage(sessionRef.current, imageRequest(imageConfigOverride, prompt, await modelAttachments(filesForModel)), abort.signal, refreshSession)
        if (!current.alive || abort.signal.aborted) return
        const fresh = await current.client.snapshot(),
          storedChat = fresh.records.find(r => r.entity === 'sessions' && r.id === chat.id)
        if (storedChat?.deleted || context.some(m => fresh.records.some(r => r.id === m.id && r.entity === 'messages' && r.deleted))) throw new Error('这段对话已在另一端删除，图片未保存')
        const now = updated(storedChat ?? chat),
          id = crypto.randomUUID(),
          resources: StoredRecord[] = []
        for (const [index, dataUrl] of result.images.entries()) resources.push(...(await uploadResource(current.client, sessionRef.current, dataUrlFile(dataUrl, index), chat.id, id, refreshSession)))
        const text = result.text || '已生成图片'
        await current.client.saveRecords([
          recordOf('messages', id, {
            sessionId: chat.id,
            text,
            sender: 'AI',
            timestamp: now,
            isError: false,
            outputType: 'image',
            modelName: str(config.model),
            providerName: str(config.provider),
            executionStartedAt: startedAt,
            executionFinishedAt: now,
            executionDurationMs: Math.max(0, now - startedAt),
          }),
          recordOf('sessions', chat.id, { ...(storedChat ?? chat).payload, clientModifiedAt: now, lastModifiedTimestamp: now }),
          ...resources,
        ])
        await changed()
        return
      }
      let webSearchResults: SearchResult[] = []
      let search: Parameters<typeof builtinTools>[0]['search']
      // 安卓 WebSearchSupport：支持原生搜索的模型直接开启原生搜索，其余模型改由 web_search 工具按需检索。
      const nativeSearch = options.webSearchEnabled === true && nativeWebSearch(config) !== undefined
      if (options.webSearchEnabled === true && !nativeSearch) {
        if (!selectedSearch) throw new Error('请先在设置的“联网搜索”中配置服务商')
        const searchConfig = await current.crypto.decodeSettings(valueOf(selectedSearch), 'web_search_configs', str(selectedSearch.payload.key))
        if (!isObject(searchConfig) || typeof searchConfig.apiKey !== 'string') throw new Error('联网搜索配置无效，请重新保存 API Key')
        const provider = normalizeSearchProvider(searchConfig.providerId ?? selectedSearch.payload.key)
        if (!provider) throw new Error('联网搜索服务商不受支持')
        search = {
          provider,
          apiKey: searchConfig.apiKey,
          onResults: results => {
            webSearchResults = [...webSearchResults, ...results.filter(item => !webSearchResults.some(old => old.url === item.url))]
          },
        }
      }
      const tools = combineTools(builtinTools({ session: () => sessionRef.current, refreshSession, signal: abort.signal, search }), memory?.tools)
      const request = modelRequest(config, context, system, await modelAttachments(filesForModel))
      const result = await generate(
        sessionRef.current,
        nativeSearch ? withNativeWebSearch(request, config) : request,
        abort.signal,
        partial => {
          if (current.alive) setStreaming({ ...partial, sessionId: chat.id, startedAt })
        },
        refreshSession,
        tools
      )
      if (!current.alive || abort.signal.aborted) return
      const fresh = await current.client.snapshot()
      const storedChat = fresh.records.find(r => r.entity === 'sessions' && r.id === chat.id)
      if (storedChat?.deleted || context.some(m => fresh.records.some(r => r.id === m.id && r.entity === 'messages' && r.deleted))) throw new Error('这段对话已在另一端删除，回复未保存')
      const now = updated(storedChat ?? chat),
        id = crypto.randomUUID()
      await current.client.saveRecords([
        recordOf('messages', id, {
          sessionId: chat.id,
          text: result.text,
          sender: 'AI',
          timestamp: now,
          isError: false,
          outputType: 'general',
          modelName: str(config.model),
          providerName: str(config.provider),
          ...(result.reasoning ? { reasoning: result.reasoning } : {}),
          ...(result.executionSteps?.length ? { executionSteps: result.executionSteps as unknown as JsonObject[] } : {}),
          ...(webSearchResults.length ? { webSearchResults } : {}),
          executionStartedAt: startedAt,
          executionFinishedAt: now,
          executionDurationMs: Math.max(0, now - startedAt),
        }),
        recordOf('sessions', chat.id, { ...(storedChat ?? chat).payload, clientModifiedAt: now, lastModifiedTimestamp: now }),
      ])
      await changed()
    } finally {
      if (controller.current === abort) controller.current = undefined
      if (current.alive) setStreaming(undefined)
    }
  }

  const send = async (): Promise<void> => {
    if (busyRef.current || (!draft.trim() && !files.length)) return
    // 安卓 AiContentSafetyPolicy：命中拦截规则时不保存、不发送，输入保留在编辑框。
    const blocked = blockedInputMessage(draft.trim(), imageMode)
    if (blocked) {
      setError(blocked)
      return
    }
    busyRef.current = true
    setBusy(true)
    setError('')
    const content = draft.trim(),
      pendingFiles = files
    try {
      const chat = active ?? (await newSession()),
        current = currentRuntime()
      const now = updated(chat),
        id = crypto.randomUUID()
      const user = recordOf('messages', id, {
        sessionId: chat.id,
        text: content || pendingFiles.map(f => f.name).join('\n'),
        sender: 'User',
        timestamp: now,
        isError: false,
        outputType: 'general',
      })
      const resources: StoredRecord[] = []
      for (const file of pendingFiles) resources.push(...(await uploadResource(current.client, sessionRef.current, file, chat.id, id, refreshSession)))
      if (!current.alive) return
      await current.client.saveRecords([
        user,
        recordOf('sessions', chat.id, {
          ...chat.payload,
          clientModifiedAt: now,
          lastModifiedTimestamp: now,
          title: str(chat.payload.title) === '新对话' ? (content || pendingFiles[0]?.name || '新对话').slice(0, 40) : chat.payload.title,
        }),
        ...resources,
      ])
      setDraft('')
      setFiles([])
      await changed()
      const context = liveRecords.current
        .filter(r => r.entity === 'messages' && !r.deleted && r.payload.sessionId === chat.id && r.id !== id)
        .sort((a, b) => Number(a.payload.timestamp) - Number(b.payload.timestamp))
      await reply(chat, [...context, user], pendingFiles)
    } catch (value) {
      if (runtime.current?.alive && !(value instanceof DOMException && value.name === 'AbortError')) {
        setError(value instanceof Error ? value.message : '发送失败，未保存的输入仍在编辑框中')
      }
    } finally {
      busyRef.current = false
      if (runtime.current?.alive) {
        setBusy(false)
        input.current?.focus()
      }
    }
  }

  const continueReply = async () => {
    if (!active || busyRef.current) return
    busyRef.current = true
    setBusy(true)
    try {
      await run(() => reply(active, messages))
    } finally {
      busyRef.current = false
      if (runtime.current?.alive) setBusy(false)
    }
  }

  /**
   * 针对指定消息重新生成回复，或切换模型后重答（对齐安卓端 AiMessagePopupMenu & ConfirmModelRegenerateDialog）。
   */
  const regenerateFromMessage = async (targetMessage: StoredRecord, overrideModelKey?: string) => {
    if (!active || busyRef.current) return
    busyRef.current = true
    setBusy(true)
    try {
      await run(async () => {
        if (overrideModelKey) {
          await chooseModel(overrideModelKey)
        }
        const idx = messages.findIndex(m => m.id === targetMessage.id)
        const sliceEnd = idx >= 0 ? (targetMessage.payload.sender === 'User' ? idx + 1 : idx) : messages.length
        const context = messages.slice(0, Math.max(1, sliceEnd))
        await reply(active, context)
      })
    } finally {
      busyRef.current = false
      if (runtime.current?.alive) setBusy(false)
    }
  }

  /**
   * 导出单条 AI 回复文本为 Markdown 文件（对齐安卓端 ExportTextDialog / 导出文本）。
   */
  const exportMessageMarkdown = (message: StoredRecord) => {
    const content = str(message.payload.text)
    const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `everytalk-reply-${message.id.slice(0, 8)}.md`
    a.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  const download = async (attachment: StoredRecord) => {
    const current = currentRuntime()
    if (attachment.payload.role === 'message_archive') {
      const value = await readHistory(current.client, current.crypto, sessionRef.current, records, attachment, refreshSession)
      if (current.alive) setHistory({ id: attachment.id, value })
      return
    }
    if (downloads.preferences && downloads.supported) {
      await downloads.authorize()
      if (await downloads.save(attachment)) return
      throw new Error('保存目录已变化，请重新点击附件')
    }
    const localFile = await receiveFileForPreview(current.client, sessionRef.current, records, attachment, refreshSession)
    if (!current.alive) return
    // 使用下载链接，HTML/SVG 等主动内容不会在本站上下文执行。
    const url = URL.createObjectURL(localFile)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = str(attachment.payload.name, 'file')
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
  }

  const exportLocal = () => {
    const url = URL.createObjectURL(new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), records }, null, 2)], { type: 'application/json' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'everytalk-web-backup.json'
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  const shareFile = async (attachment: StoredRecord): Promise<void> => {
    const current = currentRuntime()
    const file = await receiveFileForPreview(current.client, sessionRef.current, records, attachment, refreshSession)
    if (!current.alive) return
    if (!navigator.canShare?.({ files: [file] })) throw new Error('当前浏览器不能分享此类文件，请先保存后使用系统打开')
    try {
      await navigator.share({ files: [file], title: file.name })
    } catch (value) {
      if (value instanceof DOMException && value.name === 'AbortError') return
      throw new Error('系统未允许此次文件分享，请保存后从本地打开')
    }
  }

  const previewAttachment = async (attachment: StoredRecord): Promise<void> => {
    const current = currentRuntime()
    const file = await receiveFileForPreview(current.client, sessionRef.current, records, attachment, refreshSession)
    if (current.alive) setPreviewFile(file)
  }

  const sendReport = async (): Promise<void> => {
    if (!report || report.sending) return
    const message = report.message
    setReport({ ...report, sending: true })
    try {
      const imageCount = records.filter(
        r => r.entity === 'attachments' && !r.deleted && r.payload.messageId === message.id && str(r.payload.kind) === 'image' && (!r.payload.chunkCount || r.payload.chunkIndex === 0)
      ).length
      const response = await authenticatedFetch(
        sessionRef.current,
        `${apiBaseUrl}/v1/report`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          cache: 'no-store',
          body: JSON.stringify({
            messageId: message.id,
            category: report.category,
            details: report.details,
            messageText: str(message.payload.text),
            imageCount,
            isImageGeneration: message.payload.outputType === 'image',
            modelName: str(message.payload.modelName),
            providerName: str(message.payload.providerName),
          }),
        },
        refreshSession
      )
      const body: unknown = await response.json().catch(() => undefined)
      if (!response.ok) throw new Error(isObject(body) && typeof body.error === 'string' ? body.error : `举报失败（HTTP ${response.status}）`)
      const next = [...new Set([...reported, message.id])].slice(-500)
      setReported(next)
      localStorage.setItem(reportedKey, JSON.stringify(next))
      setReport(undefined)
    } catch (value) {
      setReport(current => current && { ...current, sending: false })
      throw value
    }
  }

  const removeSession = async (target?: StoredRecord) => {
    const sessionToRemove = target ?? active
    if (!sessionToRemove) return
    await currentRuntime().client.saveLocal('sessions', sessionToRemove.id, { ...sessionToRemove.payload, clientModifiedAt: updated(sessionToRemove) }, true)
    await changed()
    if (sessionToRemove.id === activeId) setSelected(undefined)
    closeAllMenus()
  }

  const filteredSessions = sortedSessions.filter(
    item =>
      !query ||
      str(item.payload.title).toLowerCase().includes(query.toLowerCase()) ||
      records.some(m => m.entity === 'messages' && !m.deleted && m.payload.sessionId === item.id && str(m.payload.text).toLowerCase().includes(query.toLowerCase()))
  )

  const groupedSessionIds = new Set(memberships.map(m => str(valueOf(m).conversationId)).filter(Boolean))
  const pinnedSessions = filteredSessions.filter(item => pinned.has(item.id) && !groupedSessionIds.has(item.id))
  const ungroupedSessions = filteredSessions.filter(item => !pinned.has(item.id) && !groupedSessionIds.has(item.id))

  /**
   * 渲染含围栏代码块与文件引用的消息正文。
   */
  const renderFormattedMessageText = (text: string, attachments: StoredRecord[], renderFile: (attachment: StoredRecord, label?: string) => React.ReactNode) => {
    const segments = parseMessageSegments(text)
    return segments.map((seg, idx) => {
      if (seg.kind === 'code') {
        return <CodeBlockCard key={`code-${idx}`} language={seg.language} code={seg.code} onOpenFullscreen={item => setFullscreenCode(item)} />
      }
      if (!seg.content) return null
      const parts = messageFileParts(seg.content, attachments)
      return (
        <span key={`txt-${idx}`}>
          <MessageFileText parts={parts} renderFile={renderFile} />
        </span>
      )
    })
  }

  /**
   * 渲染单条会话项（对齐安卓端 DrawerListItem.kt：左侧 8px 激活圆点 + 标题 + 置顶图标 + 悬浮卡片菜单）。
   */
  const renderSessionRow = (item: StoredRecord) => {
    const isMenuOpen = sessionMenuId === item.id
    return (
      <div key={item.id} className={`et-session-row ${item.id === activeId ? 'active' : ''}`}>
        <button
          type="button"
          className="et-session"
          onClick={() => {
            setSelected(item.id)
            setMobile(false)
            closeAllMenus()
          }}
        >
          <span className="et-session-dot" aria-hidden="true" />
          <span className="et-session-title">{str(item.payload.title, '未命名对话') || '未命名对话'}</span>
          {pinned.has(item.id) && <Pin size={14} className="et-pin-icon" aria-label="已置顶" />}
        </button>
        <div className="et-menu-anchor">
          <button
            type="button"
            className={`et-session-more ${isMenuOpen ? 'is-open' : ''}`}
            aria-label="会话操作"
            onClick={e => {
              e.stopPropagation()
              setSessionMenuId(isMenuOpen ? undefined : item.id)
              setGroupMenuName(undefined)
            }}
          >
            <MoreHorizontal size={16} />
          </button>
          {isMenuOpen && (
            <div className="et-floating-card align-right" role="menu">
              <button
                type="button"
                className="et-floating-item"
                onClick={() =>
                  void run(async () => {
                    await saveSetting('pinned_items', item.id, {}, setting('pinned_items', item.id), pinned.has(item.id))
                    setSessionMenuId(undefined)
                  })
                }
              >
                <Pin size={16} />
                <span className="et-floating-item-label">{pinned.has(item.id) ? '取消置顶' : '置顶'}</span>
              </button>
              <button
                type="button"
                className="et-floating-item"
                onClick={() => {
                  setRenameDialog({ session: item, title: str(item.payload.title) })
                  setSessionMenuId(undefined)
                }}
              >
                <Edit3 size={16} />
                <span className="et-floating-item-label">重命名</span>
              </button>
              <button
                type="button"
                className="et-floating-item"
                onClick={() => {
                  setMoveGroupDialog(item)
                  setSessionMenuId(undefined)
                }}
              >
                <Folder size={16} />
                <span className="et-floating-item-label">移动到分组</span>
              </button>
              <div className="et-floating-divider" />
              <button
                type="button"
                className="et-floating-item et-danger"
                disabled={busy}
                onClick={() => {
                  setDeleteDialog(item)
                  setSessionMenuId(undefined)
                }}
              >
                <Trash2 size={16} />
                <span className="et-floating-item-label">删除</span>
              </button>
            </div>
          )}
        </div>
      </div>
    )
  }

  return (
    <main
      className="et-app et-workspace"
      onClick={() => {
        if (modelDropdownOpen || topMoreOpen || fnPanelOpen || sessionMenuId || groupMenuName || aiMenuMessageId || contextPopupMessageId) closeAllMenus()
      }}
    >
      {previewFile && <MessageFilePreview file={previewFile} onClose={() => setPreviewFile(undefined)} />}
      {mobile && <button className="et-nav-scrim" aria-label="关闭导航" onClick={() => setMobile(false)} />}

      {/* 左侧可拖拽展开抽屉（融合 QoneAgent ResizableSidebar 丝滑收起/拖拽调宽与安卓端 AppDrawerContent.kt） */}
      <aside
        className={`et-sidebar ${mobile ? 'is-open' : ''} ${drawerCollapsed ? 'is-collapsed' : ''} ${sidebarResizing ? 'is-resizing' : ''}`}
        style={{ '--et-sidebar-w': `${sidebarWidth}px` } as React.CSSProperties}
        aria-label="会话导航"
        aria-hidden={drawerCollapsed && !mobile}
        onClick={e => e.stopPropagation()}
      >
        <div className="et-sidebar-clip">
          <div className="et-sidebar-frame">
            <div className="et-drawer-header">
              <div className="et-drawer-header-left">
                <button
                  type="button"
                  className="et-drawer-collapse-btn et-desktop-only"
                  aria-label="收起侧边栏"
                  title="收起侧边栏"
                  onClick={() => toggleDesktopDrawer(true)}
                >
                  <PanelLeftClose size={18} />
                </button>
                <Link href="/" className="et-brand">
                  <img src="/favicon.svg" width="28" height="28" alt="" />
                  <span>EveryTalk</span>
                </Link>
              </div>
              <div className="et-drawer-header-actions">
                <button
                  type="button"
                  className={`et-drawer-search-btn ${searchOpen || Boolean(query) ? 'is-active' : ''}`}
                  aria-label={searchOpen ? '收起搜索' : '搜索会话'}
                  title={searchOpen ? '收起搜索' : '搜索会话'}
                  onClick={() => {
                    setSearchOpen(prev => {
                      const next = !prev
                      if (next) window.setTimeout(() => searchInputRef.current?.focus(), 40)
                      else setQuery('')
                      return next
                    })
                  }}
                >
                  {searchOpen ? <X size={17} /> : <Search size={17} />}
                </button>
                <button type="button" className="et-mobile-only et-drawer-close-btn" aria-label="关闭导航" onClick={() => setMobile(false)}>
                  <X size={18} />
                </button>
              </div>
            </div>

            <div className={`et-collapsible-block ${searchOpen || Boolean(query) ? 'is-open' : ''}`}>
              <div className="et-collapsible-inner">
                <div className="et-drawer-search-box">
                  <Search size={15} />
                  <input ref={searchInputRef} aria-label="搜索对话" placeholder="搜索对话…" value={query} onChange={e => setQuery(e.target.value)} />
                  {query && (
                    <button type="button" className="et-drawer-search-clear" aria-label="清除搜索" onClick={() => setQuery('')}>
                      <X size={14} />
                    </button>
                  )}
                </div>
              </div>
            </div>

            <div className="et-drawer-primary-actions">
              <button
                type="button"
                className="et-drawer-action-btn"
                disabled={!ready || busy}
                onClick={() =>
                  void run(async () => {
                    await newSession()
                  })
                }
              >
                <Plus size={18} />
                <span>开启新对话</span>
              </button>
              <button type="button" className="et-drawer-action-btn is-secondary" disabled={!ready || busy || sessions.length === 0} onClick={() => setClearAllOpen(true)}>
                <Trash2 size={17} />
                <span>清空记录</span>
              </button>
            </div>

            <nav className="et-sessions">
              {/* 分组区块（对齐 AppDrawerGroupComponents.kt，支持整体平滑展开/收起） */}
              <div className="et-drawer-section-head">
                <button
                  type="button"
                  className="et-drawer-section-toggle"
                  onClick={() => setGroupSectionExpanded(prev => !prev)}
                >
                  <ChevronRight size={13} className={`et-group-chevron ${groupSectionExpanded ? 'is-expanded' : ''}`} />
                  <span>分组</span>
                </button>
                <button
                  type="button"
                  className="et-drawer-section-add"
                  aria-label="创建新分组"
                  title="创建新分组"
                  onClick={() => {
                    setNewGroupName('')
                    setCreateGroupOpen(true)
                  }}
                >
                  <Plus size={15} />
                </button>
              </div>

              <div className={`et-collapsible-block ${groupSectionExpanded ? 'is-open' : ''}`}>
                <div className="et-collapsible-inner">
                  {groups.map(groupName => {
                    const expanded = expandedGroups[groupName] === true
                    const groupItems = filteredSessions.filter(s => memberships.some(m => str(valueOf(m).conversationId) === s.id && str(valueOf(m).groupName) === groupName))
                    const isGroupMenuOpen = groupMenuName === groupName
                    return (
                      <div key={groupName} className="et-group-block">
                        <div className="et-group-header-row">
                          <button
                            type="button"
                            className="et-group-header"
                            onClick={() => setExpandedGroups(prev => ({ ...prev, [groupName]: !expanded }))}
                          >
                            <ChevronRight size={16} className={`et-group-chevron ${expanded ? 'is-expanded' : ''}`} />
                            <span className="et-group-title">{groupName}</span>
                          </button>
                          <div className="et-menu-anchor">
                            <button
                              type="button"
                              className="et-group-more"
                              aria-label={`分组 ${groupName} 操作`}
                              onClick={e => {
                                e.stopPropagation()
                                setGroupMenuName(isGroupMenuOpen ? undefined : groupName)
                                setSessionMenuId(undefined)
                              }}
                            >
                              <MoreVertical size={15} />
                            </button>
                            {isGroupMenuOpen && (
                              <div className="et-floating-card align-right" role="menu">
                                <button
                                  type="button"
                                  className="et-floating-item"
                                  onClick={() =>
                                    void run(async () => {
                                      setGroupMenuName(undefined)
                                      setExpandedGroups(prev => ({ ...prev, [groupName]: true }))
                                      await newSession(groupName)
                                    })
                                  }
                                >
                                  <Plus size={16} />
                                  <span className="et-floating-item-label">新建会话</span>
                                </button>
                                <button
                                  type="button"
                                  className="et-floating-item"
                                  onClick={() => {
                                    setRenameGroupDialog({ oldName: groupName, newName: groupName })
                                    setGroupMenuName(undefined)
                                  }}
                                >
                                  <Edit3 size={16} />
                                  <span className="et-floating-item-label">重命名分组</span>
                                </button>
                                <div className="et-floating-divider" />
                                <button
                                  type="button"
                                  className="et-floating-item et-danger"
                                  onClick={() => {
                                    setDeleteGroupDialog(groupName)
                                    setGroupMenuName(undefined)
                                  }}
                                >
                                  <Trash2 size={16} />
                                  <span className="et-floating-item-label">删除分组</span>
                                </button>
                              </div>
                            )}
                          </div>
                        </div>
                        <div className={`et-group-collapsible ${expanded ? 'is-open' : ''}`}>
                          <div className="et-group-collapsible-inner">
                            <div className="et-group-children">
                              {groupItems.length > 0 ? groupItems.map(renderSessionRow) : <p className="et-empty-list">暂无会话</p>}
                            </div>
                          </div>
                        </div>
                      </div>
                    )
                  })}
                </div>
              </div>

              {/* 会话区块（不显示冗余数量标签） */}
              <div className="et-drawer-section-head">
                <span>会话</span>
              </div>

              {pinnedSessions.length > 0 && (
                <div className="et-group-block">
                  <div className="et-group-header-row">
                    <button type="button" className="et-group-header" onClick={() => setPinnedExpanded(prev => !prev)}>
                      <ChevronRight size={16} className={`et-group-chevron ${pinnedExpanded ? 'is-expanded' : ''}`} />
                      <span className="et-group-title">已置顶</span>
                    </button>
                  </div>
                  <div className={`et-group-collapsible ${pinnedExpanded ? 'is-open' : ''}`}>
                    <div className="et-group-collapsible-inner">
                      <div className="et-group-children">{pinnedSessions.map(renderSessionRow)}</div>
                    </div>
                  </div>
                </div>
              )}

              {ungroupedSessions.map(renderSessionRow)}
              {!filteredSessions.length && <p className="et-empty-list">{query ? '没有匹配的对话' : ready ? '创建对话，或等待安卓端同步' : '正在读取对话…'}</p>}
            </nav>

            <div className="et-sidebar-bottom">
              <button
                type="button"
                className="et-account-trigger"
                title="设置与模型管理"
                onClick={() => setSettings('models')}
              >
                <span className="et-avatar">{name.slice(0, 1).toUpperCase()}</span>
                <div>
                  <strong>{name}</strong>
                  <small>{session.user.email}</small>
                </div>
              </button>
              <div className="et-sidebar-bottom-actions">
                <button type="button" className="et-sidebar-icon-btn" aria-label="模型与设置" title="模型与设置" onClick={() => setSettings('models')}>
                  <Settings size={17} />
                </button>
                <button type="button" className="et-sidebar-icon-btn" aria-label="退出登录" title="退出当前浏览器" onClick={() => void onSignOut()}>
                  <LogOut size={16} />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* 右侧可拖拽宽度分隔条（双击重置默认宽度，向左拖拽到底自动收起） */}
        {!drawerCollapsed && (
          <div
            className="et-sidebar-resizer et-desktop-only"
            role="separator"
            aria-orientation="vertical"
            aria-label="调整侧边栏宽度"
            title="拖拽调整宽度，双击重置"
            onPointerDown={handleSidebarResizeStart}
            onDoubleClick={resetSidebarWidth}
          />
        )}
      </aside>

      {/* 桌面端收起态 52px 快捷边栏（对齐 QoneAgent CollapsedSidebar） */}
      <aside
        className={`et-collapsed-sidebar et-desktop-only ${drawerCollapsed ? 'is-visible' : ''}`}
        aria-label="快捷导航栏"
        aria-hidden={!drawerCollapsed}
        onClick={e => e.stopPropagation()}
      >
        <div className="et-collapsed-sidebar-clip">
          <div className="et-collapsed-sidebar-inner">
            <div className="et-collapsed-header">
              <button
                type="button"
                className="et-collapsed-icon-btn"
                aria-label="展开侧边栏"
                title="展开侧边栏"
                onClick={() => toggleDesktopDrawer(false)}
              >
                <PanelLeft size={18} />
              </button>
            </div>
            <nav className="et-collapsed-nav" aria-label="快捷操作">
              <button
                type="button"
                className="et-collapsed-icon-btn"
                aria-label="开启新对话"
                title="开启新对话"
                disabled={!ready || busy}
                onClick={() =>
                  void run(async () => {
                    await newSession()
                  })
                }
              >
                <Plus size={18} />
              </button>
              <button
                type="button"
                className="et-collapsed-icon-btn"
                aria-label="搜索会话"
                title="搜索会话"
                onClick={() => {
                  toggleDesktopDrawer(false)
                  setSearchOpen(true)
                  window.setTimeout(() => searchInputRef.current?.focus(), 120)
                }}
              >
                <Search size={17} />
              </button>
              <button
                type="button"
                className="et-collapsed-icon-btn"
                aria-label="创建新分组"
                title="创建新分组"
                onClick={() => {
                  setNewGroupName('')
                  setCreateGroupOpen(true)
                }}
              >
                <Folder size={17} />
              </button>
            </nav>
            <div className="et-collapsed-footer">
              <button
                type="button"
                className="et-collapsed-icon-btn"
                aria-label="模型与设置"
                title="模型与设置"
                onClick={() => setSettings('models')}
              >
                <Settings size={17} />
              </button>
              <button
                type="button"
                className="et-collapsed-avatar-btn"
                aria-label="账号与设置"
                title={`${name} · 账号与设置`}
                onClick={() => setSettings('models')}
              >
                <span className="et-avatar">{name.slice(0, 1).toUpperCase()}</span>
              </button>
            </div>
          </div>
        </div>
      </aside>

      {/* 右侧主对话区 */}
      <section className="et-chat">
        {/* 安卓端 3D 球体粒子点云背景特效（对齐 ParticlesBackground.kt） */}
        <ParticlesBackground />

        {/* 顶部与底部半透明渐变阴影（对齐安卓端 floatingEdgeGradient） */}
        <div className="et-chat-fade-top" aria-hidden="true" />
        <div className="et-chat-fade-bottom" aria-hidden="true" />

        {/* 顶部悬浮栏（对齐安卓端 AppTopBar.kt 与 QoneAgent ThreadHeader） */}
        <header className="et-topbar" onClick={e => e.stopPropagation()}>
          <div className="et-topbar-left">
            <button
              type="button"
              className="et-circle-btn et-mobile-only"
              aria-label="打开侧边栏"
              title="打开侧边栏"
              onClick={() => setMobile(prev => !prev)}
            >
              <Menu size={20} />
            </button>

            <div className="et-menu-anchor">
              <button
                type="button"
                className="et-model-capsule"
                aria-label="切换模型"
                disabled={busy}
                onClick={() => {
                  setModelDropdownOpen(prev => !prev)
                  setTopMoreOpen(false)
                  setFnPanelOpen(false)
                }}
              >
                <span
                  className={`et-model-capsule-text ${topBarModel.isGemini ? 'et-gemini-gradient' : ''}`}
                  style={topBarModel.color ? { color: topBarModel.color } : undefined}
                >
                  {topBarModel.label}
                </span>
              </button>

              {modelDropdownOpen && (
                <div className="et-floating-card align-left" role="listbox" aria-label="模型选择">
                  {models.map(m => {
                    const key = str(m.payload.key)
                    const isSelected = key === str(selectedModel?.payload.key)
                    const label = str(valueOf(m).name, str(valueOf(m).model))
                    return (
                      <button
                        key={m.id}
                        type="button"
                        className={`et-floating-item ${isSelected ? 'is-selected' : ''}`}
                        onClick={() =>
                          void run(async () => {
                            await chooseModel(key)
                            setModelDropdownOpen(false)
                          })
                        }
                      >
                        <ModelIcon model={str(valueOf(m).model)} />
                        <span className="et-floating-item-label">
                          {valueOf(m).imageConfig === true ? '图片 · ' : ''}
                          {label}
                        </span>
                        {isSelected && <Check size={16} />}
                      </button>
                    )
                  })}
                  <div className="et-floating-divider" />
                  <button
                    type="button"
                    className="et-floating-item"
                    onClick={() => {
                      setModelDropdownOpen(false)
                      setSettings('models')
                    }}
                  >
                    <Plus size={16} />
                    <span className="et-floating-item-label">添加或管理模型</span>
                  </button>
                </div>
              )}
            </div>
          </div>

          <div className="et-topbar-right">

            {messages.length > 0 && active ? (
              <div className="et-menu-anchor">
                <div className="et-dual-capsule">
                  <button
                    type="button"
                    aria-label="开启新对话"
                    title="开启新对话"
                    disabled={!ready || busy}
                    onClick={() =>
                      void run(async () => {
                        await newSession()
                      })
                    }
                  >
                    <Plus size={20} />
                  </button>
                  <button
                    type="button"
                    aria-label="更多操作"
                    title="更多操作"
                    onClick={() => {
                      setTopMoreOpen(prev => !prev)
                      setModelDropdownOpen(false)
                      setFnPanelOpen(false)
                    }}
                  >
                    <MoreVertical size={19} />
                  </button>
                </div>

                {topMoreOpen && (
                  <div className="et-floating-card align-right" role="menu">
                    <button
                      type="button"
                      className="et-floating-item"
                      onClick={() =>
                        void run(async () => {
                          await saveSetting('pinned_items', active.id, {}, setting('pinned_items', active.id), pinned.has(active.id))
                          setTopMoreOpen(false)
                        })
                      }
                    >
                      <Pin size={16} />
                      <span className="et-floating-item-label">{pinned.has(active.id) ? '取消置顶' : '置顶'}</span>
                    </button>
                    <button
                      type="button"
                      className="et-floating-item"
                      onClick={() => {
                        setRenameDialog({ session: active, title: str(active.payload.title) })
                        setTopMoreOpen(false)
                      }}
                    >
                      <Edit3 size={16} />
                      <span className="et-floating-item-label">重命名</span>
                    </button>
                    <button
                      type="button"
                      className="et-floating-item"
                      onClick={() => {
                        setMoveGroupDialog(active)
                        setTopMoreOpen(false)
                      }}
                    >
                      <Folder size={16} />
                      <span className="et-floating-item-label">移动到分组</span>
                    </button>
                    <button
                      type="button"
                      className="et-floating-item"
                      onClick={() => {
                        setSystemPromptDraft(currentSystemPrompt)
                        setSystemPromptOpen(true)
                        setTopMoreOpen(false)
                      }}
                    >
                      <Sparkles size={16} />
                      <span className="et-floating-item-label">对话风格</span>
                    </button>
                    <button
                      type="button"
                      className="et-floating-item"
                      onClick={() => {
                        setSettings('models')
                        setTopMoreOpen(false)
                      }}
                    >
                      <Settings size={16} />
                      <span className="et-floating-item-label">设置</span>
                    </button>
                    <div className="et-floating-divider" />
                    <button
                      type="button"
                      className="et-floating-item et-danger"
                      disabled={busy}
                      onClick={() => {
                        setDeleteDialog(active)
                        setTopMoreOpen(false)
                      }}
                    >
                      <Trash2 size={16} />
                      <span className="et-floating-item-label">删除</span>
                    </button>
                  </div>
                )}
              </div>
            ) : (
              <button type="button" className="et-circle-btn" aria-label="打开设置" title="打开设置" onClick={() => setSettings('models')}>
                <Settings size={20} />
              </button>
            )}
          </div>
        </header>

        {sync.error && (
          <div className="et-banner" role="alert">
            <span>{sync.error}</span>
            <button aria-label="关闭提示" onClick={() => setError('')}>
              <X size={15} />
            </button>
          </div>
        )}
        {downloads.error && (
          <div className="et-banner" role="alert">
            <span>{downloads.error}</span>
            <button onClick={() => setSettings('data')}>文件保存设置</button>
          </div>
        )}
        {snapshot.pausedEpoch !== undefined && (
          <div className="et-banner">
            <span>云端数据已被清空，同步已暂停以防旧数据重新上传。本地数据仍保留，请先导出备份。</span>
            <button onClick={exportLocal}>导出备份</button>
          </div>
        )}
        {snapshot.rejected > 0 && (
          <div className="et-banner">
            <span>
              {snapshot.rejected} 条记录被服务端拒绝，原内容仍在本机。
              {snapshot.rejected > snapshot.retryableRejected ? `其中 ${snapshot.rejected - snapshot.retryableRejected} 条需重新编辑后才能上传。` : ''}
            </span>
            {snapshot.retryableRejected > 0 && (
              <button
                onClick={() =>
                  void run(async () => {
                    await currentRuntime().client.retryRejected()
                    await refresh()
                  })
                }
              >
                重试
              </button>
            )}
          </div>
        )}

        {/* 消息流与空对话页（对齐 EmptyChatView.kt、EmptyImageGenerationView.kt 与 BubbleContentTypes.kt） */}
        <div ref={conversationScrollRef} className="et-conversation" onScroll={handleConversationScroll}>
          <div className="et-messages">
            {!messages.length && !streaming && (
              imageMode ? (
                <div className="et-image-empty">
                  <h2 className="et-image-empty-title">
                    开始创作
                    <span className="et-bouncing-dots" aria-hidden="true">
                      <span>.</span>
                      <span>.</span>
                      <span>.</span>
                    </span>
                  </h2>
                  <p className="et-image-empty-sub">输入画面描述，或选择画幅比例与参考图片</p>
                  <div className="et-pill-grid">
                    <button type="button" className="et-pill-card" onClick={() => setRatioDialogOpen(true)}>
                      <SlidersHorizontal size={18} color="#4CAF50" />
                      <span>画幅比例 · {imageAspectRatio || '自动'}</span>
                    </button>
                    <button type="button" className="et-pill-card" onClick={() => imageInput.current?.click()}>
                      <ImageIcon size={18} color="#2196F3" />
                      <span>上传参考图</span>
                    </button>
                    <button
                      type="button"
                      className="et-pill-card"
                      onClick={() => {
                        const textModel = models.find(m => valueOf(m).imageConfig !== true)
                        if (textModel) void run(() => chooseModel(str(textModel.payload.key)))
                      }}
                    >
                      <Sparkles size={18} color="#AB47BC" />
                      <span>切换文本对话</span>
                    </button>
                    <button type="button" className="et-pill-card" onClick={() => setSettings('models')}>
                      <Settings size={18} color="#FF9800" />
                      <span>图像模型配置</span>
                    </button>
                  </div>
                </div>
              ) : (
                <div className="et-welcome">
                  <h2 className="et-welcome-title">
                    EveryTalk，自由对话
                    <span className="et-bouncing-dots" aria-hidden="true">
                      <span>.</span>
                      <span>.</span>
                      <span>.</span>
                    </span>
                  </h2>
                  <div className="et-pill-grid">
                    <button
                      type="button"
                      className="et-pill-card"
                      onClick={() => {
                        const imgModel = models.find(m => valueOf(m).imageConfig === true)
                        if (imgModel) {
                          void run(() => chooseModel(str(imgModel.payload.key)))
                        }
                        input.current?.focus()
                      }}
                    >
                      <ImageIcon size={18} color="#4CAF50" />
                      <span>图像创作</span>
                    </button>
                    <button type="button" className="et-pill-card" onClick={() => startSpeechSession(true)}>
                      <Mic size={18} color="#2196F3" />
                      <span>语音对话</span>
                    </button>
                    <button type="button" className="et-pill-card" onClick={() => setSettings('models')}>
                      <SlidersHorizontal size={18} color="#FF9800" />
                      <span>编辑配置</span>
                    </button>
                    <button
                      type="button"
                      className="et-pill-card"
                      onClick={() => {
                        setSystemPromptDraft(currentSystemPrompt)
                        setSystemPromptOpen(true)
                      }}
                    >
                      <Sparkles size={18} color="#AB47BC" />
                      <span>对话风格</span>
                    </button>
                  </div>
                </div>
              )
            )}

            {messages.map(message => {
              const isUser = message.payload.sender === 'User'
              const attachments = records.filter(
                r => r.entity === 'attachments' && !r.deleted && r.payload.messageId === message.id && (!r.payload.chunkCount || r.payload.chunkIndex === 0)
              )
              const searchResults = readSearchResults(message.payload.webSearchResults)
              const steps = readExecutionSteps(message.payload.executionSteps)
              const archived = archiveViews[message.id]
              const displayParts = archived?.segments ?? displaySegments(message.payload)
              const body = isUser ? bodySegments(message.payload) : undefined
              const messageText = archived?.text || (body ? body.map(part => part.type === 'text' ? part.text : '').join('') : str(message.payload.text))
              const reasoningText = archived?.reasoning || str(message.payload.reasoning)
              const durationMs = Number(
                message.payload.executionDurationMs ??
                  (Number(message.payload.executionFinishedAt ?? 0) > Number(message.payload.executionStartedAt ?? 0)
                    ? Number(message.payload.executionFinishedAt) - Number(message.payload.executionStartedAt)
                    : 0)
              )
              const durationSec = durationMs > 0 ? Math.max(1, Math.round(durationMs / 1000)) : undefined

              const fileParts = messageFileParts(messageText, attachments)
              const referencedIds = new Set(fileParts.flatMap(part => (part.attachment ? [part.attachment.id] : [])))
              const remainingAttachments = attachments.filter(attachment => !referencedIds.has(attachment.id))
              const renderFile = (attachment: StoredRecord, label?: string) => (
                <MessageFileLink
                  attachment={attachment}
                  label={label}
                  savedPath={downloads.savedPaths[attachmentDownloadKey(attachment)]}
                  onOpen={() => previewAttachment(attachment)}
                  onSave={() => download(attachment)}
                  onShare={fileSharing ? () => shareFile(attachment) : undefined}
                />
              )

              const isLongUserMessage = isUser && (messageText.length > 360 || messageText.split('\n').length > 8)
              const isUserExpanded = expandedUserMessages[message.id] === true
              const messageModelName = str(message.payload.modelName, modelLabel)
              const estimatedTokens = Math.max(1, Math.round((messageText.length + reasoningText.length) * 0.65))
              const isAiMenuOpen = aiMenuMessageId === message.id
              const isContextPopupOpen = contextPopupMessageId === message.id

              return (
                <article key={message.id} className={`et-message ${isUser ? 'from-user' : 'from-assistant'}`}>
                  {isUser ? (
                    <div className="et-user-bubble">
                      <UserBubbleClamp
                        isLong={isLongUserMessage}
                        expanded={isUserExpanded}
                        onToggle={() => setExpandedUserMessages(prev => ({ ...prev, [message.id]: !isUserExpanded }))}
                      >
                        {body ? (
                          <span className="et-body-segments">
                            {body.map((part, index) => part.type === 'skill' ? (
                              <span key={index} className="et-skill-file-chip">
                                {part.displayName || part.skillId}
                              </span>
                            ) : part.text ? (
                              <MessageFileText key={index} parts={messageFileParts(part.text, attachments)} renderFile={renderFile} />
                            ) : null)}
                          </span>
                        ) : (
                          <MessageFileText parts={fileParts} renderFile={renderFile} />
                        )}
                      </UserBubbleClamp>
                      {remainingAttachments.length > 0 && (
                        <div className="et-attachments">
                          {remainingAttachments.map(attachment => (
                            <div key={attachment.id} className="et-attachment-item">
                              {str(attachment.payload.kind) === 'image' && imageClient && (
                                <div className="et-image-preview">
                                  <SyncedImage
                                    attachment={attachment}
                                    records={records}
                                    client={imageClient}
                                    session={sessionRef.current}
                                    refreshSession={refreshSession}
                                    onOpenLightbox={info => setImageLightbox(info)}
                                  />
                                </div>
                              )}
                              {attachment.payload.role === 'message_archive' ? (
                                <button onClick={() => void run(() => download(attachment))}>
                                  <FileDown size={15} />
                                  完整历史
                                </button>
                              ) : (
                                renderFile(attachment)
                              )}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  ) : (
                    <div className="et-assistant-body">
                      {displayParts ? (
                        <>
                          {(steps.length > 0 || searchResults.length > 0) && (
                            <ThinkingExecutionBlock
                              reasoning=""
                              steps={steps}
                              searchResults={searchResults}
                              isStreaming={false}
                              hasMainText={true}
                              durationSeconds={durationSec}
                              onOpenTimeline={() =>
                                setTimelineModal({ reasoning: '', steps, searchResults, durationSeconds: durationSec, isStreaming: false })
                              }
                            />
                          )}
                          {displayParts.map((segment, index) =>
                            segment.type === 'reasoning' ? (
                              <ThinkingExecutionBlock
                                key={`reasoning-${index}`}
                                reasoning={segment.text}
                                steps={[]}
                                searchResults={[]}
                                isStreaming={false}
                                hasMainText={true}
                                onOpenTimeline={() =>
                                  setTimelineModal({ reasoning: segment.text, steps: [], searchResults: [], isStreaming: false })
                                }
                              />
                            ) : (
                              <div key={`content-${index}`} className="et-message-text">
                                {renderFormattedMessageText(segment.text, attachments, renderFile)}
                              </div>
                            )
                          )}
                        </>
                      ) : (
                        <>
                          {(Boolean(reasoningText) || steps.length > 0) && (
                            <ThinkingExecutionBlock
                              reasoning={reasoningText}
                              steps={steps}
                              searchResults={searchResults}
                              isStreaming={false}
                              hasMainText={true}
                              durationSeconds={durationSec}
                              onOpenTimeline={() =>
                                setTimelineModal({
                                  reasoning: reasoningText,
                                  steps,
                                  searchResults,
                                  durationSeconds: durationSec,
                                  isStreaming: false,
                                })
                              }
                            />
                          )}
                          <div className="et-message-text">
                            {renderFormattedMessageText(messageText, attachments, renderFile)}
                          </div>
                        </>
                      )}

                      {/* 网页来源胶囊按钮（对齐安卓端 ChatSourcesComponents.kt 的 PageSourcesButton） */}
                      {searchResults.length > 0 && (
                        <button
                          type="button"
                          className="et-sources-pill"
                          onClick={() => setSourcesDialog(searchResults)}
                        >
                          <span className="et-sources-icon-stack">
                            {searchResults.slice(0, 3).map(item => (
                              <span key={item.url} className="et-sources-favicon">
                                <img src={siteFaviconUrl(item.url)} alt="" loading="lazy" />
                              </span>
                            ))}
                          </span>
                          <span>{searchResults.length} 个网页来源</span>
                          <ChevronRight size={14} />
                        </button>
                      )}

                      {remainingAttachments.length > 0 && (
                        <div className="et-attachments">
                          {remainingAttachments.map(attachment => (
                            <div key={attachment.id} className="et-attachment-item">
                              {str(attachment.payload.kind) === 'image' && imageClient && (
                                <div className="et-image-preview">
                                  <SyncedImage
                                    attachment={attachment}
                                    records={records}
                                    client={imageClient}
                                    session={sessionRef.current}
                                    refreshSession={refreshSession}
                                    onOpenLightbox={info => setImageLightbox(info)}
                                  />
                                </div>
                              )}
                              {attachment.payload.role === 'message_archive' ? (
                                <button onClick={() => void run(() => download(attachment))}>
                                  <FileDown size={15} />
                                  完整历史
                                </button>
                              ) : (
                                renderFile(attachment)
                              )}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}

                  {/* 消息底部操作栏（对齐安卓端 AiMessageFooterItem / AiMessagePopupMenu 与 User MessageContextMenu） */}
                  <div className="et-message-tools" onClick={e => e.stopPropagation()}>
                    <button
                      type="button"
                      className={`et-msg-circle-btn ${isUser ? 'is-user-btn' : ''}`}
                      aria-label="复制消息"
                      title="复制全文"
                      onClick={() =>
                        void run(async () => {
                          await navigator.clipboard.writeText(messageText)
                          setCopied(message.id)
                          window.setTimeout(() => setCopied(''), 1500)
                        })
                      }
                    >
                      {copied === message.id ? <Check size={15} /> : <Copy size={15} />}
                    </button>

                    {isUser ? (
                      <>
                        <button
                          type="button"
                          className="et-msg-circle-btn is-user-btn"
                          aria-label="编辑并重发"
                          title="编辑消息内容"
                          onClick={() => {
                            setDraft(messageText)
                            input.current?.focus()
                          }}
                        >
                          <Edit3 size={15} />
                        </button>
                        <button
                          type="button"
                          className="et-msg-circle-btn is-user-btn"
                          aria-label="重新生成回复"
                          title="基于此条消息重新生成回复"
                          disabled={busy}
                          onClick={() => void regenerateFromMessage(message)}
                        >
                          <RefreshCw size={15} />
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                        type="button"
                        className="et-msg-circle-btn"
                        aria-label="分享回复"
                        title="分享或复制内容"
                        onClick={() => {
                          if (typeof navigator !== 'undefined' && navigator.share) {
                            void navigator.share({ text: messageText }).catch(() => null)
                          } else {
                            void navigator.clipboard.writeText(messageText)
                            setCopied(message.id)
                            window.setTimeout(() => setCopied(''), 1500)
                          }
                        }}
                      >
                        <Share2 size={15} />
                      </button>

                        {/* 上下文用量圆环按钮（完全对齐安卓端 AiContextUsageButton & ContextUsageRing 17dp 环形图） */}
                        <div className="et-menu-anchor">
                          <button
                            type="button"
                            className="et-context-ring-btn"
                            aria-label="查看上下文用量"
                            title={`上下文用量 · ~${estimatedTokens} tok（点击查看详情）`}
                            onClick={() => {
                              setContextPopupMessageId(isContextPopupOpen ? undefined : message.id)
                              setAiMenuMessageId(undefined)
                            }}
                          >
                            <ContextUsageRing fraction={Math.min(1, estimatedTokens / 8192)} hasData={true} />
                          </button>

                          {isContextPopupOpen && (
                            <div className="et-floating-card align-bottom-left et-context-stats-card" role="dialog" aria-label="上下文统计">
                              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                <strong style={{ fontSize: 13.5, fontWeight: 600 }}>上下文用量</strong>
                                <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 999, background: 'var(--soft)', color: 'var(--muted)' }}>
                                  已统计
                                </span>
                              </div>
                              <div className="et-context-stat-row">
                                <span>使用模型</span>
                                <strong>{messageModelName || '默认模型'}</strong>
                              </div>
                              {str(message.payload.providerName) && (
                                <div className="et-context-stat-row">
                                  <span>模型服务商</span>
                                  <strong>{str(message.payload.providerName)}</strong>
                                </div>
                              )}
                              <div className="et-context-stat-row">
                                <span>本次输出估算</span>
                                <strong>~{estimatedTokens} tokens</strong>
                              </div>
                              <div className="et-context-stat-row">
                                <span>正文总字数</span>
                                <strong>{messageText.length} 字符</strong>
                              </div>
                              {durationSec !== undefined && (
                                <div className="et-context-stat-row">
                                  <span>回复用时</span>
                                  <strong>{durationSec} 秒</strong>
                                </div>
                              )}
                            </div>
                          )}
                        </div>

                        {/* 更多操作悬浮卡片（对齐安卓端 AiMessagePopupMenu：重新生成、切换模型重答、导出文本、举报不当内容） */}
                        <div className="et-menu-anchor">
                          <button
                            type="button"
                            className="et-msg-circle-btn"
                            aria-label="更多消息操作"
                            title="更多操作"
                            onClick={() => {
                              setAiMenuMessageId(isAiMenuOpen ? undefined : message.id)
                              setAiMenuPickerMode(false)
                              setContextPopupMessageId(undefined)
                            }}
                          >
                            <MoreHorizontal size={16} />
                          </button>

                          {isAiMenuOpen && (
                            <div className="et-floating-card align-bottom-left" role="menu">
                              {!aiMenuPickerMode ? (
                                <>
                                  <button
                                    type="button"
                                    className="et-floating-item"
                                    disabled={busy}
                                    onClick={() => {
                                      setAiMenuMessageId(undefined)
                                      void regenerateFromMessage(message)
                                    }}
                                  >
                                    <RefreshCw size={16} />
                                    <span className="et-floating-item-label">重新生成</span>
                                  </button>
                                  <button
                                    type="button"
                                    className="et-floating-item"
                                    disabled={busy}
                                    onClick={() => setAiMenuPickerMode(true)}
                                  >
                                    <Sparkles size={16} />
                                    <span className="et-floating-item-label">切换模型重答</span>
                                    <ChevronRight size={15} />
                                  </button>
                                  <button
                                    type="button"
                                    className="et-floating-item"
                                    onClick={() => {
                                      setAiMenuMessageId(undefined)
                                      exportMessageMarkdown(message)
                                    }}
                                  >
                                    <FileDown size={16} />
                                    <span className="et-floating-item-label">导出文本 (.md)</span>
                                  </button>
                                  <div className="et-floating-divider" />
                                  <button
                                    type="button"
                                    className="et-floating-item et-danger"
                                    disabled={reported.includes(message.id)}
                                    onClick={() => {
                                      setAiMenuMessageId(undefined)
                                      setReport({ message, category: 'OTHER', details: '', sending: false })
                                    }}
                                  >
                                    <Flag size={16} />
                                    <span className="et-floating-item-label">
                                      {reported.includes(message.id) ? '已举报该内容' : '举报不当内容'}
                                    </span>
                                  </button>
                                </>
                              ) : (
                                <>
                                  <button
                                    type="button"
                                    className="et-floating-item"
                                    onClick={() => setAiMenuPickerMode(false)}
                                  >
                                    <span className="et-floating-item-label" style={{ color: 'var(--muted)', fontSize: 12 }}>
                                      ← 选择模型重答
                                    </span>
                                  </button>
                                  <div className="et-floating-divider" />
                                  {models.map(m => {
                                    const key = str(m.payload.key)
                                    const label = str(valueOf(m).name, str(valueOf(m).model))
                                    return (
                                      <button
                                        key={m.id}
                                        type="button"
                                        className="et-floating-item"
                                        onClick={() => {
                                          setAiMenuMessageId(undefined)
                                          setAiMenuPickerMode(false)
                                          setConfirmRegenerate({ message, modelKey: key, modelLabel: label })
                                        }}
                                      >
                                        <ModelIcon model={str(valueOf(m).model)} />
                                        <span className="et-floating-item-label">{label}</span>
                                      </button>
                                    )
                                  })}
                                </>
                              )}
                            </div>
                          )}
                        </div>
                      </>
                    )}
                  </div>
                </article>
              )
            })}

            {streaming && streaming.sessionId === activeId && (
              <article className="et-message from-assistant" aria-live="polite">
                <div className="et-assistant-body">
                  {Boolean(streaming.reasoning) || Boolean(streaming.executionSteps?.length) ? (
                    <ThinkingExecutionBlock
                      reasoning={streaming.reasoning}
                      steps={streaming.executionSteps ?? []}
                      searchResults={[]}
                      isStreaming={true}
                      hasMainText={Boolean(streaming.text.trim())}
                      activeStatus={streaming.activeStatus}
                      durationSeconds={elapsedSeconds}
                      onOpenTimeline={() =>
                        setTimelineModal({
                          reasoning: streaming.reasoning,
                          steps: streaming.executionSteps ?? [],
                          searchResults: [],
                          durationSeconds: elapsedSeconds,
                          isStreaming: true,
                        })
                      }
                    />
                  ) : (
                    !streaming.text && (
                      <div className="et-loading-stage">
                        <AndroidLoadingRing size={16} strokeWidth={2.2} />
                        <span className="et-shimmer-text">
                          {imageMode ? '正在生成图片' : streaming.activeStatus || '正在连接模型'}
                        </span>
                        <span>· {elapsedSeconds}s</span>
                      </div>
                    )
                  )}
                  {streaming.text && (
                    <div className="et-message-text">
                      {renderFormattedMessageText(streaming.text, [], () => null)}
                    </div>
                  )}
                </div>
              </article>
            )}

            
            <div ref={messagesEnd} />
          </div>
        </div>

        {/* 回到底部悬浮圆钮（对齐安卓端 ScrollToBottomButton.kt） */}
        {showScrollBottom && (
          <button
            type="button"
            className="et-scroll-bottom-btn"
            aria-label="回到底部"
            title="回到底部"
            onClick={scrollToBottom}
          >
            <ArrowDown size={18} />
          </button>
        )}

        {/* 底部渐变遮罩与输入栏（对齐安卓端 ChatInputArea.kt & ImageGenerationInputArea.kt） */}
        <div className="et-composer-wrap">
          <div className="et-composer-area" onClick={e => e.stopPropagation()}>
            <input
              ref={fileInput}
              type="file"
              multiple
              className="et-hidden"
              onChange={e => {
                setFiles(items => [...items, ...Array.from(e.target.files ?? [])].slice(0, 5))
                e.target.value = ''
              }}
            />
            <input
              ref={imageInput}
              type="file"
              accept="image/*"
              multiple
              className="et-hidden"
              onChange={e => {
                setFiles(items => [...items, ...Array.from(e.target.files ?? [])].slice(0, 5))
                e.target.value = ''
              }}
            />
            <input
              ref={cameraInput}
              type="file"
              accept="image/*"
              capture="environment"
              className="et-hidden"
              onChange={e => {
                setFiles(items => [...items, ...Array.from(e.target.files ?? [])].slice(0, 5))
                e.target.value = ''
              }}
            />

            <div className={`et-collapsible-block ${files.length > 0 ? 'is-open' : ''}`}>
              <div className="et-collapsible-inner">
                <div className="et-draft-files">
                  {files.map((file, index) => (
                    <div key={`${file.name}:${index}`} className="et-draft-file-card">
                      <span>{file.name}</span>
                      <button
                        type="button"
                        className="et-draft-file-remove"
                        aria-label={`移除 ${file.name}`}
                        onClick={() => setFiles(items => items.filter((_, i) => i !== index))}
                      >
                        <X size={12} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            <LiquidComposerRow
              separated={
                inputFocused ||
                Boolean(draft.trim()) ||
                files.length > 0 ||
                Boolean(imageMode) ||
                webSearchEnabled ||
                mcpEnabled ||
                agentEnabled ||
                promptEnabled ||
                fnPanelOpen
              }
              plusSlot={plusWidth => (
                <div className="et-plus-wrap" style={{ width: `${Math.round(plusWidth)}px` }}>
                  <button
                    type="button"
                    className="et-plus-btn"
                    aria-label="打开更多功能"
                    title="更多功能"
                    disabled={!ready || busy}
                    onClick={() => {
                      setFnPanelOpen(prev => !prev)
                      setModelDropdownOpen(false)
                      setTopMoreOpen(false)
                    }}
                  >
                    <Plus size={22} />
                  </button>

                  {fnPanelOpen && (
                    <div className="et-floating-card align-bottom-left et-fn-panel" role="menu" aria-label="功能面板">
                      {imageMode ? (
                        <>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              setRatioDialogOpen(true)
                            }}
                          >
                            <span className="et-fn-icon" style={{ color: '#66B5FF' }}>
                              <SlidersHorizontal size={20} />
                            </span>
                            <span className="et-fn-label">画幅比例：{imageAspectRatio || '自动'}</span>
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              setRatioDialogOpen(true)
                            }}
                          >
                            <span className="et-fn-icon" style={{ color: '#9C27B0' }}>
                              <Sparkles size={20} />
                            </span>
                            <span className="et-fn-label">图片质量：{imageQualitySize}</span>
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              imageInput.current?.click()
                            }}
                          >
                            <span className="et-fn-icon" style={{ color: '#2CB334' }}>
                              <ImageIcon size={20} />
                            </span>
                            <span className="et-fn-label">选择图片</span>
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              cameraInput.current?.click()
                            }}
                          >
                            <span className="et-fn-icon" style={{ color: '#2196F3' }}>
                              <Camera size={20} />
                            </span>
                            <span className="et-fn-label">拍照</span>
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              setSettings('models')
                            }}
                          >
                            <span className="et-fn-icon" style={{ color: '#FF9800' }}>
                              <Settings size={20} />
                            </span>
                            <span className="et-fn-label">调整参数</span>
                          </button>
                          {(Boolean(draft.trim()) || files.length > 0) && (
                            <button
                              type="button"
                              className="et-fn-row"
                              onClick={() => {
                                setFnPanelOpen(false)
                                setDraft('')
                                setFiles([])
                              }}
                            >
                              <span className="et-fn-icon">
                                <X size={20} />
                              </span>
                              <span className="et-fn-label">清空内容</span>
                            </button>
                          )}
                        </>
                      ) : (
                        <>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              imageInput.current?.click()
                            }}
                          >
                            <span className="et-fn-icon">
                              <ImageIcon size={20} />
                            </span>
                            <span className="et-fn-label">图片</span>
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              cameraInput.current?.click()
                            }}
                          >
                            <span className="et-fn-icon">
                              <Camera size={20} />
                            </span>
                            <span className="et-fn-label">相机</span>
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              fileInput.current?.click()
                            }}
                          >
                            <span className="et-fn-icon">
                              <Paperclip size={20} />
                            </span>
                            <span className="et-fn-label">附件</span>
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() =>
                              void run(async () => {
                                await toggleWebSearch()
                                setFnPanelOpen(false)
                              })
                            }
                          >
                            <span className="et-fn-icon" style={webSearchEnabled ? { color: '#66B5FF' } : undefined}>
                              <Globe size={20} />
                            </span>
                            <span className="et-fn-label" style={webSearchEnabled ? { color: '#66B5FF', fontWeight: 600 } : undefined}>
                              联网搜索
                            </span>
                            {webSearchEnabled && <Check size={16} color="#66B5FF" />}
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() =>
                              void run(async () => {
                                await toggleMcp()
                                setFnPanelOpen(false)
                              })
                            }
                          >
                            <span className="et-fn-icon" style={mcpEnabled ? { color: '#FF9800' } : undefined}>
                              <Wrench size={19} />
                            </span>
                            <span className="et-fn-label" style={mcpEnabled ? { color: '#FF9800', fontWeight: 600 } : undefined}>
                              MCP
                            </span>
                            {mcpEnabled && <Check size={16} color="#FF9800" />}
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() =>
                              void run(async () => {
                                await toggleAgent()
                                setFnPanelOpen(false)
                              })
                            }
                          >
                            <span className="et-fn-icon" style={agentEnabled ? { color: '#26A69A' } : undefined}>
                              <Terminal size={19} />
                            </span>
                            <span className="et-fn-label" style={agentEnabled ? { color: '#26A69A', fontWeight: 600 } : undefined}>
                              远程代理
                            </span>
                            {agentEnabled && <Check size={16} color="#26A69A" />}
                          </button>
                          <button
                            type="button"
                            className="et-fn-row"
                            onClick={() => {
                              setFnPanelOpen(false)
                              setSystemPromptDraft(currentSystemPrompt)
                              setSystemPromptOpen(true)
                            }}
                          >
                            <span className="et-fn-icon" style={promptEnabled ? { color: '#CE93D8' } : undefined}>
                              <Sparkles size={20} />
                            </span>
                            <span className="et-fn-label" style={promptEnabled ? { color: '#CE93D8', fontWeight: 600 } : undefined}>
                              系统提示词
                            </span>
                            {promptEnabled && <Check size={16} color="#CE93D8" />}
                          </button>
                        </>
                      )}
                    </div>
                  )}
                </div>
              )}
              pillSlot={({ marginLeft, paddingLeft }) => (
                <div
                  className="et-composer-pill"
                  style={{
                    marginLeft: `${Math.round(marginLeft)}px`,
                    paddingLeft: `${Math.round(paddingLeft)}px`,
                  }}
                >
                  <div
                    className={`et-collapsible-block ${
                      Boolean(imageMode || webSearchEnabled || mcpEnabled || agentEnabled || promptEnabled)
                        ? 'is-open'
                        : ''
                    }`}
                  >
                    <div className="et-collapsible-inner">
                      <div className="et-active-tags">
                        {imageMode && (
                          <div
                            className="et-active-tag is-ratio"
                            title={imageAspectRatio ? `画幅比例 ${imageAspectRatio} · 点击修改，点击 X 重置为自动` : '生图模式 · 点击选择画幅比例，点击 X 切回文本对话'}
                          >
                            <span
                              className="et-active-tag-body"
                              onClick={() => setRatioDialogOpen(true)}
                            >
                              <span
                                className="et-ratio-mini-box"
                                style={{
                                  width: currentRatioMeta ? Math.round(currentRatioMeta.widthPx * 0.65) : 12,
                                  height: currentRatioMeta ? Math.round(currentRatioMeta.heightPx * 0.65) : 12,
                                }}
                              />
                              <span>
                                {imageAspectRatio ? `${imageAspectRatio} · ${imageQualitySize}` : `生图 · 自动 · ${imageQualitySize}`}
                              </span>
                            </span>
                            <button
                              type="button"
                              className="et-active-tag-close"
                              aria-label={imageAspectRatio ? '重置画幅比例为自动' : '退出生图模式，切回文本对话'}
                              title={imageAspectRatio ? '重置画幅比例为自动' : '退出生图模式，切回文本对话'}
                              onClick={e => {
                                e.stopPropagation()
                                if (imageAspectRatio) {
                                  void run(async () => {
                                    await saveConversationImageRatio('', imageQualitySize)
                                  })
                                } else {
                                  const textModel = models.find(m => valueOf(m).imageConfig !== true)
                                  if (textModel) void run(() => chooseModel(str(textModel.payload.key)))
                                }
                              }}
                            >
                              <X size={12} />
                            </button>
                          </div>
                        )}
                        {!imageMode && webSearchEnabled && (
                          <div className="et-active-tag" title="已开启联网搜索 · 点击 X 关闭">
                            <span
                              className="et-active-tag-body"
                              onClick={() => void run(toggleWebSearch)}
                            >
                              <Globe size={13} />
                              <span>联网搜索</span>
                            </span>
                            <button
                              type="button"
                              className="et-active-tag-close"
                              aria-label="关闭联网搜索"
                              title="关闭联网搜索"
                              onClick={e => {
                                e.stopPropagation()
                                void run(toggleWebSearch)
                              }}
                            >
                              <X size={12} />
                            </button>
                          </div>
                        )}
                        {!imageMode && mcpEnabled && (
                          <div className="et-active-tag is-mcp" title="已开启 MCP 工具 · 点击管理，点击 X 关闭">
                            <span
                              className="et-active-tag-body"
                              onClick={() => setSettings('mcp')}
                            >
                              <Wrench size={13} />
                              <span>MCP</span>
                            </span>
                            <button
                              type="button"
                              className="et-active-tag-close"
                              aria-label="关闭 MCP"
                              title="关闭 MCP"
                              onClick={e => {
                                e.stopPropagation()
                                void run(toggleMcp)
                              }}
                            >
                              <X size={12} />
                            </button>
                          </div>
                        )}
                        {!imageMode && agentEnabled && (
                          <div className="et-active-tag is-agent" title="已开启远程代理 · 点击管理服务器，点击 X 关闭">
                            <span
                              className="et-active-tag-body"
                              onClick={() => setSettings('servers')}
                            >
                              <Terminal size={13} />
                              <span>远程代理</span>
                            </span>
                            <button
                              type="button"
                              className="et-active-tag-close"
                              aria-label="关闭远程代理"
                              title="关闭远程代理"
                              onClick={e => {
                                e.stopPropagation()
                                void run(toggleAgent)
                              }}
                            >
                              <X size={12} />
                            </button>
                          </div>
                        )}
                        {promptEnabled && (
                          <div className="et-active-tag is-prompt" title="已启用系统提示词 · 点击编辑，点击 X 关闭">
                            <span
                              className="et-active-tag-body"
                              onClick={() => {
                                setSystemPromptDraft(currentSystemPrompt)
                                setSystemPromptOpen(true)
                              }}
                            >
                              <Sparkles size={13} />
                              <span>系统提示词</span>
                            </span>
                            <button
                              type="button"
                              className="et-active-tag-close"
                              aria-label="关闭系统提示词"
                              title="关闭系统提示词"
                              onClick={e => {
                                e.stopPropagation()
                                void run(async () => {
                                  await saveConversationSystemPrompt(currentSystemPrompt, false)
                                })
                              }}
                            >
                              <X size={12} />
                            </button>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className="et-composer-main">
                    <textarea
                      ref={input}
                      value={draft}
                      disabled={!ready || listening}
                      aria-label="消息内容"
                      rows={1}
                      placeholder={imageMode ? '描述你想生成的图片…' : webSearchEnabled ? '搜索网络...' : ready ? '发消息...' : '正在打开工作区…'}
                      onFocus={() => setInputFocused(true)}
                      onBlur={() => setInputFocused(false)}
                      onChange={e => setDraft(e.target.value)}
                      onKeyDown={e => {
                        if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                          e.preventDefault()
                          void send()
                        }
                      }}
                    />

                    {busy ? (
                      <button type="button" className="et-send" aria-label="停止生成" onClick={() => controller.current?.abort()}>
                        <Square size={14} fill="currentColor" />
                      </button>
                    ) : draft.trim() || files.length > 0 ? (
                      <button type="button" className="et-send" disabled={!ready} aria-label={imageMode ? '生成图片' : '发送消息'} onClick={() => void send()}>
                        <ArrowUp size={18} strokeWidth={2.5} />
                      </button>
                    ) : (
                      <button
                        type="button"
                        className={`et-send ${listening ? 'is-listening' : ''}`}
                        disabled={!ready}
                        aria-label={listening ? '停止语音输入' : '语音输入'}
                        title={listening ? '停止语音输入' : '语音输入'}
                        onClick={toggleVoiceInput}
                      >
                        <Mic size={18} />
                      </button>
                    )}
                  </div>
                </div>
              )}
            />
          </div>
        </div>
      </section>

      {/* 设置面板弹窗 */}
      {settings && (
        <SettingsPanel
          records={settingsPanelRecords}
          onClose={() => setSettings(undefined)}
          saveSetting={saveSetting}
          saveMemory={saveMemory}
          error={sync.error}
          reportError={setError}
          downloads={downloads}
          initialTab={settings}
        />
      )}

      {/* 思考与工具执行完整时间线弹窗（对齐安卓端 ReasoningBottomSheet & ThinkingExecutionTimeline.kt） */}
      {timelineModal && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setTimelineModal(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="思考与执行过程" className="et-timeline-dialog">
            <div className="et-dialog-header">
              <div>
                <h3>思考与执行时间线</h3>
                <p>
                  {timelineModal.durationSeconds
                    ? `总耗时 ${timelineModal.durationSeconds} 秒`
                    : timelineModal.isStreaming
                      ? '正在实时执行中'
                      : '完整推理与工具调用记录'}
                </p>
              </div>
              <button type="button" aria-label="关闭时间线" onClick={() => setTimelineModal(undefined)}>
                <X size={18} />
              </button>
            </div>
            <div className="et-timeline-scroll">
              <div className="et-timeline">
                {Boolean(timelineModal.reasoning.trim()) && (
                  <div className="et-timeline-node">
                    <div className="et-timeline-track">
                      <span className="et-timeline-line is-top is-hidden" />
                      <span className="et-timeline-marker is-thinking">
                        <Sparkles size={12} />
                      </span>
                      <span className="et-timeline-line is-bottom" />
                    </div>
                    <div className="et-timeline-content">
                      <div className="et-timeline-title">思考过程</div>
                      <div className="et-timeline-reasoning">{timelineModal.reasoning}</div>
                    </div>
                  </div>
                )}

                {timelineModal.steps.map((step, index) => {
                  const isFirst = !timelineModal.reasoning.trim() && index === 0
                  return (
                    <div key={step.id} className="et-timeline-node">
                      <div className="et-timeline-track">
                        <span className={`et-timeline-line is-top ${isFirst ? 'is-hidden' : ''}`} />
                        <span
                          className={`et-timeline-marker ${
                            step.isError
                              ? 'is-error'
                              : step.type === 'search'
                                ? 'is-search'
                                : step.type === 'web'
                                  ? 'is-web'
                                  : 'is-tool'
                          }`}
                        >
                          {step.type === 'search' ? (
                            <Search size={12} />
                          ) : step.type === 'web' ? (
                            <Globe size={12} />
                          ) : (
                            <Wrench size={12} />
                          )}
                        </span>
                        <span className="et-timeline-line is-bottom" />
                      </div>
                      <div className="et-timeline-content">
                        <div className="et-timeline-title">
                          {step.type === 'search' ? '搜索网页' : step.type === 'web' ? '读取网页' : '调用工具'}
                        </div>
                        <div className="et-capsule-wrap">
                          {step.type === 'web'
                            ? step.labels.map(url => (
                                <a
                                  key={url}
                                  href={url.startsWith('http') ? url : `https://${url}`}
                                  target="_blank"
                                  rel="noreferrer"
                                  className="et-website-capsule"
                                >
                                  <img src={siteFaviconUrl(url)} alt="" loading="lazy" />
                                  <span>{extractHost(url)}</span>
                                </a>
                              ))
                            : step.labels.map(label => (
                                <span key={label} className="et-capsule-label">
                                  <span>{label}</span>
                                </span>
                              ))}
                        </div>
                      </div>
                    </div>
                  )
                })}

                {timelineModal.searchResults.length > 0 && (
                  <div className="et-timeline-node">
                    <div className="et-timeline-track">
                      <span
                        className={`et-timeline-line is-top ${
                          !timelineModal.reasoning.trim() && timelineModal.steps.length === 0 ? 'is-hidden' : ''
                        }`}
                      />
                      <span className="et-timeline-marker is-web">
                        <Globe size={12} />
                      </span>
                      <span className="et-timeline-line is-bottom" />
                    </div>
                    <div className="et-timeline-content">
                      <div className="et-timeline-title">已浏览网页 ({timelineModal.searchResults.length})</div>
                      <div className="et-capsule-wrap">
                        {timelineModal.searchResults.map(item => (
                          <a key={item.url} href={item.url} target="_blank" rel="noreferrer" className="et-website-capsule">
                            <img src={siteFaviconUrl(item.url)} alt="" loading="lazy" />
                            <span>{extractHost(item.url)}</span>
                          </a>
                        ))}
                      </div>
                    </div>
                  </div>
                )}

                <div className="et-timeline-node">
                  <div className="et-timeline-track">
                    <span className="et-timeline-line is-top" />
                    <span className="et-timeline-marker is-done">
                      <CheckCircle2 size={13} />
                    </span>
                    <span className="et-timeline-line is-bottom is-hidden" />
                  </div>
                  <div className="et-timeline-content">
                    <div className="et-timeline-title">{timelineModal.isStreaming ? '正在执行中…' : '思考完成'}</div>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </div>
      )}

      {/* 网页来源详情对话框（对齐安卓端 WebSourcesDialog.kt） */}
      {sourcesDialog && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setSourcesDialog(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="网页来源" className="et-sources-dialog">
            <div className="et-dialog-header">
              <div>
                <h3>网页来源 ({sourcesDialog.length})</h3>
                <p>点击任意条目在新标签页打开原始网页</p>
              </div>
              <button type="button" aria-label="关闭网页来源" onClick={() => setSourcesDialog(undefined)}>
                <X size={18} />
              </button>
            </div>
            <div className="et-source-list">
              {sourcesDialog.map((item, idx) => (
                <a key={item.url} href={item.url} target="_blank" rel="noreferrer" className="et-source-item">
                  <div className="et-source-head">
                    <span className="et-source-favicon-lg">
                      <img src={siteFaviconUrl(item.url)} alt="" loading="lazy" />
                    </span>
                    <div className="et-source-meta">
                      <div className="et-source-title">
                        {idx + 1}. {item.title || extractHost(item.url)}
                      </div>
                      <span className="et-source-url">{item.url}</span>
                    </div>
                  </div>
                  {item.snippet && <div className="et-source-snippet">{item.snippet}</div>}
                </a>
              ))}
            </div>
          </section>
        </div>
      )}

      {/* 代码块全屏查看与预览对话框（对齐安卓端 FullScreenCodeViewerDialog.kt） */}
      {fullscreenCode && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setFullscreenCode(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="全屏代码查看" className="et-code-fullscreen-dialog">
            <header className="et-code-fullscreen-header">
              <span className="et-code-lang">{fullscreenCode.language.toUpperCase()}</span>
              <div className="et-code-actions">
                {(fullscreenCode.language === 'html' || fullscreenCode.language === 'svg' || fullscreenCode.language === 'xml') && (
                  <button
                    type="button"
                    className={`et-code-capsule ${fullscreenCode.preview ? 'is-active' : ''}`}
                    onClick={() => setFullscreenCode({ ...fullscreenCode, preview: !fullscreenCode.preview })}
                  >
                    {fullscreenCode.preview ? <Code2 size={13} /> : <Eye size={13} />}
                    <span>{fullscreenCode.preview ? '查看代码' : '渲染预览'}</span>
                  </button>
                )}
                <button
                  type="button"
                  className="et-code-capsule"
                  onClick={() => void navigator.clipboard.writeText(fullscreenCode.code)}
                >
                  <Copy size={13} />
                  <span>复制代码</span>
                </button>
                <button type="button" aria-label="关闭全屏代码" onClick={() => setFullscreenCode(undefined)}>
                  <X size={18} />
                </button>
              </div>
            </header>
            <div className="et-code-fullscreen-body">
              {fullscreenCode.preview ? (
                <iframe
                  title="全屏渲染预览"
                  className="et-code-preview-frame"
                  sandbox=""
                  srcDoc={
                    fullscreenCode.language === 'svg'
                      ? `<!doctype html><html><body style="margin:0;display:grid;place-items:center;min-height:100vh">${fullscreenCode.code}</body></html>`
                      : fullscreenCode.code
                  }
                />
              ) : (
                <pre className="et-code-pre">
                  <code>{fullscreenCode.code}</code>
                </pre>
              )}
            </div>
          </section>
        </div>
      )}

      {/* 图像比例与清晰度选择对话框（对齐安卓端 ImageRatioSelectionDialog.kt） */}
      {ratioDialogOpen && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setRatioDialogOpen(false) }}>
          <section role="dialog" aria-modal="true" aria-label="选择图像比例" className="et-dialog">
            <div className="et-dialog-header">
              <div>
                <h3>选择图像比例</h3>
                <p>适用于当前图像会话的画幅比例与清晰度档位</p>
              </div>
              <button type="button" aria-label="关闭图像比例弹窗" onClick={() => setRatioDialogOpen(false)}>
                <X size={18} />
              </button>
            </div>

            <div className="et-quality-selector">
              <span>清晰度</span>
              <div className="et-quality-pills">
                {(['2K', '4K'] as const).map(q => (
                  <button
                    key={q}
                    type="button"
                    className={`et-quality-btn ${imageQualitySize === q ? 'is-active' : ''}`}
                    onClick={() => void run(() => saveConversationImageRatio(imageAspectRatio, q))}
                  >
                    {q}
                  </button>
                ))}
              </div>
            </div>

            <button
              type="button"
              className={`et-ratio-auto-card ${!imageAspectRatio ? 'is-selected' : ''}`}
              onClick={() =>
                void run(async () => {
                  await saveConversationImageRatio('', imageQualitySize)
                  setRatioDialogOpen(false)
                })
              }
            >
              <div>
                <strong>自动 (AUTO)</strong>
                <small>根据提示词自动设置最佳画幅</small>
              </div>
              {!imageAspectRatio && <Check size={18} />}
            </button>

            <div className="et-ratio-grid">
              {IMAGE_RATIO_OPTIONS.map(item => {
                const isSelected = imageAspectRatio === item.ratio
                return (
                  <button
                    key={item.ratio}
                    type="button"
                    className={`et-ratio-card ${isSelected ? 'is-selected' : ''}`}
                    onClick={() =>
                      void run(async () => {
                        await saveConversationImageRatio(item.ratio, imageQualitySize)
                        setRatioDialogOpen(false)
                      })
                    }
                  >
                    <span
                      className="et-ratio-preview-box"
                      style={{ width: item.widthPx, height: item.heightPx }}
                    />
                    <div>
                      <strong>{item.ratio}</strong>
                      <small>{item.size2k}</small>
                    </div>
                  </button>
                )
              })}
            </div>
          </section>
        </div>
      )}

      {/* 沉浸式语音对话大屏（对齐安卓端 VoiceInputScreen.kt & VoiceOrb.kt） */}
      {voiceOverlayOpen && (
        <div className="et-voice-overlay" role="dialog" aria-modal="true" aria-label="语音对话">
          <ParticlesBackground forceWhite />
          <div className="et-voice-top">
            <span>语音对话 · {voiceLanguage}</span>
            <button
              type="button"
              className="et-voice-btn"
              style={{ width: 40, height: 40 }}
              aria-label="关闭语音界面"
              onClick={() => {
                recognition.current?.stop()
                setVoiceOverlayOpen(false)
              }}
            >
              <X size={18} />
            </button>
          </div>

          <div className="et-voice-stage">
            <div className="et-voice-orb-wrap">
              {listening && <span className="et-voice-ring" />}
              {listening && <span className="et-voice-ring delay-1" />}
              <div className="et-voice-orb">
                <Mic size={36} color="#FFFFFF" />
              </div>
            </div>
            <div className="et-voice-status">{listening ? '正在聆听，请说话…' : '语音已暂停，可点击下方发送或重说'}</div>
            <div className="et-voice-transcript">{draft || '识别到的文字会实时显示在这里…'}</div>
          </div>

          <div className="et-voice-controls">
            <button
              type="button"
              className="et-voice-btn"
              title="清空并重新说话"
              onClick={() => {
                recognition.current?.stop()
                setDraft('')
                window.setTimeout(() => startSpeechSession(true), 120)
              }}
            >
              <RotateCcw size={22} />
            </button>
            <button
              type="button"
              className="et-voice-btn is-primary"
              title={listening ? '停止聆听' : '开始聆听'}
              onClick={() => {
                if (listening) recognition.current?.stop()
                else startSpeechSession(true)
              }}
            >
              {listening ? <Square size={22} fill="currentColor" /> : <Mic size={26} />}
            </button>
            <button
              type="button"
              className="et-voice-btn"
              title="完成并发送"
              disabled={!draft.trim()}
              onClick={() => {
                recognition.current?.stop()
                setVoiceOverlayOpen(false)
                void send()
              }}
            >
              <ArrowUp size={24} />
            </button>
          </div>
        </div>
      )}



      {/* 系统提示词 / 对话风格弹窗（对齐安卓端 SystemPromptDialog.kt） */}
      {systemPromptOpen && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setSystemPromptOpen(false) }}>
          <section role="dialog" aria-modal="true" aria-label="系统提示词" className="et-dialog et-dialog-wide">
            <div className="et-dialog-header">
              <div className="et-appinfo-left">
                <span className="et-dialog-icon-badge">
                  <Sparkles size={20} />
                </span>
                <div>
                  <h3>系统提示词</h3>
                  <p>设置当前会话中 AI 的角色、专业背景与回复风格</p>
                </div>
              </div>
              <div className="et-dialog-header-actions">
                {systemPromptDraft.trim() && (
                  <button
                    type="button"
                    className="et-msg-circle-btn"
                    aria-label="清空提示词"
                    title="清空提示词"
                    onClick={() => {
                      setSystemPromptDraft('')
                      void run(async () => {
                        await saveConversationSystemPrompt('', false)
                      })
                    }}
                  >
                    <Trash2 size={17} />
                  </button>
                )}
                <button type="button" className="et-msg-circle-btn" aria-label="关闭系统提示词弹窗" onClick={() => setSystemPromptOpen(false)}>
                  <X size={18} />
                </button>
              </div>
            </div>
            <textarea
              rows={7}
              value={systemPromptDraft}
              onChange={e => setSystemPromptDraft(e.target.value)}
              placeholder="例如：你是一个温柔耐心的全栈架构导师，回答请直接给出关键结论、架构权衡与可运行代码…"
            />
            <div className="et-dialog-actions">
              <button type="button" className="et-dialog-cancel" onClick={() => setSystemPromptOpen(false)}>
                取消
              </button>
              <button
                type="button"
                className="et-dialog-confirm"
                onClick={() =>
                  void run(async () => {
                    await saveConversationSystemPrompt(systemPromptDraft.trim(), Boolean(systemPromptDraft.trim()))
                    setSystemPromptOpen(false)
                  })
                }
              >
                确定
              </button>
            </div>
          </section>
        </div>
      )}

      {/* 重命名会话对话框（对齐安卓端 DrawerDialogs.kt） */}
      {renameDialog && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setRenameDialog(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="重命名会话" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge">
                <Edit3 size={20} />
              </span>
              <div>
                <h3>重命名会话</h3>
                <p>修改后的标题将自动加密同步至所有已登录设备</p>
              </div>
            </div>
            <form
              onSubmit={e => {
                e.preventDefault()
                const target = renameDialog.session
                const nextTitle = renameDialog.title.trim() || '未命名对话'
                void run(async () => {
                  await currentRuntime().client.saveLocal('sessions', target.id, { ...target.payload, title: nextTitle, clientModifiedAt: updated(target) })
                  await changed()
                  setRenameDialog(undefined)
                })
              }}
            >
              <input
                autoFocus
                aria-label="会话标题"
                value={renameDialog.title}
                onChange={e => setRenameDialog({ ...renameDialog, title: e.target.value })}
                placeholder="输入会话名称"
              />
              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setRenameDialog(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm">
                  确定
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* 删除会话确认对话框（对齐安卓端 DeleteConfirmationDialog） */}
      {deleteDialog && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setDeleteDialog(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="删除会话" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge is-danger">
                <Trash2 size={21} />
              </span>
              <div>
                <h3>确定删除此项？</h3>
                <p>删除后将同步移除「{str(deleteDialog.payload.title, '未命名对话')}」及其关联消息。</p>
              </div>
            </div>
            <div className="et-dialog-actions">
              <button type="button" className="et-dialog-cancel" onClick={() => setDeleteDialog(undefined)}>
                取消
              </button>
              <button
                type="button"
                className="et-dialog-danger"
                onClick={() =>
                  void run(async () => {
                    const target = deleteDialog
                    setDeleteDialog(undefined)
                    await removeSession(target)
                  })
                }
              >
                确认删除
              </button>
            </div>
          </section>
        </div>
      )}

      {/* 清空所有记录确认对话框（对齐安卓端 ClearAllConfirmationDialog） */}
      {clearAllOpen && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setClearAllOpen(false) }}>
          <section role="dialog" aria-modal="true" aria-label="清空记录" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge is-danger">
                <Trash2 size={21} />
              </span>
              <div>
                <h3>清空所有聊天记录？</h3>
                <p>此操作将清空当前账号下的所有对话记录且无法撤销。</p>
              </div>
            </div>
            <div className="et-dialog-actions">
              <button type="button" className="et-dialog-cancel" onClick={() => setClearAllOpen(false)}>
                取消
              </button>
              <button
                type="button"
                className="et-dialog-danger"
                onClick={() =>
                  void run(async () => {
                    setClearAllOpen(false)
                    await clearAllSessions()
                  })
                }
              >
                确认清空
              </button>
            </div>
          </section>
        </div>
      )}

      {/* 创建新分组对话框（对齐安卓端 CreateGroupDialog） */}
      {createGroupOpen && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setCreateGroupOpen(false) }}>
          <section role="dialog" aria-modal="true" aria-label="创建新分组" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge">
                <Folder size={21} />
              </span>
              <div>
                <h3>创建新分组</h3>
                <p>将相关主题的对话归类到同一文件夹中管理</p>
              </div>
            </div>
            <form
              onSubmit={e => {
                e.preventDefault()
                const trimmed = newGroupName.trim()
                if (!trimmed) return
                saveCustomGroups([...customGroups, trimmed])
                setExpandedGroups(prev => ({ ...prev, [trimmed]: true }))
                setNewGroupName('')
                setCreateGroupOpen(false)
              }}
            >
              <input
                autoFocus
                aria-label="分组名称"
                placeholder="输入分组名称（例如：架构设计、日常灵感）"
                value={newGroupName}
                onChange={e => setNewGroupName(e.target.value)}
              />
              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setCreateGroupOpen(false)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={!newGroupName.trim()}>
                  创建
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* 重命名分组对话框 */}
      {renameGroupDialog && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setRenameGroupDialog(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="重命名分组" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge">
                <Folder size={21} />
              </span>
              <div>
                <h3>重命名分组</h3>
                <p>为分组「{renameGroupDialog.oldName}」设置新的名称</p>
              </div>
            </div>
            <form
              onSubmit={e => {
                e.preventDefault()
                const { oldName, newName } = renameGroupDialog
                void run(async () => {
                  await renameGroup(oldName, newName)
                  setRenameGroupDialog(undefined)
                })
              }}
            >
              <input
                autoFocus
                aria-label="分组新名称"
                placeholder="输入新的分组名称"
                value={renameGroupDialog.newName}
                onChange={e => setRenameGroupDialog({ ...renameGroupDialog, newName: e.target.value })}
              />
              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" onClick={() => setRenameGroupDialog(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={!renameGroupDialog.newName.trim()}>
                  确定
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* 删除分组确认对话框 */}
      {deleteGroupDialog && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setDeleteGroupDialog(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="删除分组" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge is-danger">
                <Trash2 size={21} />
              </span>
              <div>
                <h3>删除分组「{deleteGroupDialog}」？</h3>
                <p>分组内的对话不会被删除，将自动移回未分组列表。</p>
              </div>
            </div>
            <div className="et-dialog-actions">
              <button type="button" className="et-dialog-cancel" onClick={() => setDeleteGroupDialog(undefined)}>
                取消
              </button>
              <button
                type="button"
                className="et-dialog-danger"
                onClick={() =>
                  void run(async () => {
                    const target = deleteGroupDialog
                    setDeleteGroupDialog(undefined)
                    await deleteGroup(target)
                  })
                }
              >
                确认删除
              </button>
            </div>
          </section>
        </div>
      )}

      {/* 移动到分组对话框（对齐安卓端 MoveToGroupDialog） */}
      {moveGroupDialog && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setMoveGroupDialog(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="移动到分组" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge">
                <Folder size={21} />
              </span>
              <div>
                <h3>移动到分组</h3>
                <p>将「{str(moveGroupDialog.payload.title, '未命名对话')}」归入指定分组</p>
              </div>
            </div>
            {groups.length === 0 && !groupedSessionIds.has(moveGroupDialog.id) ? (
              <div className="et-empty-group-box">
                <p className="et-empty-list">当前还没有创建任何分组</p>
                <button
                  type="button"
                  className="et-filter-chip is-active"
                  onClick={() => {
                    setMoveGroupDialog(undefined)
                    setNewGroupName('')
                    setCreateGroupOpen(true)
                  }}
                >
                  <Plus size={14} />
                  <span>立即创建新分组</span>
                </button>
              </div>
            ) : (
              <div className="et-group-picker-list">
                {groupedSessionIds.has(moveGroupDialog.id) && (
                  <button
                    type="button"
                    className="et-group-option-row et-danger"
                    onClick={() =>
                      void run(async () => {
                        const target = moveGroupDialog
                        setMoveGroupDialog(undefined)
                        await moveSessionToGroup(target, null)
                      })
                    }
                  >
                    <FolderMinus size={18} />
                    <span>移出当前分组</span>
                  </button>
                )}
                {groups.map(groupName => (
                  <button
                    key={groupName}
                    type="button"
                    className="et-group-option-row"
                    onClick={() =>
                      void run(async () => {
                        const target = moveGroupDialog
                        setMoveGroupDialog(undefined)
                        await moveSessionToGroup(target, groupName)
                      })
                    }
                  >
                    <Folder size={18} />
                    <span>{groupName}</span>
                  </button>
                ))}
              </div>
            )}
            <div className="et-dialog-actions">
              <button type="button" className="et-dialog-cancel" onClick={() => setMoveGroupDialog(undefined)}>
                取消
              </button>
            </div>
          </section>
        </div>
      )}

      {/* 举报不当内容对话框（对齐安卓端 AiContentReportDialog.kt：红色旗标头 + 7 项单选卡片 + 0/500 字数统计） */}
      {report && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget && !report.sending) setReport(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="举报不当内容" className="et-dialog et-dialog-wide">
            <div className="et-dialog-header">
              <div>
                <div className="et-report-title-row">
                  <Flag size={22} color="#EF5350" />
                  <h3>举报不当内容</h3>
                </div>
                <p>请选择举报原因，我们将审核该 AI 回复及关联的模型信息。</p>
              </div>
              <button type="button" className="et-msg-circle-btn" aria-label="关闭举报弹窗" disabled={report.sending} onClick={() => setReport(undefined)}>
                <X size={18} />
              </button>
            </div>
            <form
              onSubmit={e => {
                e.preventDefault()
                void run(sendReport)
              }}
            >
              <div className="et-report-list" role="radiogroup" aria-label="举报原因">
                {reportCategories.map(([id, label]) => {
                  const isSelected = report.category === id
                  return (
                    <button
                      key={id}
                      type="button"
                      role="radio"
                      aria-checked={isSelected}
                      className={`et-report-card ${isSelected ? 'is-selected' : ''}`}
                      onClick={() => setReport({ ...report, category: id })}
                    >
                      <span className="et-report-radio">
                        {isSelected && <span className="et-report-radio-dot" />}
                      </span>
                      <div className="et-report-card-body">
                        <strong>{label}</strong>
                        <small>{REPORT_CATEGORY_DESCRIPTIONS[id] ?? ''}</small>
                      </div>
                    </button>
                  )
                })}
              </div>

              <label className="et-form-field">
                <span className="et-form-label">补充说明（选填）</span>
                <textarea
                  rows={3}
                  maxLength={500}
                  value={report.details}
                  onChange={e => setReport({ ...report, details: e.target.value })}
                  placeholder="补充具体问题细节，帮助我们更快定位与处理…"
                />
              </label>
              <div className="et-report-counter">{report.details.length}/500</div>

              <div className="et-dialog-actions">
                <button type="button" className="et-dialog-cancel" disabled={report.sending} onClick={() => setReport(undefined)}>
                  取消
                </button>
                <button type="submit" className="et-dialog-confirm" disabled={report.sending}>
                  {report.sending ? '正在提交…' : '提交举报'}
                </button>
              </div>
            </form>
          </section>
        </div>
      )}

      {/* 切换模型重答确认对话框（对齐安卓端 ConfirmModelRegenerateDialog） */}
      {confirmRegenerate && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setConfirmRegenerate(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="切换模型重答" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <span className="et-dialog-icon-badge">
                <Sparkles size={21} />
              </span>
              <div>
                <h3>切换模型重新生成？</h3>
                <p>将使用「{confirmRegenerate.modelLabel}」重新生成本轮回复。</p>
              </div>
            </div>
            <div className="et-dialog-actions">
              <button type="button" className="et-dialog-cancel" onClick={() => setConfirmRegenerate(undefined)}>
                取消
              </button>
              <button
                type="button"
                className="et-dialog-confirm"
                onClick={() => {
                  const target = confirmRegenerate
                  setConfirmRegenerate(undefined)
                  void regenerateFromMessage(target.message, target.modelKey)
                }}
              >
                确认重答
              </button>
            </div>
          </section>
        </div>
      )}

      {/* 关于 EveryTalk 对话框（对齐安卓端 ChatScreenDialogs.kt AboutDialog） */}
      {aboutOpen && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setAboutOpen(false) }}>
          <section role="dialog" aria-modal="true" aria-label="关于 EveryTalk" className="et-dialog">
            <div className="et-dialog-header is-centered">
              <img src="/favicon.svg" width="48" height="48" alt="" style={{ borderRadius: 14 }} />
              <div>
                <h3>EveryTalk</h3>
                <p>版本 Web 1.7.9 · 与安卓端端到端加密同步</p>
              </div>
            </div>
            <p className="et-muted" style={{ lineHeight: 1.65, textAlign: 'center', fontSize: 13.5 }}>
              EveryTalk 是一款开源、简洁、全功能的跨平台 AI 助手客户端，支持 OpenAI、Claude、Gemini、DeepSeek、Qwen 等主流多模态文本与生图模型，并提供跨设备端到端加密同步。
            </p>
            <div className="et-appinfo-card">
              <a
                href="https://github.com/roseforljh/KunTalkwithAi"
                target="_blank"
                rel="noreferrer"
                className="et-appinfo-row"
              >
                <div className="et-appinfo-left">
                  <span className="et-appinfo-icon">
                    <Globe size={18} />
                  </span>
                  <div className="et-appinfo-text">
                    <strong>GitHub 开源仓库</strong>
                    <small>github.com/roseforljh/KunTalkwithAi</small>
                  </div>
                </div>
                <ChevronRight size={16} />
              </a>
            </div>
            <div className="et-dialog-actions">
              <button type="button" className="et-dialog-confirm" onClick={() => setAboutOpen(false)}>
                完成
              </button>
            </div>
          </section>
        </div>
      )}

      {/* 全屏图片预览与继续编辑操作栏（对齐安卓端 ImagePreviewDialog.kt & ImageContextMenu） */}
      {imageLightbox && (
        <div className="et-img-lightbox" role="dialog" aria-modal="true" aria-label="图片预览" onClick={e => { if (e.target === e.currentTarget) setImageLightbox(undefined) }}>
          <img src={imageLightbox.src} alt={str(imageLightbox.attachment.payload.name, '预览图片')} />
          <div className="et-img-lightbox-actions">
            <button
              type="button"
              className="et-img-lightbox-btn"
              onClick={() => {
                const refFile = imageLightbox.file
                setImageLightbox(undefined)
                setFiles(items => [...items, refFile].slice(0, 5))
                const imgModel = models.find(m => valueOf(m).imageConfig === true)
                if (!imageMode && imgModel) {
                  void run(() => chooseModel(str(imgModel.payload.key)))
                }
                input.current?.focus()
              }}
            >
              <ImageIcon size={16} />
              <span>作为参考图继续编辑</span>
            </button>
            <button
              type="button"
              className="et-img-lightbox-btn"
              onClick={() => void run(() => download(imageLightbox.attachment))}
            >
              <FileDown size={16} />
              <span>下载图片</span>
            </button>
            <button type="button" className="et-img-lightbox-btn" onClick={() => setImageLightbox(undefined)}>
              <X size={16} />
              <span>关闭</span>
            </button>
          </div>
        </div>
      )}

      {/* 完整历史归档对话框 */}
      {history && (
        <div className="et-modal-backdrop" onClick={e => { if (e.target === e.currentTarget) setHistory(undefined) }}>
          <section role="dialog" aria-modal="true" aria-label="完整历史" className="et-dialog et-dialog-wide">
            <div className="et-dialog-header">
              <div>
                <h3>完整历史归档</h3>
                <p>归档已完成解密和摘要校验，以下内容仅供查看</p>
              </div>
              <button type="button" className="et-msg-circle-btn" aria-label="关闭历史" onClick={() => setHistory(undefined)}>
                <X size={18} />
              </button>
            </div>
            <pre className="et-history">{JSON.stringify(history.value, null, 2)}</pre>
          </section>
        </div>
      )}
    </main>
  )
}
