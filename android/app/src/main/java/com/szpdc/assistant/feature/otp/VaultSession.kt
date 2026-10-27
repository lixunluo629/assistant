package com.szpdc.assistant.feature.otp

import android.content.Context
import com.szpdc.assistant.CloudConfig
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 登录态和同步密钥只放在本机密钥库里。卸载应用后密钥库一起消失，
 * 重新登录时再用同一个账号密码把云端密文解出来。
 */
object VaultSession {
    private const val PREFS = "otp_account"
    private const val KEY_ALIAS = "otp-vault-wrap"
    private const val SERVER = "server"
    private const val USERNAME = "username"
    private const val TOKEN = "token"
    private const val WRAPPED = "wrapped_key"

    fun server(context: Context): String {
        val saved = prefs(context).getString(SERVER, "").orEmpty().trimEnd('/')
        if (saved.isEmpty() || saved == "http://8.138.34.69" || saved == "http://8.138.34.69:80" ||
            saved == "http://lixl.chat" || saved == "http://lixl.chat:8007"
        ) {
            return CloudConfig.BASE_URL
        }
        return saved
    }

    fun username(context: Context): String = prefs(context).getString(USERNAME, "").orEmpty()

    fun token(context: Context): String = prefs(context).getString(TOKEN, "").orEmpty()

    fun isLoggedIn(context: Context): Boolean = token(context).isNotEmpty() && vaultKey(context) != null

    fun saveLogin(context: Context, server: String, username: String, token: String, vaultKey: ByteArray) {
        prefs(context).edit()
            .putString(SERVER, server.trim().trimEnd('/'))
            .putString(USERNAME, username.trim())
            .putString(TOKEN, token)
            .putString(WRAPPED, wrap(vaultKey))
            .commit()
    }

    fun saveServerDraft(context: Context, server: String, username: String) {
        prefs(context).edit()
            .putString(SERVER, server.trim().trimEnd('/'))
            .putString(USERNAME, username.trim())
            .apply()
    }

    fun logout(context: Context) {
        prefs(context).edit().remove(TOKEN).remove(WRAPPED).apply()
    }

    fun vaultKey(context: Context): ByteArray? {
        val wrapped = prefs(context).getString(WRAPPED, null) ?: return null
        return runCatching { unwrap(wrapped) }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wrap(vaultKey: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val body = cipher.doFinal(vaultKey)
        return VaultCrypto.b64(iv + body)
    }

    private fun unwrap(packed: String): ByteArray {
        val raw = VaultCrypto.b64Decode(packed)
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
