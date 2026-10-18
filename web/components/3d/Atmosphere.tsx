'use client'

import dynamic from 'next/dynamic'
const PixelSnow = dynamic(() => import('./PixelSnow'), { ssr: false })

export default function Atmosphere() {
  return (
    <div className="site-atmosphere" aria-hidden="true">
      <PixelSnow />
    </div>
  )
}
