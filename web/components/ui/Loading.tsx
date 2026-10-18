'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'
import { motion } from 'framer-motion'

export default function Loading() {
  const { t } = useLanguage()
  return (
    <div className="page-loading" aria-live="polite" aria-busy="true">
      <div className="page-loading-card">
        <span className="page-loading-spinner" aria-hidden="true" />
        <span>{t('加载中...')}</span>
      </div>
    </div>
  )
}

export function PageTransition({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -20 }}
      transition={{ duration: 0.5 }}
    >
      {children}
    </motion.div>
  )
}
