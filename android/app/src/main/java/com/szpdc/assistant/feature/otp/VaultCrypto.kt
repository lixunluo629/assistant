package com.szpdc.assistant.feature.otp

import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 令牌库的密钥从账号密码在本机派生。
 * 发给服务器的只有登录用的 authKey，以及用另一把密钥加密后的密文。
 */
object VaultCrypto {
    private const val ITERATIONS = 120_000

    data class Keys(val authKey: String, val vaultKey: ByteArray)

    fun derive(username: String, password: String): Keys {
        val master = pbkdf2(
            password.toByteArray(Charsets.UTF_8),
            ("assistant-otp-v1:" + username.trim().lowercase()).toByteArray(Charsets.UTF_8),
            ITERATIONS,
            32,
        )
        val auth = hmac(master, "auth".toByteArray())
        val vault = hmac(master, "vault".toByteArray())
        Arrays.fill(master, 0)
        return Keys(auth.joinToString("") { "%02x".format(it) }, vault)
    }

    fun encrypt(vaultKey: ByteArray, plaintext: ByteArray): Pair<String, String> {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(vaultKey, "AES"), GCMParameterSpec(128, iv))
        return b64(iv) to b64(cipher.doFinal(plaintext))
    }

    fun decrypt(vaultKey: ByteArray, ivB64: String, ciphertextB64: String): ByteArray? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(vaultKey, "AES"),
                GCMParameterSpec(128, b64Decode(ivB64)),
            )
            cipher.doFinal(b64Decode(ciphertextB64))
        }.getOrNull()
    }

    fun b64(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(table[b0 ushr 2])
            out.append(table[((b0 and 3) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) out.append(table[((b1 and 15) shl 2) or (b2 ushr 6)]) else out.append('=')
            if (i + 2 < bytes.size) out.append(table[b2 and 63]) else out.append('=')
            i += 3
        }
        return out.toString()
    }

    fun b64Decode(text: String): ByteArray {
        val clean = text.filter { it != '=' && !it.isWhitespace() }
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (char in clean) {
            val value = when (char) {
                in 'A'..'Z' -> char - 'A'
                in 'a'..'z' -> char - 'a' + 26
                in '0'..'9' -> char - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> continue
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((buffer shr bits).toByte())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLen = mac.macLength
        val blocks = (length + hashLen - 1) / hashLen
        val out = ByteArray(blocks * hashLen)
        val block = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, block, 0, salt.size)
        for (index in 1..blocks) {
            block[salt.size] = (index ushr 24).toByte()
            block[salt.size + 1] = (index ushr 16).toByte()
            block[salt.size + 2] = (index ushr 8).toByte()
            block[salt.size + 3] = index.toByte()
            var u = mac.doFinal(block)
            val mixed = u.copyOf()
            for (round in 1 until iterations) {
                u = mac.doFinal(u)
                for (i in mixed.indices) mixed[i] = (mixed[i].toInt() xor u[i].toInt()).toByte()
            }
            System.arraycopy(mixed, 0, out, (index - 1) * hashLen, hashLen)
        }
        return out.copyOf(length)
    }
}
