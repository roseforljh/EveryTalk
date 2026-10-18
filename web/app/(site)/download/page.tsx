import type { Metadata } from 'next'
import Download from '@/components/sections/Download'
import VersionHistory from '@/components/sections/VersionHistory'

export const metadata: Metadata = { title: '下载 Android 应用 | EveryTalk' }
export default function DownloadPage() {
  return (
    <div className="subpage">
      <Download />
      <VersionHistory />
    </div>
  )
}
