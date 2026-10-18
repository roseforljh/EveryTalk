'use client'

import { motion } from 'framer-motion'
import { cn } from '@/lib/utils'
import { cardHover } from '@/lib/animations'

interface CardProps {
  children: React.ReactNode
  className?: string
  glowing?: boolean
  hoverable?: boolean
}

export default function Card({ 
  children, 
  className, 
  glowing = false,
  hoverable = true 
}: CardProps) {
  return (
    <motion.div
      className={cn(
        'relative glass rounded-2xl p-6 transition-all duration-300',
        glowing && 'shadow-lg shadow-primary/20',
        hoverable && 'cursor-pointer',
        className
      )}
      whileHover={hoverable ? cardHover : undefined}
      initial={{ opacity: 0, y: 20 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true }}
      transition={{ duration: 0.5 }}
    >
      {/* 发光边框 - 白色模糊 */}
      {glowing && (
        <div className="absolute inset-0 rounded-2xl bg-white opacity-0 group-hover:opacity-20 transition-opacity duration-300 blur-xl -z-10" />
      )}
      
      {/* 悬停时的光效 */}
      {hoverable && (
        <div className="absolute inset-0 rounded-2xl opacity-0 hover:opacity-100 transition-opacity duration-500 pointer-events-none">
          <div className="absolute inset-0 bg-linear-to-br from-primary/10 via-transparent to-secondary/10" />
        </div>
      )}
      
      <div className="relative z-10">{children}</div>
    </motion.div>
  )
}

