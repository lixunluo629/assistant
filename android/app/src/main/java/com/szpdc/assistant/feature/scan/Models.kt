package com.szpdc.assistant.feature.scan

import java.security.MessageDigest

/**
 * 平台无关数据模型。与 Python 核心库 core/qrlogin/models.py 保持同一套语义。
 *
 * 安全边界：
 * 1. 本文件不保存二维码原文，也不保存其中的任何登录票据；
 * 2. LoginTarget 只带 hasCredential 布尔标记，不带票据值；
 * 3. Decision 只描述"用户接下来做什么"，不含可用于自动授权的载荷。
 */

enum class Platform(val id: String, val displayName: String) {
    WECHAT("wechat", "微信"),
    DINGTALK("dingtalk", "钉钉"),
    QQ("qq", "QQ"),
    ALIPAY("alipay", "支付宝"),
    BILIBILI("bilibili", "哔哩哔哩"),
    WEWORK("wework", "企业微信"),
    BAIDU("baidu", "百度"),
    QUARK("quark", "夸克"),
    XIAOHONGSHU("xiaohongshu", "小红书"),
    DOUYIN("douyin", "抖音"),
    STEAM("steam", "Steam"),
    UNKNOWN("unknown", "未知平台");

    companion object {
        fun fromId(id: String?): Platform {
            if (id == null) return UNKNOWN
            for (value in values()) {
                if (value.id.equals(id, ignoreCase = true)) return value
            }
            return UNKNOWN
        }
    }
}

/** 三态是刻意的：查不到 ≠ 没安装。检测受限时必须如实返回 UNKNOWN。 */
enum class InstallStatus { INSTALLED, NOT_INSTALLED, UNKNOWN }

enum class Action { OPEN_APP, PROMPT_DOWNLOAD, MANUAL_SCAN, PASSWORD_LOGIN, REJECT }

enum class RejectionReason { EMPTY, UNSAFE_SCHEME, UNKNOWN_PLATFORM, MALFORMED }

data class LoginTarget(
    val platform: Platform,
    val host: String = "",
    /** host 后缀匹配 = "host"；全文关键词回退 = "url_keyword" / "domain_keyword" / "text_alias" */
    val matchedBy: String = "",
    /** true 表示只靠文字线索猜出来的平台，禁止自动跳转 */
    val loose: Boolean = false,
    /** 二维码里是否带一次性登录票据，只记布尔 */
    val hasCredential: Boolean = false,
    /** 原文的加盐哈希前缀，仅用于日志关联 */
    val fingerprint: String = "",
) {
    fun toLogLine(): String =
        "platform=" + platform.id +
            " host=" + host.ifEmpty { "-" } +
            " matchedBy=" + matchedBy.ifEmpty { "-" } +
            " loose=" + loose +
            " hasCredential=" + hasCredential +
            " fp=" + fingerprint.ifEmpty { "-" }
}

data class Decision(
    val action: Action,
    val platform: Platform,
    val title: String,
    val detail: String,
    /** 只可能来自规则表常量，绝不拼接二维码内容 */
    val deepLink: String? = null,
    val storeUrl: String? = null,
    val fallbackUrl: String? = null,
    /**
     * 官方 App 包名，来自规则表常量。
     *
     * 启动 App 应当优先用包名（getLaunchIntentForPackage），而不是 scheme：
     * - 包名就是安装检测用的那一份，不会出现"检测到已安装、点按钮却唤不起"；
     * - 裸 scheme（如 dingtalk://）是否注册完全取决于各 App 自己，钉钉就不响应裸 scheme；
     * - deepLink 降级为兜底。
     */
    val androidPackages: List<String> = emptyList(),
    /**
     * 直达「扫一扫」的候选深链，按优先级排列（规则表常量）。
     *
     * 实测只有钉钉对外暴露了扫一扫入口；微信 9.x / QQ 没有，
     * 此时为空列表，UI 退化为"打开 App，请用户自己点扫一扫"。
     */
    val scanDeepLinks: List<String> = emptyList(),
    /**
     * 是否由 UI 在展示结果后自动执行主操作，不再要求用户点一下。
     *
     * OPEN_APP / PROMPT_DOWNLOAD 为 true：用户主动扫这个码本身就是明确意图；
     * REJECT / MANUAL_SCAN 恒为 false —— 安全警告必须让用户看到并自己决策。
     */
    val scanActivityClasses: List<String> = emptyList(),
    /** 直达扫一扫的显式 Activity 类名候选（规则表常量），按优先级排列 */
    val autoLaunch: Boolean = false,
    val steps: List<String> = emptyList(),
    val rejectionReason: RejectionReason? = null,
    val fingerprint: String = "",
) {
    /** 只有白名单规则表里写死的深链才允许被系统调起 */
    val isSafeToLaunch: Boolean
        get() = action == Action.OPEN_APP && !deepLink.isNullOrEmpty()
}

sealed interface ParseOutcome {
    data class Success(val target: LoginTarget) : ParseOutcome
    data class Rejected(
        val reason: RejectionReason,
        val message: String,
        val fingerprint: String = "",
    ) : ParseOutcome
}

internal object Fingerprint {
    private const val SALT = "qr-login-assistant/v1"
    private val HEX = "0123456789abcdef".toCharArray()

    /** 加盐 SHA-256 前 12 位：可入日志排障，不可逆，不泄漏原文。 */
    fun of(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(SALT.toByteArray(Charsets.UTF_8))
        digest.update(raw.toByteArray(Charsets.UTF_8))
        val bytes = digest.digest()
        val builder = StringBuilder(12)
        for (byte in bytes) {
            val value = byte.toInt()
            builder.append(HEX[(value shr 4) and 0x0F])
            builder.append(HEX[value and 0x0F])
            if (builder.length >= 12) break
        }
        return builder.toString()
    }
}
