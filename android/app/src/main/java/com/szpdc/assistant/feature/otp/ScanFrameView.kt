package com.szpdc.assistant.feature.otp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.szpdc.assistant.R

/** 扫码预览上的方框，用来提示把二维码放进这个区域。 */
class ScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val mask = Paint().apply { color = 0xB30F1420.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = context.getColor(R.color.menu_accent)
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.on_surface)
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
    }
    private val hint = context.getString(R.string.otp_scan_frame)

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height) * 0.68f
        val left = (width - side) / 2f
        val top = (height - side) / 2f - dp(12f)
        val frame = RectF(left, top, left + side, top + side)
        val radius = dp(18f)
        val hole = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(frame, radius, radius, Path.Direction.CW)
        }
        canvas.drawPath(hole, mask)
        canvas.drawRoundRect(frame, radius, radius, stroke)
        canvas.drawText(hint, width / 2f, frame.bottom + dp(36f), label)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
