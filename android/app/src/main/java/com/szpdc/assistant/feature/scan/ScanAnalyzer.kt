package com.szpdc.assistant.feature.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机帧 -> 二维码文本 -> 回调。
 *
 * 约定：
 * - 不缓存帧、不落盘、不上传；帧数据在 ImageProxy.close() 后即失效，回调里不得持有；
 * - busy 闸门保证同一时刻只处理一帧，避免连续解码刷屏；
 * - 解码出的文本只交给回调一次，调用方（LoginRouter）用后即弃。
 */
class ScanAnalyzer(
    private val onDecoded: (String) -> Unit,
    /** 某一帧里没有任何二维码时回调 —— 用来判断"用户把镜头移开了"，是重新允许自动跳转的信号 */
    private val onNothingDetected: () -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
    private val busy = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }

        val mediaImage = image.image
        if (mediaImage == null) {
            busy.set(false)
            image.close()
            return
        }

        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                var found: String? = null
                for (barcode in barcodes) {
                    val value = barcode.rawValue
                    if (!value.isNullOrEmpty()) {
                        found = value
                        break
                    }
                }
                if (found != null) onDecoded(found) else onNothingDetected()
            }
            .addOnCompleteListener {
                busy.set(false)
                image.close()
            }
    }

    fun shutdown() {
        runCatching { scanner.close() }
    }
}
