/* eslint-disable @next/next/no-img-element */
import { Sparkles } from 'lucide-react'

/** 模型名称优先于转发平台；本地 SVG 不发起网站图标请求，未知模型使用统一兜底。 */
export function modelBrand(model: string): string | undefined {
  const name = model.toLowerCase()
  const brands: [string, RegExp][] = [
    ['anthropic', /claude|anthropic/], ['google', /gemini|gemma|google/], ['qwen', /qwen|通义|z-image/],
    ['deepseek', /deepseek/], ['moonshot', /kimi|moonshot/], ['zhipu', /glm|zhipu/],
    ['minimax', /minimax/], ['mistral', /mistral|codestral/], ['meta', /llama/], ['doubao', /doubao|seedream/],
    ['kolors', /kolors|kwai/], ['openai', /(?:^|[\/\s_-])(?:gpt|o[134](?:-|$)|chatgpt|dall-e)|openai/],
  ]
  return brands.find(([, pattern]) => pattern.test(name))?.[0]
}

export function ModelIcon({ model }: { model: string }) {
  const brand = modelBrand(model)
  return <span className={`et-model-icon${brand === 'openai' ? ' et-model-icon-mono' : ''}`} aria-hidden="true">{brand ? <img src={`/model-icons/${brand}.svg`} alt="" width={24} height={24} /> : <Sparkles size={20} />}</span>
}
