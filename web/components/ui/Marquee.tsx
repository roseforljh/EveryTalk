import {
  Children,
  cloneElement,
  isValidElement,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from 'react'
import { cn } from '@/lib/utils'

// 改编自 Magic UI Marquee：循环副本与 CSS 匀速平移保留原组件机制。
// 来源：https://github.com/magicuidesign/magicui/blob/main/apps/www/registry/magicui/marquee.tsx
// Copyright (c) Magic UI. MIT 许可全文见 BLACK_WHITE_REDESIGN.md。
interface MarqueeProps extends ComponentPropsWithoutRef<'div'> {
  children: ReactNode
  reverse?: boolean
  pauseOnHover?: boolean
  paused?: boolean
  repeat?: number
}

/** 副本不重复进入读屏和 Tab 顺序；保留鼠标点击，滚到任一副本都能打开项目。 */
export function Marquee({
  children,
  className,
  reverse = false,
  pauseOnHover = false,
  paused = false,
  repeat = 4,
  ...props
}: MarqueeProps) {
  return (
    <div
      {...props}
      className={cn('marquee', className)}
      data-reverse={reverse}
      data-pause-on-hover={pauseOnHover}
      data-paused={paused}
    >
      {Array.from({ length: repeat }, (_, index) => (
        <div
          className="marquee-track"
          key={index}
          aria-hidden={index > 0 ? true : undefined}
        >
          {index === 0
            ? children
            : Children.map(children, (child) =>
                isValidElement<ComponentPropsWithoutRef<'a'>>(child)
                  ? cloneElement(child, { tabIndex: -1 })
                  : child,
              )}
        </div>
      ))}
    </div>
  )
}
