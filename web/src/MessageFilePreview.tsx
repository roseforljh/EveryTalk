/* eslint-disable @next/next/no-img-element */
import { useEffect, useRef, useState } from 'react'
import { Download, FileText, X } from 'lucide-react'
import { filePresentation } from './file-preview'

/**
 * 本地文件预览弹窗（对齐安卓端 MessageFilePreview.kt：28px 圆角容器、文件图标与标题栏、本地下载与关闭胶囊按钮）。
 * 浏览器端只展示安全媒介或纯文本源码，避免执行未知脚本。
 */
export function MessageFilePreview({ file, onClose }: { file: File; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [url, setUrl] = useState('')
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const presentation = filePresentation(file.name, file.type)

  useEffect(() => {
    const node = dialog.current
    if (node && !node.open && typeof node.showModal === 'function') node.showModal()
  }, [])

  useEffect(() => {
    let cancelled = false
    let objectUrl = ''
    setUrl('')
    setText('')
    setError('')
    if (presentation.preview === 'text') {
      void file
        .slice(0, 262_144)
        .text()
        .then(value => {
          if (!cancelled) setText(value)
        })
        .catch(value => {
          if (!cancelled) setError(value instanceof Error ? value.message : '预览失败')
        })
    } else if (presentation.preview) {
      objectUrl = URL.createObjectURL(file)
      setUrl(objectUrl)
    }
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [file, presentation.preview])

  const handleDownloadLocal = () => {
    const downloadUrl = URL.createObjectURL(file)
    const a = document.createElement('a')
    a.href = downloadUrl
    a.download = file.name || 'file'
    a.click()
    window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 5000)
  }

  return (
    <dialog
      ref={dialog}
      className="et-file-preview"
      aria-label={`预览 ${file.name}`}
      onClose={onClose}
      onClick={event => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <header>
        <div className="et-api-card-title">
          <span className="et-appinfo-icon" style={{ color: presentation.color }}>
            <FileText size={18} />
          </span>
          <div>
            <strong>{file.name}</strong>
            <div className="et-muted" style={{ fontSize: 12 }}>
              本地预览 · {Math.max(1, Math.round(file.size / 1024))} KB
            </div>
          </div>
        </div>
        <div className="et-api-card-actions">
          <button type="button" className="et-filter-chip" onClick={handleDownloadLocal}>
            <Download size={14} />
            <span>另存为</span>
          </button>
          <button type="button" className="et-msg-circle-btn" aria-label="关闭预览" onClick={onClose}>
            <X size={18} />
          </button>
        </div>
      </header>
      {error && (
        <p className="et-error" role="alert">
          {error}
        </p>
      )}
      {presentation.preview === 'image' && url && <img src={url} alt={file.name} />}
      {presentation.preview === 'pdf' && url && <object data={url} type="application/pdf" aria-label={file.name} />}
      {presentation.preview === 'audio' && url && <audio controls src={url} />}
      {presentation.preview === 'video' && url && <video controls src={url} />}
      {presentation.preview === 'text' && <pre>{text}</pre>}
      {!presentation.preview && <p className="et-muted">此文件格式不支持浏览器内直接预览，可点击右上角「另存为」后使用本地应用打开。</p>}
    </dialog>
  )
}
