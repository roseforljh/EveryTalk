/** 浏览器语音接口的最小类型边界，避免把供应商语音密钥或音频写入同步数据。 */
export interface BrowserSpeechRecognitionEvent extends Event {
  results: { length: number; [index: number]: { [index: number]: { transcript: string } } }
}

export interface BrowserSpeechRecognitionErrorEvent extends Event { error: string }

export interface BrowserSpeechRecognition {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((event: BrowserSpeechRecognitionEvent) => void) | null
  onerror: ((event: BrowserSpeechRecognitionErrorEvent) => void) | null
  onend: (() => void) | null
  start: () => void
  stop: () => void
}

type SpeechRecognitionFactory = new () => BrowserSpeechRecognition
type VoiceWindow = Window & { SpeechRecognition?: SpeechRecognitionFactory; webkitSpeechRecognition?: SpeechRecognitionFactory }

/** 兼容 Chromium 前缀版本；不支持时返回 undefined，由界面给出明确提示。 */
export function getSpeechRecognition(scope: Window): BrowserSpeechRecognition | undefined {
  const target = scope as VoiceWindow
  const Factory = target.SpeechRecognition ?? target.webkitSpeechRecognition
  return Factory ? new Factory() : undefined
}

/** 只接受浏览器语言标签，避免把同步配置中的任意内容写入语音接口。 */
export function normalizeVoiceLanguage(value: unknown): string {
  const candidate = typeof value === 'string' ? value.trim() : ''
  return /^[a-z]{2,3}(?:-[A-Z]{2,4})?$/.test(candidate) ? candidate : 'zh-CN'
}

/** 朗读交给浏览器语音服务，是否联网取决于所选语音；调用方负责取消旧朗读和管理按钮状态。 */
export function speakText(scope: Window, text: string, language: string, onEnd: () => void): boolean {
  if (!scope.speechSynthesis || typeof SpeechSynthesisUtterance !== 'function' || !text.trim()) return false
  const utterance = new SpeechSynthesisUtterance(text)
  utterance.lang = language
  utterance.onend = onEnd
  utterance.onerror = onEnd
  scope.speechSynthesis.speak(utterance)
  return true
}
