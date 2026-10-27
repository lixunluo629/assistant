package com.szpdc.assistant.feature.otp

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 把整库加密后上传。服务器只保存密文，解不开令牌。 */
object VaultSync {

    fun pushAsync(context: Context, onDone: (String?) -> Unit) {
        val app = context.applicationContext
        Thread {
            val error = runCatching { push(app) }.getOrElse { it.message ?: "同步失败" }
            onDone(error)
        }.start()
    }

    fun pullMergeAsync(context: Context, onDone: (String?) -> Unit) {
        val app = context.applicationContext
        Thread {
            val error = runCatching { pullMerge(app) }.getOrElse { it.message ?: "同步失败" }
            onDone(error)
        }.start()
    }

    fun register(server: String, username: String, authKey: String) {
        post(server, "/v1/register", JSONObject().put("username", username).put("authKey", authKey), null)
    }

    fun login(server: String, username: String, authKey: String): String {
        val response = post(
            server,
            "/v1/login",
            JSONObject().put("username", username).put("authKey", authKey),
            null,
        )
        return response.getString("token")
    }

    private fun push(context: Context): String? {
        val server = VaultSession.server(context)
        val token = VaultSession.token(context)
        val key = VaultSession.vaultKey(context) ?: return "请先登录账号"
        if (server.isEmpty() || token.isEmpty()) return "请先登录账号"
        val plain = VaultCodec.toJson(VaultStore.load(context)).toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = VaultCrypto.encrypt(key, plain)
        post(
            server,
            "/v1/vault",
            JSONObject().put("iv", iv).put("ciphertext", ciphertext),
            token,
        )
        return null
    }

    private fun pullMerge(context: Context): String? {
        val server = VaultSession.server(context)
        val token = VaultSession.token(context)
        val key = VaultSession.vaultKey(context) ?: return "请先登录账号"
        if (server.isEmpty() || token.isEmpty()) return "请先登录账号"
        val response = get(server, "/v1/vault", token)
        val ciphertext = response.optString("ciphertext")
        val iv = response.optString("iv")
        val remote = if (ciphertext.isBlank() || iv.isBlank()) {
            emptyList()
        } else {
            val plain = VaultCrypto.decrypt(key, iv, ciphertext) ?: return "云端数据无法解密，请确认是这个账号的密码"
            VaultCodec.fromJson(String(plain, Charsets.UTF_8))
        }
        val local = VaultStore.load(context)
        val merged = VaultCodec.merge(local, remote)
        VaultStore.save(context, merged)
        if (merged != remote) {
            val encoded = VaultCodec.toJson(merged).toByteArray(Charsets.UTF_8)
            val (newIv, newCipher) = VaultCrypto.encrypt(key, encoded)
            post(server, "/v1/vault", JSONObject().put("iv", newIv).put("ciphertext", newCipher), token)
        }
        return null
    }

    private fun post(server: String, path: String, body: JSONObject, token: String?): JSONObject {
        val connection = open(server, path, token)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return read(connection)
    }

    private fun get(server: String, path: String, token: String): JSONObject {
        val connection = open(server, path, token)
        connection.requestMethod = "GET"
        return read(connection)
    }

    private fun open(server: String, path: String, token: String?): HttpURLConnection {
        val url = URL(server.trim().trimEnd('/') + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (!token.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token)
        return connection
    }

    private fun read(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (code !in 200..299) {
            throw IllegalStateException(json?.optString("error").orEmpty().ifBlank { "服务器返回 $code" })
        }
        if (json == null || !json.optBoolean("ok", false)) {
            throw IllegalStateException(json?.optString("error").orEmpty().ifBlank { "服务器返回无法识别" })
        }
        return json
    }
}
