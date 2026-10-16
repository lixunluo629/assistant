package com.szpdc.assistant.feature.scan

import java.text.Normalizer

/**
 * 二维码内容解析：只做识别，不联网，不保留原文。
 * 与 Python 的 parser.parse() 保持完全相同的流水线：
 *   清洗 -> scheme 闸门 -> URL host 最长后缀匹配 -> （无 URL 时）loose 文字线索
 */
object QrParser {
    private const val INVISIBLE = "\u200b\u200c\u200d\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2060\ufeff"
    private const val TRAILING_JUNK = """。，、；：.,;:'"”’)]）>}］"""

    private val INVISIBLE_RE = Regex("[" + INVISIBLE + "]")
    private val SCHEME_RE = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*):""")
    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val AUTHORITY_RE = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]*)""")
    private val BARE_DOMAIN_RE = Regex(
        """\b((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LOOSE_CONTEXT_RE = Regex(
        """扫码|扫一扫|二维码|登录|登入|sign\s*in|login|scan""",
        RegexOption.IGNORE_CASE,
    )

    fun clean(raw: String): String =
        INVISIBLE_RE.replace(Normalizer.normalize(raw, Normalizer.Form.NFKC), "").trim()

    fun parse(raw: String, rules: RuleSet): ParseOutcome {
        val text = clean(raw)
        val fingerprint = Fingerprint.of(text)

        if (text.isEmpty()) {
            return ParseOutcome.Rejected(RejectionReason.EMPTY, "二维码内容为空", fingerprint)
        }

        // 1) 非 http(s) 协议：可能是伪造的 weixin:// / mqq:// 跳转载荷，直接拒绝
        val scheme = SCHEME_RE.find(text)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") {
            return ParseOutcome.Rejected(
                RejectionReason.UNSAFE_SCHEME,
                "二维码内容使用了非网页协议 " + scheme + "://，登录二维码不应是这种形式，已拒绝处理",
                fingerprint,
            )
        }

        // 2) 有合法 URL：按 host 做最长后缀白名单匹配
        val urls = URL_RE.findAll(text)
            .map { it.value.trimEnd { ch -> TRAILING_JUNK.contains(ch) } }
            .filter { it.isNotEmpty() }
            .toList()

        if (urls.isNotEmpty()) {
            for (url in urls) {
                val match = AUTHORITY_RE.find(url) ?: continue
                val urlScheme = match.groupValues[1].lowercase()
                if (urlScheme != "http" && urlScheme != "https") continue
                val hit = rules.matchHost(match.groupValues[2]) ?: continue
                return ParseOutcome.Success(
                    LoginTarget(
                        platform = hit.first.platform,
                        host = hit.second,
                        matchedBy = "host",
                        loose = false,
                        hasCredential = rules.hasCredentialParam(queryKeys(url)),
                        fingerprint = fingerprint,
                    )
                )
            }
            // URL 存在但没命中白名单：直接拒绝，绝不回退文字匹配，
            // 否则 https://evil.example.com/?next=wx.qq.com 会被宽松放行。
            return ParseOutcome.Rejected(
                RejectionReason.UNKNOWN_PLATFORM,
                "二维码指向的域名不在支持的平台白名单内，已停止处理",
                fingerprint,
            )
        }

        // 3) 完全没有 URL：才允许文字线索回退，并要求出现登录语境词
        if (BARE_DOMAIN_RE.containsMatchIn(text) || LOOSE_CONTEXT_RE.containsMatchIn(text)) {
            val hit = rules.matchText(text)
            if (hit != null) {
                return ParseOutcome.Success(
                    LoginTarget(
                        platform = hit.first.platform,
                        host = hit.second,
                        matchedBy = hit.third,
                        loose = true,
                        hasCredential = false,
                        fingerprint = fingerprint,
                    )
                )
            }
        }

        return ParseOutcome.Rejected(
            RejectionReason.UNKNOWN_PLATFORM,
            "无法从二维码内容识别出支持的平台",
            fingerprint,
        )
    }

    /** 只取参数名用于判断是否带票据；参数值不做任何保留。 */
    private fun queryKeys(url: String): List<String> {
        val index = url.indexOfAny(charArrayOf('?', '#'))
        if (index < 0 || index == url.length - 1) return emptyList()
        val query = url.substring(index + 1)
        val keys = ArrayList<String>()
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key.isNotEmpty()) keys.add(key)
        }
        return keys
    }
}
