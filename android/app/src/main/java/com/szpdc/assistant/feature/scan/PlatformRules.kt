package com.szpdc.assistant.feature.scan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则表加载与匹配。数据来自 core/platform_rules.json（Python 核心库同源）。
 *
 * 匹配策略：最长后缀优先（weixin.qq.com 优先于 qq.com），denylist 一票否决。
 * 与 Python 的 rules.RuleSet 行为一致，改动请同步两侧。
 */

data class PlatformRule(
    val platform: Platform,
    val displayName: String,
    val domains: List<String>,
    val urlKeywords: List<String>,
    val textAliases: List<String>,
    val hostDenylist: List<String>,
    val deepLink: String?,
    /** 该平台的其它合法 scheme（如 QQ 的 mqqapi） */
    val extraSchemes: List<String>,
    /** 直达「扫一扫」的候选深链（规则表常量）；为空表示该平台没对外暴露扫一扫入口 */
    val scanDeepLinks: List<String>,
    /** 没有 scheme filter 但 exported=true 的扫码 Activity（如百度） */
    val scanActivityClasses: List<String>,
    val scanEntryNote: String,
    val androidPackages: List<String>,
    val storeUrls: Map<String, String>,
    val passwordLoginUrl: String?,
    val passwordLoginNote: String,
    val scanTargetHint: String,
) {
    fun storeUrlFor(environment: String = "android"): String? =
        storeUrls[environment] ?: storeUrls["web"]

    companion object {
        fun fromJson(json: JSONObject): PlatformRule {
            val scanEntry = json.optJSONObject("scan_entry")
            val storeUrls = LinkedHashMap<String, String>()
            val storeJson = json.optJSONObject("store_urls")
            if (storeJson != null) {
                for (key in storeJson.keys()) {
                    storeUrls[key] = storeJson.optString(key)
                }
            }
            return PlatformRule(
                platform = Platform.fromId(json.optString("id")),
                displayName = json.optString("display_name", json.optString("id")),
                domains = json.optJSONArray("domains").asLowerStringList(),
                urlKeywords = json.optJSONArray("url_keywords").asLowerStringList(),
                textAliases = json.optJSONArray("text_aliases").asLowerStringList(),
                hostDenylist = json.optJSONArray("host_denylist").asLowerStringList(),
                deepLink = json.optString("deep_link").ifEmpty { null },
                extraSchemes = json.optJSONArray("extra_schemes").asLowerStringList(),
                scanDeepLinks = scanEntry?.optJSONArray("deep_links").asStringList() ?: emptyList(),
                scanActivityClasses = scanEntry?.optJSONArray("activity_classes").asStringList() ?: emptyList(),
                scanEntryNote = scanEntry?.optString("note") ?: "",
                androidPackages = json.optJSONArray("android_packages").asStringList(),
                storeUrls = storeUrls,
                passwordLoginUrl = json.optString("password_login_url").ifEmpty { null },
                passwordLoginNote = json.optString("password_login_note"),
                scanTargetHint = json.optString("scan_target_hint", "扫一扫"),
            )
        }
    }
}

private fun JSONArray?.asStringList(): List<String> {
    if (this == null) return emptyList()
    val result = ArrayList<String>(length())
    for (index in 0 until length()) {
        val value = optString(index, "")
        if (value.isNotEmpty()) result.add(value)
    }
    return result
}

private fun JSONArray?.asLowerStringList(): List<String> =
    asStringList().map { it.lowercase() }

class RuleSet private constructor(
    val version: String,
    val rules: List<PlatformRule>,
    private val credentialParams: Set<String>,
) {
    private val domainIndex: List<Pair<String, PlatformRule>> =
        rules.flatMap { rule -> rule.domains.map { domain -> domain to rule } }
            .sortedByDescending { it.first.length }

    private val keywordIndex: List<Pair<String, PlatformRule>> =
        rules.flatMap { rule -> rule.urlKeywords.map { keyword -> keyword to rule } }
            .sortedByDescending { it.first.length }

    private val aliasIndex: List<Pair<String, PlatformRule>> =
        rules.flatMap { rule -> rule.textAliases.map { alias -> alias to rule } }
            .sortedByDescending { it.first.length }

    private val denylist: List<String> =
        rules.flatMap { rule -> rule.hostDenylist }.sortedByDescending { it.length }

    /** 小写、去端口、去尾部点，避免 "Login.WeiXin.QQ.com:443." 绕过匹配。 */
    fun normalizeHost(raw: String): String {
        var host = raw.trim().lowercase()
        if (host.isEmpty()) return ""
        if (host.startsWith("[")) return "" // IPv6 字面量：登录二维码不会用
        val slash = host.indexOf('/')
        if (slash >= 0) host = host.substring(0, slash)
        if (host.count { it == ':' } == 1) host = host.substringBefore(':')
        return host.trimEnd('.')
    }

    fun isDenied(host: String): Boolean = denylist.any { hostMatches(host, it) }

    /** 按最长后缀匹配 host，返回 (规则, 命中的域名)。 */
    fun matchHost(rawHost: String): Pair<PlatformRule, String>? {
        val host = normalizeHost(rawHost)
        if (host.isEmpty() || isDenied(host)) return null
        for (entry in domainIndex) {
            if (hostMatches(host, entry.first)) return entry.second to entry.first
        }
        return null
    }

    /** 全文关键词回退，返回 (规则, 命中词, 匹配层级)。仅在没解析出合法 URL 时使用。 */
    fun matchText(text: String): Triple<PlatformRule, String, String>? {
        val low = text.lowercase()
        if (low.isEmpty()) return null
        for (entry in keywordIndex) {
            if (low.contains(entry.first) && !isDenied(entry.first)) {
                return Triple(entry.second, entry.first, "url_keyword")
            }
        }
        for (entry in domainIndex) {
            if (low.contains(entry.first) && !isDenied(entry.first)) {
                return Triple(entry.second, entry.first, "domain_keyword")
            }
        }
        for (entry in aliasIndex) {
            if (low.contains(entry.first) && !isDenied(entry.first)) {
                return Triple(entry.second, entry.first, "text_alias")
            }
        }
        return null
    }

    fun ruleFor(platform: Platform): PlatformRule? =
        rules.firstOrNull { it.platform == platform }

    /** 是否可直接把用户送到扫一扫界面（深链或显式类名任一可用即可） */
    fun supportsDirectScan(rule: PlatformRule): Boolean =
        rule.scanDeepLinks.isNotEmpty() || rule.scanActivityClasses.isNotEmpty()

    fun hasCredentialParam(keys: List<String>): Boolean =
        keys.any { credentialParams.contains(it.lowercase()) }

    private fun hostMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith("." + domain)

    companion object {
        fun fromJson(json: String): RuleSet {
            val root = JSONObject(json)
            val array = root.optJSONArray("platforms") ?: JSONArray()
            val parsed = ArrayList<PlatformRule>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parsed.add(PlatformRule.fromJson(item))
            }
            require(parsed.isNotEmpty()) { "规则表不能为空" }

            val credentials = HashSet<String>()
            val credentialArray = root.optJSONArray("credential_params")
            if (credentialArray != null) {
                for (index in 0 until credentialArray.length()) {
                    val name = credentialArray.optString(index)
                    if (name.isNotEmpty()) credentials.add(name.lowercase())
                }
            }
            return RuleSet(root.optString("version", "unknown"), parsed, credentials)
        }

        /** 规则表由 gradle 从 core/platform_rules.json 同步到 assets 根目录。 */
        fun fromAssets(context: Context, assetName: String = "platform_rules.json"): RuleSet {
            val text = context.assets.open(assetName)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            return fromJson(text)
        }
    }
}
