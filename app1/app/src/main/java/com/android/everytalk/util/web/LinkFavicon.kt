package com.android.everytalk.util.web

import androidx.annotation.DrawableRes
import com.android.everytalk.R
import java.net.URI
import java.util.Locale

/** 本地图标及其原始配色标记；单色资源由界面按主题着色。 */
data class LinkIcon(@param:DrawableRes val resource: Int, val multicolor: Boolean = false)

private val globeIcon = LinkIcon(R.drawable.ic_globe)

// 同一品牌的别名共用资源；查找时从完整域名逐级向母域名匹配，不依赖声明顺序。
private val websiteIcons = listOf(
    listOf("calendar.google.com") to LinkIcon(R.drawable.ic_website_google_calendar, true),
    listOf("docs.google.com", "drive.google.com", "sheets.google.com", "slides.google.com") to LinkIcon(R.drawable.ic_website_google_drive, true),
    listOf("mail.google.com") to LinkIcon(R.drawable.ic_website_gmail, true),
    listOf("google.com", "google.cn", "google.com.hk", "google.co.jp", "google.co.uk", "google.de", "google.fr") to LinkIcon(R.drawable.ic_website_google, true),
    listOf("github.com", "github.io") to LinkIcon(R.drawable.ic_website_github),
    listOf("gitlab.com") to LinkIcon(R.drawable.ic_website_gitlab),
    listOf("stackoverflow.com") to LinkIcon(R.drawable.ic_website_stackoverflow),
    listOf("wikipedia.org") to LinkIcon(R.drawable.ic_website_wikipedia),
    listOf("x.com", "twitter.com", "t.co") to LinkIcon(R.drawable.ic_website_x),
    listOf("youtube.com", "youtu.be") to LinkIcon(R.drawable.ic_website_youtube),
    listOf("reddit.com", "redd.it") to LinkIcon(R.drawable.ic_website_reddit),
    listOf("discord.com", "discord.gg", "discordapp.com") to LinkIcon(R.drawable.ic_website_discord),
    listOf("bilibili.com", "b23.tv") to LinkIcon(R.drawable.ic_website_bilibili, true),
    listOf("facebook.com", "fb.com", "fb.watch") to LinkIcon(R.drawable.ic_website_facebook),
    listOf("instagram.com") to LinkIcon(R.drawable.ic_website_instagram),
    listOf("linkedin.com", "lnkd.in") to LinkIcon(R.drawable.ic_website_linkedin),
    listOf("tiktok.com", "douyin.com", "iesdouyin.com") to LinkIcon(R.drawable.ic_website_tiktok),
    listOf("chatgpt.com", "openai.com") to LinkIcon(R.drawable.ic_website_openai),
    listOf("claude.ai", "anthropic.com") to LinkIcon(R.drawable.ic_website_claude, true),
    listOf("deepseek.com") to LinkIcon(R.drawable.ic_website_deepseek, true),
    listOf("huggingface.co") to LinkIcon(R.drawable.ic_website_huggingface, true),
    listOf("cloudflare.com") to LinkIcon(R.drawable.ic_website_cloudflare, true),
    listOf("vercel.com", "vercel.app") to LinkIcon(R.drawable.ic_website_vercel),
    listOf("figma.com") to LinkIcon(R.drawable.ic_website_figma, true),
    listOf("linear.app") to LinkIcon(R.drawable.ic_website_linear),
    listOf("notion.so", "notion.site", "notion.com") to LinkIcon(R.drawable.ic_website_notion),
    listOf("slack.com") to LinkIcon(R.drawable.ic_website_slack, true),
    listOf("salesforce.com", "force.com") to LinkIcon(R.drawable.ic_website_salesforce, true),
    listOf("box.com") to LinkIcon(R.drawable.ic_website_box, true),
    listOf("dropbox.com") to LinkIcon(R.drawable.ic_website_dropbox, true),
    listOf("midjourney.com") to LinkIcon(R.drawable.ic_website_midjourney),
    listOf("firefly.adobe.com") to LinkIcon(R.drawable.ic_website_adobefirefly, true),
    listOf("stability.ai") to LinkIcon(R.drawable.ic_website_stability, true),
    listOf("civitai.com") to LinkIcon(R.drawable.ic_website_civitai, true),
    listOf("ideogram.ai") to LinkIcon(R.drawable.ic_website_ideogram),
    listOf("recraft.ai") to LinkIcon(R.drawable.ic_website_recraft),
    listOf("runwayml.com") to LinkIcon(R.drawable.ic_website_runway),
    listOf("klingai.com", "kling.kuaishou.com") to LinkIcon(R.drawable.ic_website_kling, true),
    listOf("jimeng.jianying.com", "dreamina.capcut.com") to LinkIcon(R.drawable.ic_website_jimeng, true),
    listOf("lumalabs.ai") to LinkIcon(R.drawable.ic_website_luma, true),
    listOf("bfl.ai", "bfl.ml") to LinkIcon(R.drawable.ic_website_flux),
    listOf("gemini.google.com") to LinkIcon(R.drawable.ic_website_gemini, true),
    listOf("perplexity.ai") to LinkIcon(R.drawable.ic_website_perplexity, true),
    listOf("grok.com", "x.ai") to LinkIcon(R.drawable.ic_website_grok),
    listOf("qwen.ai", "qianwen.com", "tongyi.aliyun.com") to LinkIcon(R.drawable.ic_website_qwen, true),
    listOf("doubao.com") to LinkIcon(R.drawable.ic_website_doubao, true),
    listOf("kimi.com", "kimi.moonshot.cn", "moonshot.ai", "moonshot.cn") to LinkIcon(R.drawable.ic_website_kimi, true),
    listOf("modelscope.cn", "modelscope.ai") to LinkIcon(R.drawable.ic_website_modelscope, true),
    listOf("ollama.com") to LinkIcon(R.drawable.ic_website_ollama),
    listOf("microsoft.com") to LinkIcon(R.drawable.ic_website_microsoft, true),
    listOf("apple.com") to LinkIcon(R.drawable.ic_website_apple),
    listOf("baidu.com", "baike.baidu.com") to LinkIcon(R.drawable.ic_website_baidu, true),
    listOf("bing.com") to LinkIcon(R.drawable.ic_website_bing, true),
    listOf("aws.amazon.com") to LinkIcon(R.drawable.ic_website_aws, true),
    listOf("azure.com", "azure.microsoft.com") to LinkIcon(R.drawable.ic_website_azure, true),
    listOf("minimax.io", "minimaxi.com") to LinkIcon(R.drawable.ic_website_minimax, true),
    listOf("zhihu.com") to LinkIcon(R.drawable.ic_website_zhihu),
    listOf("weibo.com", "weibo.cn") to LinkIcon(R.drawable.ic_website_weibo),
    listOf("weixin.qq.com", "mp.weixin.qq.com", "wechat.com") to LinkIcon(R.drawable.ic_website_wechat),
    listOf("npmjs.com", "npmjs.org") to LinkIcon(R.drawable.ic_website_npm),
    listOf("docker.com") to LinkIcon(R.drawable.ic_website_docker),
    listOf("python.org") to LinkIcon(R.drawable.ic_website_python),
    listOf("react.dev", "reactjs.org") to LinkIcon(R.drawable.ic_website_react),
    listOf("nodejs.org") to LinkIcon(R.drawable.ic_website_nodejs),
    listOf("typescriptlang.org") to LinkIcon(R.drawable.ic_website_typescript),
    listOf("supabase.com") to LinkIcon(R.drawable.ic_website_supabase),
    listOf("firebase.google.com") to LinkIcon(R.drawable.ic_website_firebase),
    listOf("pinterest.com", "pin.it") to LinkIcon(R.drawable.ic_website_pinterest),
    listOf("telegram.org", "t.me") to LinkIcon(R.drawable.ic_website_telegram),
    listOf("medium.com") to LinkIcon(R.drawable.ic_website_medium),
).flatMap { (domains, icon) -> domains.map { domain -> domain to icon } }.toMap()

/**
 * 返回外部网页链接的规范化 host；内部 scheme 和无效地址返回空字符串。
 */
fun linkHost(href: String): String = runCatching {
    val uri = URI(href.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return@runCatching ""
    uri.host
        ?.lowercase(Locale.ROOT)
        ?.removePrefix("www.")
        ?.trimEnd('.')
        .orEmpty()
}.getOrDefault("")

/**
 * 根据网页地址同步选择内置图标，未知或无效地址统一返回地球图标，不请求网络。
 * 只匹配完整域名或点分隔的子域名，避免把 github.com.example.org 误认成 GitHub。
 */
fun linkIcon(href: String): LinkIcon {
    var host = linkHost(href)
    // 逐级去掉最左侧标签，既优先匹配产品子域名，也不会命中伪装的域名后缀。
    while (host.isNotEmpty()) {
        websiteIcons[host]?.let { return it }
        host = host.substringAfter('.', missingDelimiterValue = "")
    }
    return globeIcon
}
