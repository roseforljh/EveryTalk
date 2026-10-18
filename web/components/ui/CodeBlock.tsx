'use client'

import { useState } from 'react'
import { Check, Copy } from 'lucide-react'

type TokenKind = 'comment' | 'string' | 'keyword' | 'number' | 'function' | 'jsx' | 'plain'

type CodeToken = {
  kind: TokenKind
  value: string
}

const keywords = new Set([
  'as',
  'class',
  'const',
  'data',
  'else',
  'false',
  'fun',
  'if',
  'import',
  'in',
  'interface',
  'is',
  'object',
  'private',
  'return',
  'suspend',
  'true',
  'val',
  'var',
  'when',
])

function pushToken(tokens: CodeToken[], kind: TokenKind, value: string) {
  if (value) tokens.push({ kind, value })
}

/**
 * 轻量 Kotlin/TSX 高亮器：只负责官网代码块需要的几类语法，不引入运行时高亮依赖。
 * 代码仍以文本节点渲染，因此不会把源码当成 HTML 执行。
 */
function tokenizeLine(line: string): CodeToken[] {
  const tokens: CodeToken[] = []
  let index = 0

  while (index < line.length) {
    const current = line[index]

    if (line.startsWith('//', index)) {
      pushToken(tokens, 'comment', line.slice(index))
      break
    }

    if (current === '"' || current === "'" || current === '`') {
      const quote = current
      let end = index + 1
      while (end < line.length) {
        if (line[end] === '\\') {
          end += 2
          continue
        }
        if (line[end] === quote) {
          end += 1
          break
        }
        end += 1
      }
      pushToken(tokens, 'string', line.slice(index, end))
      index = end
      continue
    }

    if (current === '<' && /[A-Za-z/]/.test(line[index + 1] ?? '')) {
      const end = line.indexOf('>', index + 1)
      if (end !== -1) {
        pushToken(tokens, 'jsx', line.slice(index, end + 1))
        index = end + 1
        continue
      }
    }

    if (/\d/.test(current)) {
      const match = line.slice(index).match(/^\d+(?:\.\d+)?/)
      const value = match?.[0] ?? current
      pushToken(tokens, 'number', value)
      index += value.length
      continue
    }

    if (/[A-Za-z_]/.test(current)) {
      const match = line.slice(index).match(/^[A-Za-z_][A-Za-z0-9_]*/)
      const value = match?.[0] ?? current
      const after = line.slice(index + value.length).trimStart()
      const kind: TokenKind = keywords.has(value)
        ? 'keyword'
        : after.startsWith('(')
          ? 'function'
          : 'plain'
      pushToken(tokens, kind, value)
      index += value.length
      continue
    }

    pushToken(tokens, 'plain', current)
    index += 1
  }

  return tokens
}

type CodeBlockProps = {
  code: string
  language: string
  sourcePath: string
  sourceLine: string
  uiLanguage: 'zh' | 'en'
}

export default function CodeBlock({
  code,
  language,
  sourcePath,
  sourceLine,
  uiLanguage,
}: CodeBlockProps) {
  const [copied, setCopied] = useState(false)
  const lines = code.split('\n')

  const copyCode = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1800)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="source-card">
      <div className="source-card-top">
        <div>
          <span className="source-card-kicker">SOURCE / {language.toUpperCase()}</span>
          <code className="source-card-path">{sourcePath}</code>
          <span className="source-card-line">LINES {sourceLine}</span>
        </div>
        <button
          type="button"
          className="source-copy"
          onClick={copyCode}
          aria-label={
            copied
              ? uiLanguage === 'zh'
                ? '代码已复制'
                : 'Code copied'
              : uiLanguage === 'zh'
                ? '复制代码'
                : 'Copy code'
          }
        >
          {copied ? <Check size={14} /> : <Copy size={14} />}
          <span>{copied ? (uiLanguage === 'zh' ? '已复制' : 'COPIED') : uiLanguage === 'zh' ? '复制' : 'COPY'}</span>
        </button>
      </div>
      <pre className="source-code" tabIndex={0} aria-label={`${language} source code`}>
        <code>
          {lines.map((line, lineIndex) => (
            <span className="source-line" key={`${lineIndex}-${line}`}>
              <span className="source-line-number" aria-hidden="true">
                {String(lineIndex + Number(sourceLine.split(/[–-]/)[0])).padStart(2, '0')}
              </span>
              <span className="source-line-content">
                {tokenizeLine(line).map((token, tokenIndex) => (
                  <span className={`code-token code-token-${token.kind}`} key={`${tokenIndex}-${token.value}`}>
                    {token.value}
                  </span>
                ))}
                {'\n'}
              </span>
            </span>
          ))}
        </code>
      </pre>
    </div>
  )
}
