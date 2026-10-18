'use client'

import {
  useEffect,
  useRef,
  useState,
  useId,
  type ReactNode,
  type CSSProperties,
} from 'react'
// 改编自 React Bits GlassSurface，保留 SVG 位移映射和 RGB 合成折射。
// Copyright (c) 2026 David Haz. MIT + Commons Clause 许可见 BLACK_WHITE_REDESIGN.md。
// 来源：https://github.com/DavidHDev/react-bits/blob/main/src/ts-default/Components/GlassSurface/GlassSurface.tsx

export interface GlassSurfaceProps {
  children?: ReactNode
  width?: number | string
  height?: number | string
  borderRadius?: number
  borderWidth?: number
  brightness?: number
  opacity?: number
  blur?: number
  displace?: number
  backgroundOpacity?: number
  saturation?: number
  distortionScale?: number
  redOffset?: number
  greenOffset?: number
  blueOffset?: number
  xChannel?: 'R' | 'G' | 'B'
  yChannel?: 'R' | 'G' | 'B'
  mixBlendMode?:
    | 'normal'
    | 'multiply'
    | 'screen'
    | 'overlay'
    | 'darken'
    | 'lighten'
    | 'color-dodge'
    | 'color-burn'
    | 'hard-light'
    | 'soft-light'
    | 'difference'
    | 'exclusion'
    | 'hue'
    | 'saturation'
    | 'color'
    | 'luminosity'
    | 'plus-darker'
    | 'plus-lighter'
  className?: string
  style?: CSSProperties
  lens?: boolean
}

/** 玻璃只扭曲背景；内容单独叠在上面，不影响文字、按钮和焦点。 */
const GlassSurface = ({
  children,
  width = 200,
  height = 'auto',
  borderRadius = 20,
  borderWidth = 0.07,
  brightness = 50,
  opacity = 0.93,
  blur = 11,
  displace = 0,
  backgroundOpacity = 0,
  saturation = 1,
  distortionScale = -70,
  redOffset = 0,
  greenOffset = 0,
  blueOffset = 0,
  xChannel = 'R',
  yChannel = 'G',
  mixBlendMode = 'difference',
  className = '',
  style = {},
  lens = false,
}: GlassSurfaceProps) => {
  const id = useId().replace(/:/g, '')
  const filterId = `glass-filter-${id}`
  const redGradId = `red-grad-${id}`
  const blueGradId = `blue-grad-${id}`

  const [svgSupported, setSvgSupported] = useState<boolean>(false)

  const containerRef = useRef<HTMLDivElement>(null)
  const feImageRef = useRef<SVGFEImageElement>(null)
  const redChannelRef = useRef<SVGFEDisplacementMapElement>(null)
  const greenChannelRef = useRef<SVGFEDisplacementMapElement>(null)
  const blueChannelRef = useRef<SVGFEDisplacementMapElement>(null)
  const gaussianBlurRef = useRef<SVGFEGaussianBlurElement>(null)

  // 保持官方生成算法；ResizeObserver 使用当前参数，避免放大时仍用首次渲染的闭包。
  const settingsRef = useRef({
    borderRadius,
    borderWidth,
    brightness,
    opacity,
    blur,
    mixBlendMode,
    lens,
  })
  settingsRef.current = {
    borderRadius,
    borderWidth,
    brightness,
    opacity,
    blur,
    mixBlendMode,
    lens,
  }

  const generateDisplacementMap = (): string => {
    const {
      borderRadius,
      borderWidth,
      brightness,
      opacity,
      blur,
      mixBlendMode,
      lens,
    } = settingsRef.current
    const rect = containerRef.current?.getBoundingClientRect()
    const actualWidth = Math.max(1, Math.round(rect?.width || 400))
    const actualHeight = Math.max(1, Math.round(rect?.height || 200))
    const edgeSize = Math.min(actualWidth, actualHeight) * (borderWidth * 0.5)

    if (lens) {
      // 将圆角表面的法线与 Snell 折射方向编码为 R/G 位移；中心保持中性，弯折集中在厚边。
      // 映射限制为 384×128，纵向保留足够采样，避免薄顶栏的厚边出现台阶。
      const map = document.createElement('canvas')
      map.width = Math.min(actualWidth, 384)
      map.height = Math.max(1, Math.min(actualHeight, 128))
      const context = map.getContext('2d')
      if (context) {
        const pixels = context.createImageData(map.width, map.height)
        const radius = Math.min(borderRadius, actualHeight / 2)
        const rim = Math.min(16, actualHeight * 0.2)
        const eta = 1 / 1.15
        for (let y = 0; y < map.height; y++) {
          for (let x = 0; x < map.width; x++) {
            const px = (x + 0.5) * actualWidth / map.width - actualWidth / 2
            const py = (y + 0.5) * actualHeight / map.height - actualHeight / 2
            const qx = Math.abs(px) - actualWidth / 2 + radius
            const qy = Math.abs(py) - actualHeight / 2 + radius
            const ax = Math.max(qx, 0)
            const ay = Math.max(qy, 0)
            const length = Math.hypot(ax, ay)
            const distance = length + Math.min(Math.max(qx, qy), 0) - radius
            let nx = length ? ax / length : qx > qy ? 1 : 0
            let ny = length ? ay / length : qy >= qx ? 1 : 0
            nx *= Math.sign(px)
            ny *= Math.sign(py)
            const slope = distance <= 0 ? Math.max(0, 1 + distance / rim) : 0
            const nz = Math.sqrt(1 - slope * slope)
            const ray = eta * nz - Math.sqrt(1 - eta * eta * slope * slope)
            const bend = Math.min(1, -ray * slope * 2)
            const index = (y * map.width + x) * 4
            pixels.data[index] = Math.round(128 + nx * bend * 127)
            pixels.data[index + 1] = Math.round(128 + ny * bend * 127)
            pixels.data[index + 2] = 128
            pixels.data[index + 3] = 255
          }
        }
        context.putImageData(pixels, 0, 0)
        return map.toDataURL()
      }
    }

    const svgContent = `
      <svg viewBox="0 0 ${actualWidth} ${actualHeight}" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="${redGradId}" x1="100%" y1="0%" x2="0%" y2="0%">
            <stop offset="0%" stop-color="#0000"/>
            <stop offset="100%" stop-color="red"/>
          </linearGradient>
          <linearGradient id="${blueGradId}" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stop-color="#0000"/>
            <stop offset="100%" stop-color="blue"/>
          </linearGradient>
        </defs>
        <rect x="0" y="0" width="${actualWidth}" height="${actualHeight}" fill="black"></rect>
        <rect x="0" y="0" width="${actualWidth}" height="${actualHeight}" rx="${borderRadius}" fill="url(#${redGradId})" />
        <rect x="0" y="0" width="${actualWidth}" height="${actualHeight}" rx="${borderRadius}" fill="url(#${blueGradId})" style="mix-blend-mode: ${mixBlendMode}" />
        <rect x="${edgeSize}" y="${edgeSize}" width="${actualWidth - edgeSize * 2}" height="${actualHeight - edgeSize * 2}" rx="${borderRadius}" fill="hsl(0 0% ${brightness}% / ${opacity})" style="filter:blur(${blur}px)" />
      </svg>
    `

    return `data:image/svg+xml,${encodeURIComponent(svgContent)}`
  }

  const updateDisplacementMap = (): void => {
    feImageRef.current?.setAttribute('href', generateDisplacementMap())
  }

  // 参数改变时更新滤镜属性；RGB 采用相同位移，保持网站黑白配色。
  useEffect(() => {
    updateDisplacementMap()
    ;[
      { ref: redChannelRef, offset: redOffset },
      { ref: greenChannelRef, offset: greenOffset },
      { ref: blueChannelRef, offset: blueOffset },
    ].forEach(({ ref, offset }) => {
      ref.current?.setAttribute('scale', String(distortionScale + offset))
      ref.current?.setAttribute('xChannelSelector', xChannel)
      ref.current?.setAttribute('yChannelSelector', yChannel)
    })
    gaussianBlurRef.current?.setAttribute('stdDeviation', String(displace))
    // updateDisplacementMap 只读取 ref 和稳定的 useId，不依赖旧状态。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    width,
    height,
    borderRadius,
    borderWidth,
    brightness,
    opacity,
    blur,
    displace,
    distortionScale,
    redOffset,
    greenOffset,
    blueOffset,
    xChannel,
    yChannel,
    mixBlendMode,
    lens,
  ])

  useEffect(() => {
    const element = containerRef.current
    if (!element) return
    let frame = 0
    // Dock 弹簧会连续改变尺寸；每帧最多生成一次映射，卸载时取消回调。
    const observer = new ResizeObserver(() => {
      if (!frame)
        frame = requestAnimationFrame(() => {
          frame = 0
          updateDisplacementMap()
        })
    })
    observer.observe(element)
    return () => {
      observer.disconnect()
      cancelAnimationFrame(frame)
    }
    // 映射函数读取 settingsRef，因此监听器不用在每次渲染时重建。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    // 静态镜片折射不依赖鼠标，手机和减少动画模式仍可使用；同位移时下方仅绘制一次。
    setSvgSupported(supportsSVGFilters())
    // filterId 来自 useId，在组件生命周期内保持不变。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const supportsSVGFilters = (): boolean => {
    if (typeof window === 'undefined' || typeof document === 'undefined') {
      return false
    }

    const isWebkit =
      /Safari/.test(navigator.userAgent) && !/Chrome/.test(navigator.userAgent)
    const isFirefox = /Firefox/.test(navigator.userAgent)

    if (isWebkit || isFirefox) {
      return false
    }

    const div = document.createElement('div')
    div.style.backdropFilter = `url(#${filterId})`

    return div.style.backdropFilter !== ''
  }

  const containerStyle: CSSProperties = {
    ...style,
    width: typeof width === 'number' ? `${width}px` : width,
    height: typeof height === 'number' ? `${height}px` : height,
    borderRadius: `${borderRadius}px`,
    '--glass-frost': backgroundOpacity,
    '--glass-saturation': saturation,
    '--filter-id': `url(#${filterId})`,
  } as CSSProperties

  return (
    <div
      ref={containerRef}
      className={`glass-surface ${svgSupported ? 'glass-surface--svg' : 'glass-surface--fallback'} ${className}`}
      style={containerStyle}
    >
      <svg
        className="glass-surface__filter"
        aria-hidden="true"
        focusable="false"
        xmlns="http://www.w3.org/2000/svg"
      >
        <defs>
          <filter
            id={filterId}
            colorInterpolationFilters="sRGB"
            x="0%"
            y="0%"
            width="100%"
            height="100%"
          >
            <feImage
              ref={feImageRef}
              x="0"
              y="0"
              width="100%"
              height="100%"
              preserveAspectRatio="none"
              result="map"
            />

            {redOffset === greenOffset && greenOffset === blueOffset ? (
              <feDisplacementMap ref={redChannelRef} in="SourceGraphic" in2="map" result="output" />
            ) : <>
            <feDisplacementMap
              ref={redChannelRef}
              in="SourceGraphic"
              in2="map"
              result="dispRed"
            />
            <feColorMatrix
              in="dispRed"
              type="matrix"
              values="1 0 0 0 0
                      0 0 0 0 0
                      0 0 0 0 0
                      0 0 0 1 0"
              result="red"
            />

            <feDisplacementMap
              ref={greenChannelRef}
              in="SourceGraphic"
              in2="map"
              result="dispGreen"
            />
            <feColorMatrix
              in="dispGreen"
              type="matrix"
              values="0 0 0 0 0
                      0 1 0 0 0
                      0 0 0 0 0
                      0 0 0 1 0"
              result="green"
            />

            <feDisplacementMap
              ref={blueChannelRef}
              in="SourceGraphic"
              in2="map"
              result="dispBlue"
            />
            <feColorMatrix
              in="dispBlue"
              type="matrix"
              values="0 0 0 0 0
                      0 0 0 0 0
                      0 0 1 0 0
                      0 0 0 1 0"
              result="blue"
            />

            <feBlend in="red" in2="green" mode="screen" result="rg" />
            <feBlend in="rg" in2="blue" mode="screen" result="output" />
            </>}
            <feGaussianBlur
              ref={gaussianBlurRef}
              in="output"
              stdDeviation="0.7"
            />
          </filter>
        </defs>
      </svg>

      <div className="glass-surface__content">{children}</div>
    </div>
  )
}

export default GlassSurface
