import type { Metadata } from 'next'
import Features from '@/components/sections/Features'
import Showcase from '@/components/sections/Showcase'
import Download from '@/components/sections/Download'

export const metadata: Metadata = { title: '功能与体验 | EveryTalk' }
export default function FeaturesPage() {
  return (
    <div className="subpage">
      <Features />
      <Showcase />
      <Download />
    </div>
  )
}
