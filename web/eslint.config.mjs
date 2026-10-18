import { defineConfig, globalIgnores } from 'eslint/config'
import babelParser from '@babel/eslint-parser'
import nextPlugin from '@next/eslint-plugin-next'
import reactHooks from 'eslint-plugin-react-hooks'

// Next.js 16 已移除 next lint；直接使用 ESLint 的扁平配置检查源码。
export default defineConfig([
  {
    files: ['**/*.{js,mjs,ts,tsx}'],
    // Babel 解析语法，tsc 独立检查类型，避免依赖尚未支持 TypeScript 7 的旧解析器。
    languageOptions: {
      parser: babelParser,
      parserOptions: {
        requireConfigFile: false,
        babelOptions: {
          babelrc: false,
          configFile: false,
          parserOpts: { plugins: ['typescript', 'jsx'] },
        },
      },
    },
    // 直接使用官方 Next.js 插件，避免旧配置包的 React 插件限制 ESLint 10。
    plugins: { '@next/next': nextPlugin, 'react-hooks': reactHooks },
    rules: {
      ...nextPlugin.configs['core-web-vitals'].rules,
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      '@next/next/no-page-custom-font': 'off',
    },
  },
  globalIgnores(['.next/**', 'out/**', 'build/**', 'next-env.d.ts']),
])
