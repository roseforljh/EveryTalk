'use client'

import { motion, type HTMLMotionProps } from 'framer-motion'
import { cn } from '@/lib/utils'
import { buttonHover } from '@/lib/animations'

// 使用动画按钮的官方属性类型，避免原生 DOM 动画事件与 Motion 回调冲突。
interface ButtonProps extends HTMLMotionProps<'button'> {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost'
  size?: 'sm' | 'md' | 'lg'
  children: React.ReactNode
  className?: string
}

export default function Button({
  variant = 'primary',
  size = 'md',
  children,
  className,
  ...props
}: ButtonProps) {
  const baseStyles = 'relative font-semibold rounded-lg transition-all duration-300 overflow-hidden group'
  
  const variants = {
    primary: 'bg-primary hover:bg-primary-light text-black font-bold shadow-lg shadow-primary/50',
    secondary: 'bg-secondary hover:bg-secondary-light text-black font-semibold shadow-lg shadow-secondary/50',
    outline: 'border-2 border-primary text-primary hover:bg-primary/20 hover:text-primary-light',
    ghost: 'text-accent hover:bg-primary/10',
  }
  
  const sizes = {
    sm: 'px-4 py-2 text-sm',
    md: 'px-6 py-3 text-base',
    lg: 'px-8 py-4 text-lg',
  }

  return (
    <motion.button
      className={cn(baseStyles, variants[variant], sizes[size], className)}
      whileHover={buttonHover}
      whileTap={{ scale: 0.95 }}
      {...props}
    >
      {/* 悬停时的光效 */}
      <span className="absolute inset-0 bg-linear-to-r from-transparent via-white/20 to-transparent translate-x-[-200%] group-hover:translate-x-[200%] transition-transform duration-700" />
      
      {/* 边框发光效果 - 白色模糊 */}
      {variant === 'primary' && (
        <span className="absolute inset-0 rounded-lg opacity-0 group-hover:opacity-50 transition-opacity duration-300 blur-xl bg-white" />
      )}
      
      <span className="relative z-10">{children}</span>
    </motion.button>
  )
}

