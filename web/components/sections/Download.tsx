'use client'

import { useLanguage } from '@/components/ui/LanguageProvider'

import { ArrowUpRight, Download as DownloadIcon } from 'lucide-react'
import ScrollReveal from '@/components/ui/ScrollReveal'
import ElasticMesh from '@/components/3d/ElasticMesh'

export default function Download() {
  const { t } = useLanguage()
  return (
    <section
      id="download"
      className="download-section section-space site-container"
    >
      <ElasticMesh className="download-mesh" />
      <div className="download-heading">
        <p className="eyebrow">{t('下一次对话，现在开始')}</p>
        <ScrollReveal>{t('开放的 AI，随身带走。')}</ScrollReveal>
        <p>{t('下载 EveryTalk，连接自己的模型服务。')}</p>
        <div className="actions">
          <a
            href="https://github.com/roseforljh/EveryTalk/releases/latest"
            target="_blank"
            rel="noopener noreferrer"
            className="action-primary"
          >
            <DownloadIcon size={18} />
            {t(' 获取 Android 版本')} <ArrowUpRight size={18} />
          </a>
          <a
            href="https://github.com/roseforljh/EveryTalk/releases"
            target="_blank"
            rel="noopener noreferrer"
            className="action-quiet"
          >
            {t('所有版本 ')}
            <ArrowUpRight size={16} />
          </a>
        </div>
        <div className="hero-facts">
          <span>Android 8.1+</span>
          <span>GitHub Releases</span>
          <span>{t('使用自己的 API 配置')}</span>
        </div>
      </div>
      <ol className="installation">
        <li>
          <span>01</span>
          <div>
            <h3>{t('下载与安装')}</h3>
            <p>
              {t('从 GitHub Releases 下载 APK，按照 Android 提示完成安装。')}
            </p>
          </div>
        </li>
        <li>
          <span>02</span>
          <div>
            <h3>{t('连接模型')}</h3>
            <p>{t('填写服务地址、API 密钥与模型。第三方服务可能单独收费。')}</p>
          </div>
        </li>
        <li>
          <span>03</span>
          <div>
            <h3>{t('开启对话')}</h3>
            <p>{t('选择模型，新建会话。聊天、搜索或创作，从这里开始。')}</p>
          </div>
        </li>
      </ol>
    </section>
  )
}
