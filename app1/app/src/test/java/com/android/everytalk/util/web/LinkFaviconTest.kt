package com.android.everytalk.util.web

import org.junit.Assert.assertEquals
import com.android.everytalk.R
import org.junit.Test

class LinkFaviconTest {

    @Test
    fun `已知网站及子域名直接使用内置图标`() {
        assertEquals(R.drawable.ic_website_github, linkIcon("https://www.github.com/a?q=1").resource)
        assertEquals(R.drawable.ic_website_wikipedia, linkIcon("https://zh.wikipedia.org/wiki/Test").resource)
        assertEquals(R.drawable.ic_website_x, linkIcon("http://twitter.com/test").resource)
        assertEquals(R.drawable.ic_website_youtube, linkIcon("https://youtu.be/test").resource)
        assertEquals(R.drawable.ic_website_bilibili, linkIcon("https://b23.tv/test").resource)
        assertEquals(R.drawable.ic_website_openai, linkIcon("https://chat.openai.com").resource)
    }

    @Test
    fun `host 规范化并去掉尾部点`() {
        assertEquals("example.com", linkHost("HTTPS://WWW.Example.COM./a"))
        assertEquals("", linkHost("not-a-url"))
    }

    @Test
    fun `具体产品优先于母站且保留彩色标志`() {
        assertEquals(LinkIcon(R.drawable.ic_website_google_drive, true), linkIcon("https://docs.google.com/document/d/1"))
        assertEquals(LinkIcon(R.drawable.ic_website_gmail, true), linkIcon("https://mail.google.com"))
        assertEquals(LinkIcon(R.drawable.ic_website_google_calendar, true), linkIcon("https://calendar.google.com"))
        assertEquals(LinkIcon(R.drawable.ic_website_google, true), linkIcon("https://www.google.com/search?q=1"))
        assertEquals(LinkIcon(R.drawable.ic_website_github), linkIcon("https://github.com"))
        assertEquals(LinkIcon(R.drawable.ic_website_gemini, true), linkIcon("https://gemini.google.com/app"))
        assertEquals(LinkIcon(R.drawable.ic_website_firebase), linkIcon("https://firebase.google.com/docs"))
        assertEquals(LinkIcon(R.drawable.ic_website_azure, true), linkIcon("https://azure.microsoft.com"))
        assertEquals(LinkIcon(R.drawable.ic_website_microsoft, true), linkIcon("https://learn.microsoft.com"))
        assertEquals(LinkIcon(R.drawable.ic_website_google, true), linkIcon("https://www.google.com.hk/search?q=1"))
    }

    @Test
    fun `补充网站和别名均使用对应资源`() {
        val sites = mapOf(
            "midjourney.com" to LinkIcon(R.drawable.ic_website_midjourney),
            "firefly.adobe.com" to LinkIcon(R.drawable.ic_website_adobefirefly, true),
            "stability.ai" to LinkIcon(R.drawable.ic_website_stability, true),
            "civitai.com" to LinkIcon(R.drawable.ic_website_civitai, true),
            "ideogram.ai" to LinkIcon(R.drawable.ic_website_ideogram),
            "recraft.ai" to LinkIcon(R.drawable.ic_website_recraft),
            "runwayml.com" to LinkIcon(R.drawable.ic_website_runway),
            "klingai.com" to LinkIcon(R.drawable.ic_website_kling, true),
            "kling.kuaishou.com" to LinkIcon(R.drawable.ic_website_kling, true),
            "jimeng.jianying.com" to LinkIcon(R.drawable.ic_website_jimeng, true),
            "dreamina.capcut.com" to LinkIcon(R.drawable.ic_website_jimeng, true),
            "lumalabs.ai" to LinkIcon(R.drawable.ic_website_luma, true),
            "bfl.ai" to LinkIcon(R.drawable.ic_website_flux),
            "bfl.ml" to LinkIcon(R.drawable.ic_website_flux),
            "gemini.google.com" to LinkIcon(R.drawable.ic_website_gemini, true),
            "perplexity.ai" to LinkIcon(R.drawable.ic_website_perplexity, true),
            "grok.com" to LinkIcon(R.drawable.ic_website_grok),
            "x.ai" to LinkIcon(R.drawable.ic_website_grok),
            "qwen.ai" to LinkIcon(R.drawable.ic_website_qwen, true),
            "qianwen.com" to LinkIcon(R.drawable.ic_website_qwen, true),
            "tongyi.aliyun.com" to LinkIcon(R.drawable.ic_website_qwen, true),
            "doubao.com" to LinkIcon(R.drawable.ic_website_doubao, true),
            "kimi.com" to LinkIcon(R.drawable.ic_website_kimi, true),
            "kimi.moonshot.cn" to LinkIcon(R.drawable.ic_website_kimi, true),
            "moonshot.ai" to LinkIcon(R.drawable.ic_website_kimi, true),
            "moonshot.cn" to LinkIcon(R.drawable.ic_website_kimi, true),
            "modelscope.cn" to LinkIcon(R.drawable.ic_website_modelscope, true),
            "modelscope.ai" to LinkIcon(R.drawable.ic_website_modelscope, true),
            "ollama.com" to LinkIcon(R.drawable.ic_website_ollama),
            "microsoft.com" to LinkIcon(R.drawable.ic_website_microsoft, true),
            "apple.com" to LinkIcon(R.drawable.ic_website_apple),
            "baidu.com" to LinkIcon(R.drawable.ic_website_baidu, true),
            "baike.baidu.com" to LinkIcon(R.drawable.ic_website_baidu, true),
            "bing.com" to LinkIcon(R.drawable.ic_website_bing, true),
            "aws.amazon.com" to LinkIcon(R.drawable.ic_website_aws, true),
            "azure.com" to LinkIcon(R.drawable.ic_website_azure, true),
            "azure.microsoft.com" to LinkIcon(R.drawable.ic_website_azure, true),
            "minimax.io" to LinkIcon(R.drawable.ic_website_minimax, true),
            "minimaxi.com" to LinkIcon(R.drawable.ic_website_minimax, true),
            "zhihu.com" to LinkIcon(R.drawable.ic_website_zhihu),
            "weibo.com" to LinkIcon(R.drawable.ic_website_weibo),
            "weibo.cn" to LinkIcon(R.drawable.ic_website_weibo),
            "weixin.qq.com" to LinkIcon(R.drawable.ic_website_wechat),
            "mp.weixin.qq.com" to LinkIcon(R.drawable.ic_website_wechat),
            "wechat.com" to LinkIcon(R.drawable.ic_website_wechat),
            "npmjs.com" to LinkIcon(R.drawable.ic_website_npm),
            "npmjs.org" to LinkIcon(R.drawable.ic_website_npm),
            "docker.com" to LinkIcon(R.drawable.ic_website_docker),
            "python.org" to LinkIcon(R.drawable.ic_website_python),
            "react.dev" to LinkIcon(R.drawable.ic_website_react),
            "reactjs.org" to LinkIcon(R.drawable.ic_website_react),
            "nodejs.org" to LinkIcon(R.drawable.ic_website_nodejs),
            "typescriptlang.org" to LinkIcon(R.drawable.ic_website_typescript),
            "supabase.com" to LinkIcon(R.drawable.ic_website_supabase),
            "firebase.google.com" to LinkIcon(R.drawable.ic_website_firebase),
            "pinterest.com" to LinkIcon(R.drawable.ic_website_pinterest),
            "pin.it" to LinkIcon(R.drawable.ic_website_pinterest),
            "telegram.org" to LinkIcon(R.drawable.ic_website_telegram),
            "t.me" to LinkIcon(R.drawable.ic_website_telegram),
            "medium.com" to LinkIcon(R.drawable.ic_website_medium),
        )
        sites.forEach { (host, expected) ->
            assertEquals(host, expected, linkIcon("https://$host/path"))
            assertEquals(host, expected, linkIcon("https://www.$host/path"))
        }
    }

    @Test
    fun `未知网站无效地址及伪装域名统一使用地球`() {
        listOf(
            "https://example.com", "", "not-a-url", "mailto:test@github.com",
            "everytalk-footnote-reference:1", "https://github.com.example.org",
            "https://notgithub.com", "https://github.com@evil.example",
            "https://gemini.google.com.evil.example", "https://notmidjourney.com",
            "https://google.com.evil.example", "https://unknown.example/?url=https://klingai.com",
        ).forEach { href ->
            assertEquals(href, LinkIcon(R.drawable.ic_globe), linkIcon(href))
        }
    }
}
