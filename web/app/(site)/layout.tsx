import Header from '@/components/layout/Header'
import Footer from '@/components/layout/Footer'
import Atmosphere from '@/components/3d/Atmosphere'
import SmoothScroll from '@/components/ui/SmoothScroll'
import { LocalizedText } from '@/components/ui/LanguageProvider'

/** 官网展示层只包围官网路由，不影响登录后工作区的滚动和快捷键。 */
export const dynamic = 'force-dynamic'
export default function SiteLayout({ children }: { children: React.ReactNode }) {
  return <>
    <a href="#main-content" className="skip-link"><LocalizedText>跳到内容</LocalizedText></a>
    <Atmosphere />
    <SmoothScroll />
    <Header />
    <main id="main-content">{children}</main>
    <Footer />
  </>
}
