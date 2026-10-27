package com.szpdc.assistant.feature.otp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class OtpToken(
    val id: String,
    val label: String,
    val issuer: String,
    val account: String,
    val secret: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val updatedAt: Long,
    val sort: Int = 0,
    val deleted: Boolean = false,
) {
    fun displayName(): String = label.ifBlank {
        listOf(issuer, account).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "未命名" }
    }

    companion object {
        fun create(
            label: String,
            issuer: String,
            account: String,
            secret: String,
            algorithm: String,
            digits: Int,
            period: Int,
        ): OtpToken = OtpToken(
            id = UUID.randomUUID().toString(),
            label = label.trim(),
            issuer = issuer.trim(),
            account = account.trim(),
            secret = secret,
            algorithm = algorithm,
            digits = digits,
            period = period,
            updatedAt = System.currentTimeMillis(),
            deleted = false,
        )
    }
}

object VaultCodec {
    fun toJson(tokens: List<OtpToken>): String {
        val array = JSONArray()
        for (token in tokens) {
            array.put(
                JSONObject()
                    .put("id", token.id)
                    .put("label", token.label)
                    .put("issuer", token.issuer)
                    .put("account", token.account)
                    .put("secret", token.secret)
                    .put("algorithm", token.algorithm)
                    .put("digits", token.digits)
                    .put("period", token.period)
                    .put("updatedAt", token.updatedAt)
                    .put("sort", token.sort)
                    .put("deleted", token.deleted),
            )
        }
        return JSONObject().put("tokens", array).toString()
    }

    fun fromJson(text: String): List<OtpToken> {
        if (text.isBlank()) return emptyList()
        val array = JSONObject(text).optJSONArray("tokens") ?: return emptyList()
        val result = ArrayList<OtpToken>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val secret = item.optString("secret")
            if (Totp.normalizeSecret(secret) == null) continue
            result.add(
                OtpToken(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    label = item.optString("label"),
                    issuer = item.optString("issuer"),
                    account = item.optString("account"),
                    secret = Totp.normalizeSecret(secret)!!,
                    algorithm = item.optString("algorithm", "SHA1"),
                    digits = item.optInt("digits", 6).coerceIn(6, 8),
                    period = item.optInt("period", 30).coerceIn(15, 120),
                    updatedAt = item.optLong("updatedAt"),
                    sort = if (item.has("sort")) item.optInt("sort") else index,
                    deleted = item.optBoolean("deleted", false),
                ),
            )
        }
        return result
    }

    /** 同一条令牌取更新更晚的那份，避免卸载重装后把云端覆盖掉。 */
    fun merge(local: List<OtpToken>, remote: List<OtpToken>): List<OtpToken> {
        val byId = LinkedHashMap<String, OtpToken>()
        for (token in remote) byId[token.id] = token
        for (token in local) {
            val other = byId[token.id]
            if (other == null || token.updatedAt >= other.updatedAt) byId[token.id] = token
        }
        return byId.values.sortedWith(compareBy<OtpToken> { it.sort }.thenBy { it.displayName().lowercase() })
    }
}
