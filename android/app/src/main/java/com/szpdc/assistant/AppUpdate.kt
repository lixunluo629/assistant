package com.szpdc.assistant

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** 启动时询问云端有没有更新的安装包。下载后交给系统安装。 */
object AppUpdate {
    private var checked = false

    fun currentVersionName(activity: AppCompatActivity): String {
        return activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    }

    fun checkNow(activity: AppCompatActivity, onStatus: (String) -> Unit) {
        val current = versionCode(activity)
        Thread {
            val found = runCatching { fetch(current) }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val info = found.getOrNull()
                when {
                    found.isFailure -> onStatus(activity.getString(R.string.update_failed))
                    info == null -> onStatus(activity.getString(R.string.update_latest))
                    else -> {
                        onStatus(activity.getString(R.string.update_found, info.versionName))
                        show(activity, info)
                    }
                }
            }
        }.start()
    }

    fun check(activity: AppCompatActivity) {
        if (checked) return
        val current = versionCode(activity)
        val skipped = activity.getSharedPreferences(PREFS, AppCompatActivity.MODE_PRIVATE)
            .getInt(KEY_SKIP, 0)
        Thread {
            val info = runCatching { fetch(current) }.getOrNull()
            activity.runOnUiThread {
                if (activity.isDestroyed || info == null) return@runOnUiThread
                if (!info.force && info.versionCode == skipped) return@runOnUiThread
                checked = true
                show(activity, info)
            }
        }.start()
    }

    private fun show(activity: AppCompatActivity, info: Info) {
        val message = info.versionName + "\n\n" + info.changelog
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setCancelable(!info.force)
            .setPositiveButton(R.string.update_now) { _, _ -> install(activity, info) }
        if (!info.force) {
            dialog.setNegativeButton(R.string.update_later) { _, _ ->
                activity.getSharedPreferences(PREFS, AppCompatActivity.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_SKIP, info.versionCode)
                    .apply()
            }
        }
        dialog.show()
    }

    private fun install(activity: AppCompatActivity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            checked = false
            AlertDialog.Builder(activity)
                .setMessage(R.string.update_allow_install)
                .setPositiveButton(R.string.update_now) { _, _ -> openInstallSettings(activity) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val job = Download()
        val content = activity.layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val bar = content.findViewById<ProgressBar>(R.id.updateProgress)
        val label = content.findViewById<TextView>(R.id.updateProgressLabel)
        val hint = content.findViewById<TextView>(R.id.updateProgressHint)
        val title = content.findViewById<TextView>(R.id.updateProgressTitle)
        label.text = activity.getString(R.string.update_progress_known, 0, formatSize(0), "…")
        val progress = AlertDialog.Builder(activity)
            .setView(content)
            .setCancelable(true)
            .create()
        progress.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        progress.setCanceledOnTouchOutside(false)
        progress.setOnShowListener {
            progress.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.86f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        progress.setOnCancelListener {
            job.cancelled = true
            job.connection?.disconnect()
        }
        progress.show()
        Thread {
            val downloaded = runCatching { download(activity, info, job, bar, label) }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (job.cancelled || downloaded.exceptionOrNull() is DownloadCancelled) {
                    if (progress.isShowing) progress.dismiss()
                    return@runOnUiThread
                }
                val file = downloaded.getOrNull()
                if (file == null) {
                    if (progress.isShowing) progress.dismiss()
                    showError(activity, downloaded.exceptionOrNull()?.message ?: activity.getString(R.string.update_failed))
                    return@runOnUiThread
                }
                progress.setCancelable(false)
                title.setText(R.string.update_installing)
                hint.visibility = android.view.View.GONE
                bar.isIndeterminate = false
                bar.progress = 100
                Thread {
                    val launched = runCatching { commitInstall(activity, file) }
                    activity.runOnUiThread {
                        if (progress.isShowing) progress.dismiss()
                        if (launched.isFailure && !activity.isDestroyed) {
                            showError(activity, launched.exceptionOrNull()?.message ?: activity.getString(R.string.update_failed))
                        }
                    }
                }.start()
            }
        }.start()
    }

    private fun openInstallSettings(activity: AppCompatActivity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + activity.packageName),
        )
        if (runCatching { activity.startActivity(intent) }.isFailure) {
            showError(activity, activity.getString(R.string.update_allow_install))
        }
    }

    private fun download(
        activity: AppCompatActivity,
        info: Info,
        job: Download,
        bar: ProgressBar,
        label: TextView,
    ): File {
        if (info.url.isBlank()) throw IllegalStateException(activity.getString(R.string.update_failed))
        if (job.cancelled) throw DownloadCancelled()
        val connection = open(info.url)
        job.connection = connection
        val dir = File(activity.cacheDir, "updates")
        dir.mkdirs()
        val file = File(dir, "update-" + info.versionCode + ".apk")
        try {
            val code = connection.responseCode
            if (job.cancelled) throw DownloadCancelled()
            if (code !in 200..299) {
                throw IllegalStateException(activity.getString(R.string.update_failed) + " (" + code + ")")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var readTotal = 0L
                    var lastPaint = 0L
                    while (true) {
                        if (job.cancelled) throw DownloadCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        readTotal += count
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPaint >= 120 || (total > 0 && readTotal >= total)) {
                            lastPaint = now
                            paintProgress(activity, bar, label, readTotal, total)
                        }
                    }
                }
            }
            if (job.cancelled) throw DownloadCancelled()
            val header = ByteArray(2)
            file.inputStream().use { stream ->
                if (stream.read(header) < 2 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                    file.delete()
                    throw IllegalStateException(activity.getString(R.string.update_bad_file))
                }
            }
            if (info.sha256.isNotEmpty() && !sha256(file).equals(info.sha256, ignoreCase = true)) {
                file.delete()
                throw IllegalStateException(activity.getString(R.string.update_bad_file))
            }
            return file
        } catch (error: Exception) {
            file.delete()
            if (job.cancelled || error is DownloadCancelled) throw DownloadCancelled()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun paintProgress(activity: AppCompatActivity, bar: ProgressBar, label: TextView, read: Long, total: Long) {
        activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            if (total > 0) {
                val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                bar.isIndeterminate = false
                bar.progress = percent
                label.text = activity.getString(
                    R.string.update_progress_known,
                    percent,
                    formatSize(read),
                    formatSize(total),
                )
            } else {
                bar.isIndeterminate = true
                label.text = activity.getString(R.string.update_progress_unknown, formatSize(read))
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 0.1) String.format(Locale.US, "%.1f MB", mb) else (bytes / 1024).toString() + " KB"
    }

    private fun commitInstall(activity: AppCompatActivity, file: File) {
        val context = activity.applicationContext
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base", 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                flags,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun showError(activity: AppCompatActivity, message: String) {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun fetch(current: Int): Info? {
        val connection = open(CloudConfig.BASE_URL + "/v1/app/update?versionCode=" + current + "&package=com.szpdc.assistant")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("update check failed")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("ok") || !json.optBoolean("hasUpdate")) return null
            return Info(
                versionCode = json.optInt("versionCode"),
                versionName = json.optString("versionName"),
                changelog = json.optString("changelog"),
                url = downloadUrl(json.optString("url")),
                sha256 = json.optString("sha256"),
                force = json.optBoolean("force"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadUrl(url: String): String {
        val path = url.substringAfter("://").substringAfter("/")
        return if (path.isEmpty()) url else CloudConfig.BASE_URL + "/" + path
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        return connection
    }

    private fun versionCode(activity: AppCompatActivity): Int {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return code.toInt()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class Download {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
    }

    private class DownloadCancelled : Exception()

    private data class Info(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val url: String,
        val sha256: String,
        val force: Boolean,
    )

    private const val PREFS = "app_update"
    private const val KEY_SKIP = "skip_version"
}
