package com.szpdc.assistant.feature.otp

import android.content.Context
import java.io.File

/** 本机令牌库。云端只存密文，这里是卸载前的本地副本。 */
object VaultStore {
    private const val FILE = "otp-vault.json"

    fun load(context: Context): List<OtpToken> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching { VaultCodec.fromJson(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    fun save(context: Context, tokens: List<OtpToken>) {
        File(context.filesDir, FILE).writeText(VaultCodec.toJson(tokens), Charsets.UTF_8)
    }

    fun add(context: Context, token: OtpToken): String? {
        val current = load(context).toMutableList()
        if (current.any { !it.deleted && it.secret == token.secret }) return "这个令牌已经添加过"
        val sort = (current.filter { !it.deleted }.maxOfOrNull { it.sort } ?: -1) + 1
        current.add(token.copy(sort = sort))
        save(context, current)
        return null
    }

    fun reorder(context: Context, orderedIds: List<String>) {
        val rank = orderedIds.withIndex().associate { it.value to it.index }
        val now = System.currentTimeMillis()
        val next = load(context).map { token ->
            val sort = rank[token.id]
            if (sort == null || sort == token.sort) token else token.copy(sort = sort, updatedAt = now)
        }
        save(context, next)
    }

    fun rename(context: Context, id: String, label: String) {
        val next = load(context).map {
            if (it.id == id) it.copy(label = label.trim(), updatedAt = System.currentTimeMillis()) else it
        }
        save(context, next)
    }

    fun visible(context: Context): List<OtpToken> =
        load(context).filter { !it.deleted }.sortedBy { it.sort }

    fun remove(context: Context, id: String) {
        val next = load(context).map {
            if (it.id == id) it.copy(deleted = true, updatedAt = System.currentTimeMillis()) else it
        }
        save(context, next)
    }
}
