package com.szpdc.assistant.feature.scan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 本机 App 安装检测（Android 实现）。
 *
 * 关键前提：规则表里的包名必须同步声明在 AndroidManifest 的 <queries> 中。
 * 一旦声明，getPackageInfo 抛 NameNotFoundException 就是可信的"未安装"证据；
 * 未声明时系统会无条件抛该异常，此时"未安装"就是误判 —— 所以这里任何非
 * NameNotFoundException 的异常一律返回 UNKNOWN，由路由层降级为手动提示。
 */
class AndroidAppInstallProbe(private val context: Context) {

    fun isInstalled(rule: PlatformRule): InstallStatus {
        if (rule.androidPackages.isEmpty()) return InstallStatus.UNKNOWN
        val manager = context.packageManager
        var negativeEvidence = false

        for (packageName in rule.androidPackages) {
            try {
                manager.getPackageInfo(packageName, 0)
                return InstallStatus.INSTALLED
            } catch (notFound: PackageManager.NameNotFoundException) {
                negativeEvidence = true
            } catch (throwable: Throwable) {
                // SecurityException / 多用户隔离 / 系统异常：检测能力受限，不能下结论
                return InstallStatus.UNKNOWN
            }
        }

        return if (negativeEvidence) InstallStatus.NOT_INSTALLED else InstallStatus.UNKNOWN
    }

    /** 辅助判断：系统里是否有 App 能处理这个深链（同样依赖 <queries> 声明）。 */
    fun canHandleDeepLink(uri: Uri): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }.getOrDefault(false)
}
