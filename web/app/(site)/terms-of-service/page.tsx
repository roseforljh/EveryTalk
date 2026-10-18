import { LocalizedText } from '@/components/ui/LanguageProvider'
import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: '服务条款 | EveryTalk',
  description: 'EveryTalk 服务条款，说明应用使用规则、第三方服务和用户责任。',
}

export default function TermsOfServicePage() {
  return (
    <article className="legal-page section-padding min-h-screen bg-black pt-32">
      <div className="mx-auto max-w-4xl rounded-3xl border border-dark-border bg-white/[0.04] p-6 shadow-2xl shadow-primary/5 md:p-12">
        <p className="mb-3 text-sm uppercase tracking-[0.25em] text-accent">
          EveryTalk Legal
        </p>
        <h1 className="mb-4 font-poppins text-4xl font-bold text-white md:text-5xl">
          <LocalizedText>服务条款</LocalizedText>
        </h1>
        <p className="mb-10 text-gray-400">
          <LocalizedText>生效日期：2026 年 10 月 9 日</LocalizedText>
        </p>

        <div className="space-y-10 leading-8 text-gray-300">
          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>1. 服务说明</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                EveryTalk 是一款 Android 客户端，帮助你连接自己选择或配置的 AI
                模型、语音、图像、联网搜索和 MCP 服务。EveryTalk
                不运营这些第三方服务，也不保证它们的可用性、准确性、安全性或服务条款。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>2. 你的责任</LocalizedText>
            </h2>
            <ul className="list-disc space-y-2 pl-6">
              <li>
                <LocalizedText>
                  仅将 EveryTalk 用于合法用途，并遵守适用的法律法规。
                </LocalizedText>
              </li>
              <li>
                <LocalizedText>
                  只处理你有权处理的信息、文件、消息和音频。
                </LocalizedText>
              </li>
              <li>
                <LocalizedText>
                  保护 API 密钥、密码、访问令牌、刷新令牌和设备访问权限。
                </LocalizedText>
              </li>
              <li>
                <LocalizedText>
                  在依赖模型输出或工具结果前自行核实重要信息。
                </LocalizedText>
              </li>
              <li>
                <LocalizedText>
                  处理他人的消息、文件、音频或其他信息前取得必要同意。
                </LocalizedText>
              </li>
            </ul>
            <p className="mt-4">
              <LocalizedText>
                你对通过自己的设备、账号、API 密钥和已连接服务进行的活动负责。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>3. AI 输出和第三方服务</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                AI 输出可能不准确、不完整、有偏差、不可用，或不适合特定用途。AI
                输出不是专业、法律、医疗、金融或其他受监管领域的建议。重要信息和决定必须由你独立核实。你使用第三方服务时，还必须遵守该服务自己的条款和隐私政策；服务商可能收取费用或施加使用限制。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>4. MCP 和已连接账号</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                MCP
                工具可以按照你授予的范围和权限访问或修改已连接服务中的数据。Gmail
                MCP
                功能可能搜索和读取邮件、列出标签、添加或移除标签以及发送纯文本邮件。使用前请确认服务器、请求的权限、收件人和内容。不要连接不信任的
                MCP
                服务器，也不要在没有授权的情况下访问、修改、导出或发送他人的数据。你可以在相应服务的账号设置中撤销访问权限。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>5. 内容和知识产权</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                你应当拥有或获得提交到 EveryTalk
                及第三方服务的内容所需的权利。你保留自己内容的权利，但授予
                EveryTalk
                在提供你主动请求的功能所必需范围内处理这些内容的权限。EveryTalk
                的软件和品牌权益归相应权利人所有，并按照项目发布的开源许可使用。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>6. 可用性和责任限制</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                EveryTalk
                按现状提供。我们不保证应用或第三方服务持续可用、无错误、无中断或适合你的特定需求。法律允许的最大范围内，维护者不对因第三方服务、网络、设备、账号配置或依赖
                AI 输出而产生的间接损失负责。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>7. 终止和条款更新</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                你可以随时停止使用 EveryTalk
                并卸载应用。若你违反本条款或滥用服务，维护者可以停止提供相关功能。我们可能因功能变化或法律要求更新本条款，页面顶部的生效日期表示当前版本。继续使用应用即表示你接受更新后的条款。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>8. 联系方式</LocalizedText>
            </h2>
            <p>
              <LocalizedText>如果你对本条款有疑问，请通过 </LocalizedText>
              <a
                className="text-accent underline"
                href="https://github.com/roseforljh/EveryTalk/issues"
              >
                EveryTalk GitHub Issues
              </a>
              <LocalizedText> 联系维护者。</LocalizedText>
            </p>
          </section>
        </div>
      </div>
    </article>
  )
}
