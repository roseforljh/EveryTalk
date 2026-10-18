import { LocalizedText } from '@/components/ui/LanguageProvider'
import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: '隐私政策 | EveryTalk',
  description: 'EveryTalk 隐私政策，说明应用如何处理设备数据和第三方服务请求。',
}

export default function PrivacyPolicyPage() {
  return (
    <article className="legal-page section-padding min-h-screen bg-black pt-32">
      <div className="mx-auto max-w-4xl rounded-3xl border border-dark-border bg-white/[0.04] p-6 shadow-2xl shadow-primary/5 md:p-12">
        <p className="mb-3 text-sm uppercase tracking-[0.25em] text-accent">
          EveryTalk Legal
        </p>
        <h1 className="mb-4 font-poppins text-4xl font-bold text-white md:text-5xl">
          <LocalizedText>隐私政策</LocalizedText>
        </h1>
        <p className="mb-10 text-gray-400">
          <LocalizedText>生效日期：2026 年 10 月 11 日</LocalizedText>
        </p>

        <div className="space-y-10 leading-8 text-gray-300">
          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>概述</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                EveryTalk 是一款由 EveryTalk 项目维护者维护的 Android AI
                客户端。本政策说明你使用聊天、图像、语音、联网搜索、模型上下文协议（MCP）和
                AI 内容举报功能时，EveryTalk 如何处理信息。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>设备本地保存的信息</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                EveryTalk 会在应用私有存储中保存聊天记录、模型服务配置、API
                密钥、分组、置顶状态、生成内容引用和其他应用设置。应用不会将这些私有数据加入
                Android 系统云备份或设备间迁移。你主动导出或保存的文件由 Android
                或你选择的存储服务管理。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>网络服务处理的信息</LocalizedText>
            </h2>
            <p className="mb-4">
              <LocalizedText>
                EveryTalk 本身不提供 AI
                模型。你主动使用联网功能时，应用只向你选择或配置的服务发送完成请求所需的信息：
              </LocalizedText>
            </p>
            <div className="overflow-x-auto rounded-2xl border border-dark-border">
              <table className="w-full min-w-[680px] text-left text-sm">
                <thead className="bg-white/[0.06] text-white">
                  <tr>
                    <th className="p-4">
                      <LocalizedText>功能</LocalizedText>
                    </th>
                    <th className="p-4">
                      <LocalizedText>可能传输的信息</LocalizedText>
                    </th>
                    <th className="p-4">
                      <LocalizedText>接收方与用途</LocalizedText>
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-dark-border">
                  <tr>
                    <td className="p-4">
                      <LocalizedText>AI 聊天</LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>
                        提示词、相关会话上下文、系统指令和附件
                      </LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>
                        你选择的 AI 服务商，用于生成回复
                      </LocalizedText>
                    </td>
                  </tr>
                  <tr>
                    <td className="p-4">
                      <LocalizedText>图像与语音</LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>
                        提示词、图片、录音和语音处理所需文本
                      </LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>
                        你选择的图像、语音或 AI 服务商
                      </LocalizedText>
                    </td>
                  </tr>
                  <tr>
                    <td className="p-4">
                      <LocalizedText>搜索与网页读取</LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>搜索词和你请求读取的网址</LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>已配置的搜索或网页读取服务</LocalizedText>
                    </td>
                  </tr>
                  <tr>
                    <td className="p-4">
                      <LocalizedText>MCP 工具</LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>
                        工具参数、相关上下文和工具结果
                      </LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>
                        你配置的 MCP 服务器，用于执行请求
                      </LocalizedText>
                    </td>
                  </tr>
                  <tr>
                    <td className="p-4">
                      <LocalizedText>AI 内容举报</LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>
                        举报类别、补充说明、相关回复片段、图片数量、模型和版本信息
                      </LocalizedText>
                    </td>
                    <td className="p-4">
                      <LocalizedText>
                        配置的 EveryTalk 举报接口，用于审核
                      </LocalizedText>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p className="mt-4">
              <LocalizedText>
                这些服务依据各自的条款和隐私政策处理信息。请勿连接你不信任的服务，也不要向其提交敏感信息。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>权限用途</LocalizedText>
            </h2>
            <ul className="list-disc space-y-2 pl-6">
              <li>
                <LocalizedText>
                  网络：连接 AI、语音、图像、搜索、MCP 和举报服务。
                </LocalizedText>
              </li>
              <li>
                <LocalizedText>
                  麦克风：仅在你启动语音功能后采集音频。
                </LocalizedText>
              </li>
              <li>
                <LocalizedText>
                  相机：仅在你选择相机功能后拍摄图片。
                </LocalizedText>
              </li>
              <li>
                <LocalizedText>蓝牙：支持兼容的已配对音频设备。</LocalizedText>
              </li>
            </ul>
            <p className="mt-4">
              <LocalizedText>
                可选权限会在使用相关功能时请求。拒绝可选权限不会影响无关功能。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>收集、共享、保存和删除</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                EveryTalk 不包含广告或分析统计
                SDK，也不出售个人信息。信息只会在你主动使用相关功能时离开设备。设备本地数据会保留到你删除相应内容、清除应用数据或卸载应用；导出的文件保留到你主动删除。第三方服务可能依据自己的政策独立处理信息。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>账号登录</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                EveryTalk 通过配置的 Supabase Auth 服务提供可选的 Google
                和邮箱验证码登录。Google
                登录只申请基本身份信息（openid、email、profile），不申请 Gmail
                或 Google Drive 数据。Supabase 会处理账号
                ID、邮箱、关联登录身份以及注册和登录记录。登录不会上传聊天记录或模型
                API 密钥。应用使用 Android Keystore
                保存会话凭据，退出登录会清除本地会话。
              </LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>Google 用户数据与 Gmail MCP</LocalizedText>
            </h2>
            <p className="mb-4">
              <LocalizedText>Google 账号登录与 Gmail 连接是两个独立功能。账号登录仅使用 openid、email 和 profile 基本身份信息；只有你单独连接 Gmail 并授予 gmail.modify 权限后，Gmail MCP 才能访问邮件。当前功能包括搜索和读取邮件、列出标签、给邮件添加或移除标签，以及按你的要求发送纯文本邮件；不提供创建草稿或永久删除邮件的工具。</LocalizedText>
            </p>
            <p className="mb-4">
              <LocalizedText>为完成这些功能，应用可能处理邮箱地址、邮件和会话标识符、发件人与收件人、主题、正文、邮件时间、标签、搜索条件，以及你要求发送的内容。Google 身份信息仅用于账号认证和展示；Gmail 数据仅用于你可见的邮件操作及相关回答，不用于与这些功能无关的目的。</LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>处理路径与第三方 AI 服务</LocalizedText>
            </h2>
            <p className="mb-4">
              <LocalizedText>Gmail 请求通过部署在 Cloudflare 的 EveryTalk OAuth/MCP Worker 与 Google Gmail API 通信。Worker 使用授权凭据换取或刷新令牌，并在请求期间处理邮件操作；其应用代码不将令牌、邮件正文或用户资料写入持久存储。Google 访问令牌和刷新令牌在 Android 端加密保存，不作为模型提示词发送。</LocalizedText>
            </p>
            <p className="mb-4">
              <LocalizedText>当你使用 AI 执行 Gmail 任务时，工具返回的邮件信息可能作为会话上下文发送到当前选择的模型服务，用于理解请求、展示或总结邮件及生成相关回答。若模型经代理或聚合平台提供，网关运营方和上游模型服务商也可能处理这些内容。因此，使用 AI 邮件功能并不意味着邮件始终只在设备本地处理。</LocalizedText>
            </p>
            <p className="mb-4">
              <LocalizedText>Google 数据只能在用户同意的范围内，为提供上述可见功能而传输给必要的处理方。接收 Google 数据的 AI 服务及其上游必须遵守同样的受限使用要求，不得将这些数据用于通用模型训练。EveryTalk 支持用户自定义接口，但当前不会自动核验每个接口、套餐和上游服务的训练或保存政策；未经核实符合这些要求的服务不得接收 Gmail 数据，用户同意也不能豁免禁止训练的限制。</LocalizedText>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>受限使用承诺与禁止用途</LocalizedText>
            </h2>
            <p className="mb-4">
              <LocalizedText>EveryTalk 对通过 Google API 获得的信息（包括 Gmail 原始数据及其汇总、匿名化或派生数据）的使用和向其他应用的传输，将遵守 Google API Services User Data Policy 和 Google Workspace API User Data and Developer Policy，包括 Limited Use（受限使用）要求。本节优先于本政策中一般性的第三方服务说明；第三方政策不能扩大 Google 数据的允许用途。</LocalizedText>
            </p>
            <p className="mb-4">
              <LocalizedText>EveryTalk 不使用、出售或传输 Google 用户数据来创建、训练或改进基础模型或通用 AI/机器学习模型，不将其用于广告投放、再营销、数据经纪、信用评估或借贷决策。不得通过汇总、匿名化、派生摘要或用户授权规避这些限制。</LocalizedText>
            </p>
            <p className="mb-4">
              <LocalizedText>维护者不得人工阅读 Google 用户数据，除非获得你对具体数据的明确同意、为必要的安全调查或遵守法律，或在 Google 政策允许的范围内使用汇总匿名数据进行内部运营。需要法律或安全目的的数据处理仅限必要范围。主动举报或寻求支持时，请仅提供你同意审核的内容，不要在公开 Issue 中上传真实邮件或凭据。</LocalizedText>
            </p>
            <p lang="en" className="mb-4">EveryTalk's use and transfer to any other app of information received from Google APIs will adhere to the Google API Services User Data Policy, including the Limited Use requirements.</p>
            <p className="mb-4">
              <a className="text-accent underline" href="https://developers.google.com/terms/api-services-user-data-policy">Google API Services User Data Policy</a>
              {' · '}
              <a className="text-accent underline" href="https://developers.google.com/workspace/workspace-api-user-data-developer-policy">Google Workspace API User Data and Developer Policy</a>
            </p>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>Google 数据的保存、删除与撤销授权</LocalizedText>
            </h2>
            <p className="mb-4">
              <LocalizedText>Gmail 工具结果及相关回答可能保存在设备的聊天记录、Agent 执行记录或摘要中，并可能随相关会话再次提供给当前模型。可删除相关会话与关联记录；清除应用数据或卸载会移除应用私有存储中的数据。导出文件和另行保存的副本需要在各自位置删除。第三方已经收到的数据的保存期限和删除方式取决于实际服务及套餐，必须符合上述 Google 数据限制；EveryTalk 不能仅通过删除本地记录来删除第三方副本。</LocalizedText>
            </p>
            <p className="mb-4">
              <LocalizedText>在应用中退出 Gmail 连接会删除本地 Gmail 授权令牌，不会自动删除此前的聊天记录，也不会删除 Google 邮箱中的邮件。还可在 Google 账号的第三方连接设置中撤销 EveryTalk 的访问权限，以阻止后续访问。普通 EveryTalk 账号退出与 Gmail 断开连接是独立操作。Google 数据隐私或删除请求可通过项目页面联系维护者，请先获取私密联系渠道，不要公开提交邮件正文、密码、验证码或令牌。</LocalizedText>
            </p>
            <a className="text-accent underline" href="https://myaccount.google.com/connections">
              <LocalizedText>管理 Google 第三方连接</LocalizedText>
            </a>
          </section>

          <section>
            <h2 className="mb-3 text-2xl font-semibold text-white">
              <LocalizedText>安全、更新与联系</LocalizedText>
            </h2>
            <p>
              <LocalizedText>
                EveryTalk 的生产连接使用
                HTTPS，并将数据库和配置保存在应用私有存储中。网络安全也取决于你使用的服务商、接口、网络和设备，请妥善保管
                API
                密钥。政策可能随功能或法律要求更新，页面顶部的生效日期表示当前版本。
              </LocalizedText>
            </p>
            <p className="mt-4">
              <LocalizedText>隐私问题或账号删除请求可通过 </LocalizedText>
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
