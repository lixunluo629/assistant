package com.szpdc.assistant.feature.scan

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.szpdc.assistant.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 功能：扫码登录助手。
 *
 * 流程：扫 PC 登录二维码 -> 识别平台 -> 按 Decision.autoLaunch 自动打开对应官方 App
 * （钉钉 / QQ / 支付宝可直接进扫一扫）-> 用户在官方 App 内手动确认登录。
 *
 * 只有 OPEN_APP / PROMPT_DOWNLOAD 会自动执行；REJECT / MANUAL_SCAN 一定停下来让用户看清提示。
 * 本 Activity 全程不发起网络请求（App 未申请 INTERNET 权限）。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var rules: RuleSet
    private lateinit var probe: AndroidAppInstallProbe
    private lateinit var analysisExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var analyzer: ScanAnalyzer? = null
    private var currentDecision: Decision? = null
    private var lastHandledFingerprint: String? = null

    /**
     * 是否允许把当前这一帧的结果当作"新的一次扫码"。
     *
     * 跳转类动作执行后置 false：只有等画面里先出现"没有二维码"（用户把镜头移开），
     * 才重新允许触发。否则从官方 App 返回时手机还对着同一张码，会立刻又被跳走。
     */
    private var readyForNextCode = true

    private lateinit var previewView: PreviewView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var stepsView: TextView
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var rescanButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else showCameraDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        previewView = findViewById(R.id.previewView)
        titleView = findViewById(R.id.resultTitle)
        detailView = findViewById(R.id.resultDetail)
        stepsView = findViewById(R.id.resultSteps)
        primaryButton = findViewById(R.id.primaryButton)
        secondaryButton = findViewById(R.id.secondaryButton)
        rescanButton = findViewById(R.id.rescanButton)

        // 规则表来自 assets/platform_rules.json，与 Python 核心库同源
        rules = RuleSet.fromAssets(this)
        probe = AndroidAppInstallProbe(this)
        analysisExecutor = Executors.newSingleThreadExecutor()

        primaryButton.setOnClickListener { performPrimaryAction() }
        secondaryButton.setOnClickListener { performSecondaryAction() }
        rescanButton.setOnClickListener { requestScanAgain() }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analyzer?.shutdown()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    // ---- 相机 ----------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull()
            if (provider == null) {
                toast(getString(R.string.error_no_camera))
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also { useCase ->
                useCase.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase = analysis
            bindAnalyzer(analysis)

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                toast(getString(R.string.error_no_camera))
            }
            resetResultCard()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindAnalyzer(useCase: ImageAnalysis) {
        val scanner = analyzer ?: ScanAnalyzer(
            onDecoded = { raw -> onDecoded(raw) },
            onNothingDetected = { onNothingDetected() },
        ).also { analyzer = it }
        useCase.setAnalyzer(analysisExecutor, scanner)
    }

    private fun pauseScanning() {
        analysisUseCase?.clearAnalyzer()
    }

    private fun requestScanAgain() {
        if (!hasCameraPermission()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (cameraProvider == null) {
            startCamera()
            return
        }
        // 用户主动点「重新扫描」：无条件允许，包括同一张码
        lastHandledFingerprint = null
        readyForNextCode = true
        analysisUseCase?.let { useCase -> bindAnalyzer(useCase) }
        resetResultCard()
    }

    // ---- 扫码结果 ------------------------------------------------------

    private fun onDecoded(raw: String) {
        // 上一轮结果已处理过、且用户还没把镜头移开：忽略，避免返回后立刻又被跳走
        if (!readyForNextCode) return

        // 走完整条决策链路后，原文已不再需要：Decision 里只有平台枚举、布尔标记和不可逆指纹
        val decision = LoginRouter.route(
            raw,
            rules,
            installStatusOf = { rule -> probe.isInstalled(rule) },
        )
        if (decision.fingerprint.isNotEmpty() && decision.fingerprint == lastHandledFingerprint) {
            return // 同一张二维码不重复弹结果
        }
        lastHandledFingerprint = decision.fingerprint
        readyForNextCode = false
        pauseScanning()
        render(decision)

        // 用户要求：扫码后直接跳过去，不用再点一次按钮。
        // 延后一点执行，让结果卡片先显示出来，用户知道刚才发生了什么。
        if (decision.autoLaunch) {
            primaryButton.postDelayed({ performPrimaryAction() }, AUTO_LAUNCH_DELAY_MS)
        }
    }

    /**
     * 从官方 App 返回时自动恢复扫描，方便接着扫下一张。
     *
     * 注意不清空 lastHandledFingerprint：否则手机还对着同一张码时会再次触发自动跳转，
     * 形成"一回来就被跳走"的死循环。想重扫同一张码，点「重新扫描」按钮即可。
     */
    override fun onResume() {
        super.onResume()
        // 从官方 App / 商店返回：界面直接回到可重新扫描的状态，不留上一次的结果提示。
        if (currentDecision != null && cameraProvider != null && hasCameraPermission()) {
            // 指纹清空，允许重扫同一张码；但要求先把镜头移开一次（见 readyForNextCode 的说明）
            lastHandledFingerprint = null
            readyForNextCode = false
            resetResultCard()
            analysisUseCase?.let { useCase -> bindAnalyzer(useCase) }
        }
    }

    private fun onNothingDetected() {
        // 画面里没有二维码 —— 视为用户已把镜头移开，允许下一次触发
        readyForNextCode = true
    }

    private fun render(decision: Decision) {
        currentDecision = decision
        titleView.text = decision.title

        // 会自动跳转的动作不需要在扫码页铺开提示：用户马上就被带走了，
        // 返回时界面会自动回到"重新扫描"状态（见 onResume）。
        if (decision.autoLaunch) {
            detailView.text = decision.detail
            stepsView.text = ""
            primaryButton.visibility = View.GONE
            secondaryButton.visibility = View.GONE
            rescanButton.visibility = View.GONE
            return
        }
        detailView.text = decision.detail
        stepsView.text = decision.steps
            .mapIndexed { index, step -> (index + 1).toString() + ". " + step }
            .joinToString("\n")

        primaryButton.visibility = View.GONE
        secondaryButton.visibility = View.GONE

        when (decision.action) {
            Action.OPEN_APP -> {
                primaryButton.setText(R.string.action_open_app)
                primaryButton.visibility = View.VISIBLE
            }
            Action.PROMPT_DOWNLOAD -> {
                primaryButton.setText(R.string.action_download)
                primaryButton.visibility = View.VISIBLE
            }
            Action.PASSWORD_LOGIN -> {
                primaryButton.setText(R.string.action_open_app)
                primaryButton.visibility = View.VISIBLE
            }
            Action.MANUAL_SCAN, Action.REJECT -> {
                primaryButton.visibility = View.GONE
            }
        }

        if (decision.action != Action.PASSWORD_LOGIN && !decision.fallbackUrl.isNullOrEmpty()) {
            secondaryButton.visibility = View.VISIBLE
        }
        rescanButton.visibility = View.VISIBLE
    }

    private fun resetResultCard() {
        currentDecision = null
        titleView.setText(R.string.hint_scanning)
        detailView.text = ""
        stepsView.text = ""
        primaryButton.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        rescanButton.visibility = View.GONE
    }

    private fun showCameraDenied() {
        currentDecision = null
        titleView.setText(R.string.app_name)
        detailView.setText(R.string.error_camera_permission)
        stepsView.text = ""
        primaryButton.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        rescanButton.visibility = View.VISIBLE
    }

    // ---- 动作 ----------------------------------------------------------

    private fun performPrimaryAction() {
        val decision = currentDecision ?: return
        when (decision.action) {
            Action.OPEN_APP -> launchOfficialApp(decision)
            Action.PROMPT_DOWNLOAD -> openUri(decision.storeUrl, decision.fallbackUrl)
            Action.PASSWORD_LOGIN -> openUri(decision.fallbackUrl)
            Action.MANUAL_SCAN, Action.REJECT -> requestScanAgain()
        }
    }

    private fun performSecondaryAction() {
        val decision = currentDecision ?: return
        openUri(decision.fallbackUrl ?: decision.storeUrl)
    }

    /**
     * 唤起官方 App，顺序：扫一扫深链 → 包名启动 → 裸 scheme → 商店/网页 → 手动提示。
     *
     * 为什么这么排：
     * 1) 能直达扫一扫的平台，用户少点一步（实测目前只有钉钉做到了）；
     * 2) 包名是安装检测用的那一份，最可靠；裸 scheme 是否注册由各 App 自己决定
     *    （钉钉全部 filter 都带 host，所以裸 dingtalk:// 解析不到任何组件）；
     * 3) 逐级兜底保证任何一环失效时用户都不会卡在报错上。
     *
     * 传入的深链与包名都来自 Decision（即规则表常量），二维码内容一律不参与。
     */
    private fun launchOfficialApp(decision: Decision) {
        // 1) 扫一扫深链（钉钉 / QQ / 支付宝 / B站 / 百度 / 小红书 / 抖音等）
        for (scanLink in decision.scanDeepLinks) {
            if (tryOpenUri(scanLink)) return
        }
        // 2) 扫一扫 Activity 类名：有些平台（如百度）把扫码界面声明为 exported=true 却没有 scheme，
        //    只能显式指定组件启动。类名同样是规则表常量，失败会继续往下兜底。
        for (packageName in decision.androidPackages) {
            for (className in decision.scanActivityClasses) {
                val explicit = Intent().setClassName(packageName, className)
                if (tryStart(explicit)) return
            }
        }
        // 3) 包名启动：与安装检测同源，最可靠
        for (packageName in decision.androidPackages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: continue
            if (tryStart(launchIntent)) return
        }
        // 4) 裸 scheme / 商店 / 网页登录，逐级兜底
        if (tryOpenUri(decision.deepLink)) return
        if (tryOpenUri(decision.storeUrl)) return
        if (tryOpenUri(decision.fallbackUrl)) return

        toast(getString(R.string.hint_open_app_manually, decision.platform.displayName))
    }

    private fun openUri(uriText: String?, fallbackUri: String? = null) {
        if (tryOpenUri(uriText)) return
        if (fallbackUri != null && fallbackUri != uriText && tryOpenUri(fallbackUri)) return
        toast(getString(R.string.error_cannot_open_app))
    }

    private fun tryOpenUri(uriText: String?): Boolean {
        if (uriText.isNullOrEmpty()) return false
        return tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(uriText)))
    }

    private fun tryStart(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (error: Exception) {
            // 只记动作与异常类型，不记任何来自二维码的内容
            Log.d(TAG, "startActivity 失败：" + intent.action + " / " + error.javaClass.simpleName)
            false
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "Assistant.Scan"

        /** 自动跳转前的短暂延迟：足够看到结果卡片，又不至于让人等 */
        const val AUTO_LAUNCH_DELAY_MS = 350L
    }
}
