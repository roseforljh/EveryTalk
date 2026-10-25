/* eslint-disable @next/next/no-img-element */
'use client'

import { useEffect, useRef, useState } from 'react'
import { filePresentation } from './file-preview'

/** 所有媒体只用本地 Blob URL；文本（包括 HTML）由 React 转义，关闭时释放 URL 和播放器。 */
export function MessageFilePreview({ file, onClose }: { file: File; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [source, setSource] = useState(''), [text, setText] = useState('')
  const [error, setError] = useState('')
  const { preview } = filePresentation(file.name, file.type)
  useEffect(() => {
    dialog.current?.showModal()
    // 强制 PDF MIME，交给浏览器原生阅读器；不能让扩展名伪装的 HTML 在本站执行。
    const url = URL.createObjectURL(preview === 'pdf' ? new Blob([file], { type: 'application/pdf' }) : file)
    setSource(url)
    setText(''); setError('')
    let active = true
    if (preview === 'text') void file.slice(0, 512 * 1024).text()
      .then(value => { if (active) setText(value) })
      .catch(() => { if (active) setError('无法读取本地文件') })
    return () => { active = false; URL.revokeObjectURL(url) }
  }, [file, preview])
  return <dialog ref={dialog} className="et-file-preview" aria-label={`预览：${file.name}`} onCancel={event => { event.preventDefault(); onClose() }}>
    <header><strong>{file.name}</strong><button type="button" onClick={onClose}>关闭预览</button></header>
    <small>本地预览</small>
    {error ? <p role="alert">{error}，请关闭预览，从文件菜单选择其他应用打开。</p>
      : preview && !source ? <p>正在读取本地文件…</p>
      : preview === 'text' ? <>{file.size > 512 * 1024 && <p>仅预览前 512 KiB，完整内容请用其他应用打开。</p>}<pre>{text}</pre></>
      : preview === 'image' ? <img src={source} alt={file.name} onError={() => setError('此图片无法预览')} />
      : preview === 'audio' ? <audio src={source} controls onError={() => setError('此音频格式无法预览')} />
      : preview === 'video' ? <video src={source} controls onError={() => setError('此视频格式无法预览')} />
      : preview === 'pdf' ? <object data={source} type="application/pdf" aria-label={file.name}><p>此浏览器无法预览 PDF，请关闭预览，从文件菜单选择其他应用打开。</p></object>
      : <p>此格式暂不支持网页内预览，请从文件菜单保存或用其他应用打开。</p>}
  </dialog>
}
