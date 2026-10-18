import type { Config } from 'tailwindcss'

const config: Config = {
  content: [
    './pages/**/*.{js,ts,jsx,tsx,mdx}',
    './components/**/*.{js,ts,jsx,tsx,mdx}',
    './app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        dark: {
          bg: '#000000',
          'bg-secondary': '#0a0a0a',
          'bg-tertiary': '#111111',
          border: '#1a1a1a',
        },
        primary: {
          DEFAULT: '#D4AF37',  // 金色 - 代表阳
          light: '#FFD700',
          dark: '#B8941E',
        },
        secondary: {
          DEFAULT: '#C0C0C0',  // 银灰色 - 代表阴
          light: '#E5E5E5',
          dark: '#808080',
        },
        accent: {
          DEFAULT: '#F5F5DC',  // 米白色 - 和谐
          light: '#FFFAF0',
          dark: '#D3D3D3',
        },
        taiji: {
          gold: '#D4AF37',     // 太极金
          silver: '#C0C0C0',   // 太极银
          beige: '#F5F5DC',    // 太极米白
          gray: '#808080',     // 太极灰
        },
      },
      backgroundImage: {
        'gradient-radial': 'radial-gradient(var(--tw-gradient-stops))',
        'gradient-conic': 'conic-gradient(from 180deg at 50% 50%, var(--tw-gradient-stops))',
        'dark-gradient': 'linear-gradient(180deg, #0a0a0f 0%, #13131a 100%)',
      },
      animation: {
        'pulse-slow': 'pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite',
        'float': 'float 6s ease-in-out infinite',
        'glow': 'glow 2s ease-in-out infinite alternate',
        'shimmer': 'shimmer 2s linear infinite',
      },
      keyframes: {
        float: {
          '0%, 100%': { transform: 'translateY(0px)' },
          '50%': { transform: 'translateY(-20px)' },
        },
        glow: {
          '0%': { boxShadow: '0 0 20px rgba(99, 102, 241, 0.5)' },
          '100%': { boxShadow: '0 0 40px rgba(99, 102, 241, 0.8)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-1000px 0' },
          '100%': { backgroundPosition: '1000px 0' },
        },
      },
      backdropBlur: {
        xs: '2px',
      },
    },
  },
  plugins: [],
}

export default config

