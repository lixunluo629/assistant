package com.szpdc.assistant.feature.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TotpTest {
    @Test
    fun rfc6238Sha1Vector() {
        // RFC 6238 附录 B：密钥是 "12345678901234567890" 的 Base32，T=59，8 位。
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("94287082", Totp.code(secret, "SHA1", 8, 30, 59_000))
    }

    @Test
    fun parseOtpAuth() {
        val parsed = Totp.parse(
            "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&digits=6&period=30",
        )
        assertNotNull(parsed)
        assertEquals("JBSWY3DPEHPK3PXP", parsed!!.secret)
        assertEquals("Example", parsed.issuer)
        assertEquals("alice@example.com", parsed.account)
        assertEquals(6, parsed.digits)
    }

    @Test
    fun rejectsNonTotp() {
        assertNull(Totp.parse("https://example.com/secret=ABC"))
        assertNull(Totp.parse("otpauth://hotp/Example?secret=JBSWY3DPEHPK3PXP"))
        assertNull(Totp.normalizeSecret("hello!"))
    }

    @Test
    fun encryptRoundTripAndMerge() {
        val keys = VaultCrypto.derive("alice", "correct horse")
        val original = VaultCodec.toJson(
            listOf(OtpToken.create("工作", "Example", "alice", "JBSWY3DPEHPK3PXP", "SHA1", 6, 30)),
        )
        val (iv, ciphertext) = VaultCrypto.encrypt(keys.vaultKey, original.toByteArray())
        val plain = VaultCrypto.decrypt(keys.vaultKey, iv, ciphertext)
        assertEquals(original, String(plain!!))

        val local = VaultCodec.fromJson(original)
        val remote = listOf(local[0].copy(label = "旧标签", updatedAt = local[0].updatedAt - 10))
        assertEquals("工作", VaultCodec.merge(local, remote)[0].label)

        val deleted = local[0].copy(deleted = true, updatedAt = local[0].updatedAt + 5)
        val merged = VaultCodec.merge(listOf(deleted), local)
        assertEquals(true, merged[0].deleted)
    }

    @Test
    fun mergeKeepsDragOrder() {
        val later = OtpToken.create("乙", "", "", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 6, 30).copy(sort = 1)
        val earlier = OtpToken.create("甲", "", "", "JBSWY3DPEHPK3PXP", "SHA1", 6, 30).copy(sort = 0)
        val merged = VaultCodec.merge(listOf(later, earlier), emptyList())
        assertEquals(earlier.id, merged[0].id)
        assertEquals(later.id, merged[1].id)
    }
}
