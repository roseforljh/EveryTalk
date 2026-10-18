'use client'

import { motion, AnimatePresence } from 'framer-motion'
import Link from 'next/link'
import { Download } from 'lucide-react'
import Button from '@/components/ui/Button'

interface NavigationProps {
  isOpen: boolean
  onClose: () => void
  navItems: { name: string; href: string }[]
}

export default function Navigation({ isOpen, onClose, navItems }: NavigationProps) {
  return (
    <AnimatePresence>
      {isOpen && (
        <>
          {/* Backdrop */}
          <motion.div
            className="fixed inset-0 bg-black/80 backdrop-blur-[4px] z-40 md:hidden"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
          />

          {/* Menu */}
          <motion.div
            className="fixed top-0 right-0 bottom-0 w-4/5 max-w-sm glass-strong z-50 md:hidden"
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', damping: 25, stiffness: 200 }}
          >
            <div className="flex flex-col h-full p-8">
              {/* Navigation Items */}
              <nav className="flex-1 flex flex-col space-y-6 mt-20">
                {navItems.map((item, index) => (
                  <motion.div
                    key={item.href}
                    initial={{ opacity: 0, x: 20 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: index * 0.1 }}
                  >
                    <Link
                      href={item.href}
                      className="text-2xl font-semibold text-gray-300 hover:text-white transition-colors duration-200 block"
                      onClick={onClose}
                    >
                      {item.name}
                    </Link>
                  </motion.div>
                ))}
              </nav>

              {/* Download Button */}
              <motion.div
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.3 }}
              >
                <Link href="/download" onClick={onClose}>
                  <Button className="w-full gap-2">
                    <Download className="w-5 h-5" />
                    立即下载
                  </Button>
                </Link>
              </motion.div>
            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  )
}

