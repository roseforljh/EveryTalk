'use client'

import { Image as ImageIcon, FileText, Headphones, Video, ArrowDownLeft, ArrowUpRight, RefreshCw } from 'lucide-react'
import { imageProtocol } from './model'
import { ModelIcon } from './ModelIcon'
import {
  findCatalogModel,
  imageParameterKeys,
  modalityLabels,
  modelCapabilities,
  parameterProtocol,
  withCatalogModel,
  type CatalogModel,
} from './model-capabilities'
import { isObject } from './sync-core'
import type { JsonObject } from './types'

const modalityIcons = { text: FileText, image: ImageIcon, audio: Headphones, video: Video }
const imageFields: Record<string, { label: string; placeholder: string; numeric?: boolean }> = {
  imageSize: { label: '图片尺寸', placeholder: '1024x1024 / 1K / 2K' },
  aspectRatio: { label: '宽高比', placeholder: '1:1 / 16:9 / 9:16' },
  inferenceSteps: { label: '推理步数', placeholder: '30', numeric: true },
  guidanceScale: { label: '引导系数', placeholder: '7.5', numeric: true },
  quality: { label: '图片质量', placeholder: 'auto / low / medium / high' },
  batchSize: { label: '生成数量', placeholder: '1', numeric: true },
}

/**
 * 模型能力与高级参数编辑区块（对齐安卓端 ModelParametersDialog.kt 与 QoneAgent 模型参数面板）。
 * 将输入/输出模态开关、协议选择、文本推理参数与生图参数分区卡片化展示。
 */
export function ModelParametersFields({
  config,
  onChange,
  catalog,
  loading,
  error,
  onRefresh,
}: {
  config: JsonObject
  onChange: (config: JsonObject) => void
  catalog: CatalogModel[]
  loading: boolean
  error: string
  onRefresh: () => void
}) {
  const parameters = isObject(config.modelParameters) ? config.modelParameters : {}
  const capability = modelCapabilities(config)
  const protocol = parameterProtocol(config)
  const format = imageProtocol(config)
  const matched = findCatalogModel(catalog, String(config.model ?? ''), String(config.provider ?? ''))
  const imageOutput = capability.output.includes('image')
  const textOutput = capability.output.includes('text')

  // 与安卓一致：兼容接口未保存自定义参数时默认 reasoning_effort=medium。
  const customParameters = Array.isArray(parameters.customParameters)
    ? parameters.customParameters
    : [{ name: 'reasoning_effort', value: 'medium', type: 'STRING', enabled: true }]
  const customEffort = customParameters.find(
    item => isObject(item) && String(item.name).trim().toLowerCase() === 'reasoning_effort'
  )
  const effort =
    protocol === 'OPENAI_COMPATIBLE'
      ? isObject(customEffort) && customEffort.enabled !== false
        ? String(customEffort.value)
        : 'none'
      : String(parameters.reasoningMode === 'DISABLED' ? 'none' : parameters.reasoningEffort ?? 'medium')

  const patch = (values: JsonObject): void => onChange({ ...config, ...values })
  const patchParameters = (values: JsonObject): void => patch({ modelParameters: { ...parameters, ...values } })

  const imageParameterNames: Record<string, string> = {
    aspectRatio: 'imageAspectRatio',
    quality: 'imageQuality',
    batchSize: 'imageBatchSize',
  }
  const ranges: Record<string, [number, number, number | 'any']> = {
    outputLimit: [1, 9999999, 1],
    inferenceSteps: [1, 1000, 1],
    guidanceScale: [0, 100, 'any'],
    batchSize: [1, 10, 1],
  }

  const field = (key: string, label: string, placeholder: string, numeric = true) => (
    <label key={key} className="et-form-field">
      <span className="et-form-label">{label}</span>
      <input
        type={numeric ? 'number' : 'text'}
        min={ranges[key]?.[0]}
        max={ranges[key]?.[1]}
        step={ranges[key]?.[2]}
        value={String(parameters[imageParameterNames[key]] ?? config[key] ?? '')}
        placeholder={placeholder}
        onChange={event => {
          const value = numeric ? (event.target.value === '' ? null : Number(event.target.value)) : event.target.value
          patch({
            [key]: value,
            ...(imageParameterNames[key] ? { modelParameters: { ...parameters, [imageParameterNames[key]]: value } } : {}),
          })
        }}
      />
    </label>
  )

  const imageChoice = (key: string, label: string, options: string[]) => (
    <label key={key} className="et-form-field">
      <span className="et-form-label">{label}</span>
      <select
        value={String(parameters[imageParameterNames[key]] ?? config[key] ?? '')}
        onChange={event => {
          const value = event.target.value || null
          patch({
            [key]: value,
            ...(imageParameterNames[key] ? { modelParameters: { ...parameters, [imageParameterNames[key]]: value } } : {}),
          })
        }}
      >
        <option value="">默认</option>
        {options.map(value => (
          <option value={value} key={value}>
            {value}
          </option>
        ))}
      </select>
    </label>
  )

  return (
    <div className="et-model-params-stack">
      <section className="et-model-capabilities">
        <div className="et-model-summary">
          <div className="et-model-summary-left">
            <span className="et-model-icon-badge">
              <ModelIcon model={String(config.model ?? '')} />
            </span>
            <div>
              <strong>模型模态与能力</strong>
              <small>{matched ? '已匹配 models.dev 标准目录 · 支持手动微调' : '自定义模态能力 · 可从目录自动匹配'}</small>
            </div>
          </div>
          <button
            type="button"
            className="et-filter-chip"
            disabled={loading}
            onClick={onRefresh}
            aria-label="刷新 models.dev 模型目录"
          >
            <RefreshCw size={13} className={loading ? 'et-spin' : ''} />
            <span>{loading ? '同步中' : '刷新目录'}</span>
          </button>
        </div>

        {error && (
          <p role="status" className="et-muted" style={{ fontSize: 12 }}>
            {error}
          </p>
        )}

        {matched && (
          <button
            type="button"
            className="et-catalog-apply"
            onClick={() =>
              onChange(
                withCatalogModel(
                  {
                    ...config,
                    modelParameters: { ...parameters, inputModalities: null, outputModalities: null, supportsReasoning: null },
                  },
                  matched
                )
              )
            }
          >
            应用「{matched.name}」的预设模态与上下文限制
          </button>
        )}

        <div className="et-capability-rows">
          {(['input', 'output'] as const).map(direction => (
            <div className="et-capability-line" key={direction}>
              <span className="et-capability-dir">
                {direction === 'input' ? <ArrowDownLeft size={15} /> : <ArrowUpRight size={15} />}
                <span>{direction === 'input' ? '输入模态' : '输出模态'}</span>
              </span>
              <div className="et-capability-options">
                {Object.entries(modalityLabels).map(([id, label]) => {
                  const Icon = modalityIcons[id as keyof typeof modalityIcons]
                  const selected = capability[direction].includes(id)
                  return (
                    <button
                      key={id}
                      type="button"
                      data-modality={id}
                      aria-pressed={selected}
                      onClick={() => {
                        const values = selected
                          ? capability[direction].filter(value => value !== id)
                          : [...capability[direction], id]
                        if (!values.length) return
                        patch({
                          ...(direction === 'output'
                            ? { imageConfig: values.includes('image'), modality: values.includes('image') ? 'IMAGE' : 'TEXT' }
                            : {}),
                          modelParameters: { ...parameters, [`${direction}Modalities`]: values },
                        })
                      }}
                    >
                      <Icon size={14} />
                      <span>{label}</span>
                    </button>
                  )
                })}
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="et-parameter-section">
        <div className="et-parameter-section-head">
          <h4>请求协议与推理参数</h4>
          <small>控制底层接口适配器、上下文窗口及思考深度</small>
        </div>
        <div className="et-parameter-grid">
          <label className="et-form-field">
            <span className="et-form-label">API 协议格式</span>
            <select value={protocol} onChange={event => patchParameters({ protocolOverride: event.target.value })}>
              <option value="OPENAI_COMPATIBLE">OpenAI · Chat Completions</option>
              <option value="CODEX">OpenAI · Responses</option>
              <option value="ANTHROPIC">Anthropic · Messages</option>
              <option value="GEMINI">Google · Generate Content</option>
            </select>
          </label>

          {textOutput && (
            <>
              <label className="et-form-field">
                <span className="et-form-label">采样温度 (Temperature · 0~2)</span>
                <input
                  type="number"
                  min="0"
                  max="2"
                  step="0.1"
                  placeholder="0.7 (默认)"
                  value={String(parameters.temperature ?? config.temperature ?? '')}
                  onChange={event => {
                    const val = event.target.value === '' ? null : Number(event.target.value)
                    patch({
                      temperature: val,
                      modelParameters: { ...parameters, temperature: val },
                    })
                  }}
                />
              </label>
              <label className="et-form-field">
                <span className="et-form-label">核采样 (Top-P · 0~1)</span>
                <input
                  type="number"
                  min="0"
                  max="1"
                  step="0.05"
                  placeholder="1.0 (默认)"
                  value={String(parameters.topP ?? config.topP ?? '')}
                  onChange={event => {
                    const val = event.target.value === '' ? null : Number(event.target.value)
                    patch({
                      topP: val,
                      modelParameters: { ...parameters, topP: val },
                    })
                  }}
                />
              </label>
              {field('outputLimit', '最大输出 Tokens', '4096')}
              <label className="et-form-field">
                <span className="et-form-label">上下文窗口 Tokens</span>
                <input
                  type="number"
                  min="2"
                  max="10000000"
                  value={String(parameters.contextLimit ?? 128000)}
                  onChange={event => patchParameters({ contextLimit: Number(event.target.value) })}
                />
              </label>
              <label className="et-form-field">
                <span className="et-form-label">思考程度 (Reasoning Effort)</span>
                <select
                  value={effort}
                  onChange={event => {
                    const value = event.target.value
                    const effortParameter = { name: 'reasoning_effort', value, type: 'STRING', enabled: true }
                    const replaced = customParameters
                      .map(item =>
                        isObject(item) && String(item.name).trim().toLowerCase() === 'reasoning_effort'
                          ? effortParameter
                          : item
                      )
                      .filter(
                        (item, index, items) => item !== effortParameter || items.indexOf(effortParameter) === index
                      )
                    const next = replaced.includes(effortParameter) ? replaced : [...replaced, effortParameter]
                    patchParameters(
                      protocol === 'OPENAI_COMPATIBLE'
                        ? { reasoningEffort: value, reasoningMode: 'EFFORT', customParameters: next }
                        : {
                            reasoningEffort: value,
                            reasoningMode: value === 'none' && protocol !== 'CODEX' ? 'DISABLED' : 'EFFORT',
                          }
                    )
                  }}
                >
                  {(protocol === 'ANTHROPIC'
                    ? ['none', 'low', 'medium', 'high', 'max']
                    : protocol === 'GEMINI'
                      ? ['none', 'minimal', 'low', 'medium', 'high']
                      : protocol === 'CODEX'
                        ? ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']
                        : ['none', 'low', 'medium', 'high', 'xhigh', 'max']
                  )
                    .concat(effort)
                    .filter((value, index, values) => values.indexOf(value) === index)
                    .map(value => (
                      <option key={value} value={value}>
                        {value}
                      </option>
                    ))}
                </select>
              </label>
              {['ANTHROPIC', 'GEMINI'].includes(protocol) && (
                <label className="et-form-field">
                  <span className="et-form-label">思考预算 Tokens</span>
                  <input
                    type="number"
                    min="1024"
                    max="10000000"
                    step="1"
                    value={String(parameters.thoughtBudget ?? 2048)}
                    onChange={event =>
                      patchParameters({ thoughtBudget: Number(event.target.value), reasoningMode: 'BUDGET' })
                    }
                  />
                </label>
              )}
            </>
          )}
        </div>
      </section>

      {imageOutput && (
        <section className="et-parameter-section">
          <div className="et-parameter-section-head">
            <h4>图片生成参数 {format === 'qwen' ? '· 需上传参考图' : ''}</h4>
            <small>设置默认输出尺寸、画幅比例与推理步数</small>
          </div>
          {format === 'unsupported' && (
            <p className="et-parameter-note">当前 API 协议未接入图片生成，请在上方选择支持图片生成的接口协议。</p>
          )}
          <div className="et-parameter-grid">
            {imageParameterKeys(format).map(key => {
              const item = imageFields[key]
              if (format !== 'openai' && key === 'imageSize')
                return imageChoice(key, item.label, format === 'gemini' ? ['1K', '2K', '4K'] : ['HD', '2K'])
              if (format !== 'openai' && key === 'aspectRatio')
                return imageChoice(
                  key,
                  item.label,
                  format === 'modal'
                    ? ['1:1', '16:9', '9:16', ...(String(config.imageSize).toUpperCase() === '2K' ? ['4:3'] : [])]
                    : ['1:1', '2:3', '3:2', '3:4', '4:3', '4:5', '5:4', '9:16', '16:9', '21:9']
                )
              return field(key, item.label, item.placeholder, item.numeric ?? false)
            })}
          </div>
        </section>
      )}

      {capability.input.includes('audio') && (
        <p className="et-parameter-note">音频输入能力已记录，聊天附件仍受当前接口支持的上传格式限制。</p>
      )}
      {capability.input.includes('video') && (
        <p className="et-parameter-note">视频输入能力已记录，当前聊天适配器暂不直接上传视频文件流。</p>
      )}
      {capability.output.some(item => item === 'audio' || item === 'video') && (
        <p role="status" className="et-parameter-note">
          音视频输出能力标签已保存；当前会话面板暂未启用流式音视频生成参数。
        </p>
      )}
    </div>
  )
}
