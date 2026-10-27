package com.szpdc.assistant.feature.otp

import java.net.URI
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/** RFC 6238 动态口令。只认 otpauth://totp，不解析别的二维码。 */
object Totp {

    data class Parsed(
        val label: String,
        val issuer: String,
        val account: String,
        val secret: String,
        val algorithm: String,
        val digits: Int,
        val period: Int,
    )

    fun parse(raw: String): Parsed? {
        val text = raw.trim()
        if (!text.startsWith("otpauth://", ignoreCase = true)) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("otpauth", ignoreCase = true)) return null
        if (!uri.host.equals("totp", ignoreCase = true)) return null
        val query = queryMap(uri.rawQuery ?: return null)
        val secret = normalizeSecret(query["secret"] ?: return null) ?: return null
        val algorithm = when (query["algorithm"]?.uppercase()) {
            "SHA256" -> "SHA256"
            "SHA512" -> "SHA512"
            else -> "SHA1"
        }
        val digits = query["digits"]?.toIntOrNull()?.takeIf { it in 6..8 } ?: 6
        val period = query["period"]?.toIntOrNull()?.takeIf { it in 15..120 } ?: 30
        val path = uri.rawPath?.removePrefix("/")?.let { decode(it) }.orEmpty()
        val issuerFromPath = path.substringBefore(":", "").trim()
        val account = if (path.contains(":")) path.substringAfter(":", "").trim() else path.trim()
        val issuer = query["issuer"]?.let { decode(it) }?.trim().orEmpty().ifEmpty { issuerFromPath }
        val label = listOf(issuer, account).filter { it.isNotEmpty() }.joinToString(" ")
        return Parsed(label, issuer, account, secret, algorithm, digits, period)
    }

    fun normalizeSecret(raw: String): String? {
        val cleaned = raw.uppercase().filter { it != ' ' && it != '-' && it != '=' }
        if (cleaned.length < 8 || cleaned.any { it !in BASE32 }) return null
        if (decodeBase32(cleaned) == null) return null
        return cleaned
    }

    fun code(secret: String, algorithm: String, digits: Int, period: Int, nowMillis: Long): String {
        val key = decodeBase32(secret) ?: return "-".repeat(digits)
        val counter = nowMillis / 1000 / period
        val mac = Mac.getInstance(macName(algorithm))
        mac.init(SecretKeySpec(key, macName(algorithm)))
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = value.toByte()
            value = value ushr 8
        }
        val hash = mac.doFinal(data)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val mod = 10.0.pow(digits).toInt()
        return (binary % mod).toString().padStart(digits, '0')
    }

    fun remainingSeconds(period: Int, nowMillis: Long): Int {
        val elapsed = ((nowMillis / 1000) % period).toInt()
        return period - elapsed
    }

    fun formatCode(code: String): String {
        if (code.length <= 4 || code.any { it == '-' }) return code
        val mid = code.length / 2
        return code.substring(0, mid) + " " + code.substring(mid)
    }

    fun decodeBase32(secret: String): ByteArray? {
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>(secret.length)
        for (char in secret) {
            val value = BASE32.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add((buffer shr bits).toByte())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (out.isEmpty()) return null
        return out.toByteArray()
    }

    private fun macName(algorithm: String): String = when (algorithm) {
        "SHA256" -> "HmacSHA256"
        "SHA512" -> "HmacSHA512"
        else -> "HmacSHA1"
    }

    private fun queryMap(query: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            result[key.lowercase()] = value
        }
        return result
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
}
