'use client'

import { motion } from 'framer-motion'
import { textReveal } from '@/lib/animations'

interface AnimatedTextProps {
  text: string
  className?: string
  delay?: number
  staggerChildren?: boolean
}

export default function AnimatedText({ 
  text, 
  className = '', 
  delay = 0,
  staggerChildren = false 
}: AnimatedTextProps) {
  if (staggerChildren) {
    const words = text.split(' ')
    
    return (
      <motion.div
        className={className}
        initial="initial"
        animate="animate"
        variants={{
          animate: {
            transition: {
              staggerChildren: 0.05,
              delayChildren: delay,
            },
          },
        }}
      >
        {words.map((word, index) => (
          <motion.span
            key={index}
            className="inline-block mr-2"
            variants={textReveal}
          >
            {word}
          </motion.span>
        ))}
      </motion.div>
    )
  }

  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.8, delay }}
    >
      {text}
    </motion.div>
  )
}

export function TypingText({ text, className = '' }: { text: string; className?: string }) {
  const characters = text.split('')
  
  return (
    <motion.div
      className={className}
      initial="initial"
      animate="animate"
      variants={{
        animate: {
          transition: {
            staggerChildren: 0.03,
          },
        },
      }}
    >
      {characters.map((char, index) => (
        <motion.span
          key={index}
          variants={{
            initial: { opacity: 0 },
            animate: { opacity: 1 },
          }}
        >
          {char}
        </motion.span>
      ))}
      <motion.span
        className="typing-cursor inline-block w-0.5 h-6 bg-primary ml-1"
        animate={{ opacity: [1, 0] }}
        transition={{ duration: 0.8, repeat: Infinity }}
      />
    </motion.div>
  )
}

