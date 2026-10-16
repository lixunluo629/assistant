package com.szpdc.assistant.feature.random

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 骰子展示与摇动动画（自绘）。
 *
 * 职责边界：这里只负责"画得好看 + 摇得带感"，**结果由调用方用 [RandomOps] 生成后传进来**，
 * 所以动画里用的随机数只是视觉抖动，不参与结果，公平性不受影响。
 *
 * 视觉：白色圆角方块 + 红色点数 + 落地阴影，摇晃时整体抖动并快速闪点，落地时轻微回弹。
 */
class DiceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var count = 3
    private var values = IntArray(count) { 1 }
    private var shownValues = IntArray(count) { 1 }

    /** 0 = 静止，>0 表示正在摇（0..1 的进度由动画驱动） */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** 只用于动画抖动，不影响点数结果 */
    private val shake = Random(0x5EED)

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        facePaint.color = Color.parseColor("#F7F8FA")
        dotPaint.color = Color.parseColor("#E8322D")
        shadowPaint.color = Color.parseColor("#33000000")
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setCount(newCount: Int) {
        if (newCount == count) return
        cancelAnimation()
        count = newCount.coerceIn(1, 6)
        values = IntArray(count) { if (it < values.size) values[it] else 1 }
        shownValues = values.copyOf()
        invalidate()
    }

    fun getValues(): IntArray = values.copyOf()

    /**
     * 摇动到 [target] 指定的点数。动画期间快速闪点，结束时定格并回调。
     */
    fun animateTo(target: IntArray, onEnd: (() -> Unit)? = null) {
        cancelAnimation()
        values = target.copyOf()
        progress = 0f
        val builder = ValueAnimator.ofFloat(0f, 1f)
        builder.duration = ROLL_DURATION_MS
        builder.interpolator = DecelerateInterpolator(1.6f)
        builder.addUpdateListener { animator ->
            progress = animator.animatedValue as Float
            // 前半段快速闪点，后半段逐渐收敛到最终值
            for (i in 0 until count) {
                shownValues[i] = if (progress < 0.65f) {
                    shake.nextInt(6) + 1
                } else {
                    values[i]
                }
            }
            invalidate()
        }
        builder.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                progress = 0f
                shownValues = values.copyOf()
                invalidate()
                onEnd?.invoke()
            }
        })
        animator = builder
        builder.start()
    }

    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rows = layoutRows(count)
        val maxPerRow = rows.max()
        val gap = dp(14f)

        // 骰子尺寸固定，不随数量变化：
        // 只摇 1 个时不会变成巨无霸，摇 6 个时也不会缩成小点。
        // 只有当固定尺寸在当前 View 里真放不下（窄屏 + 3 列）时，才按需等比缩小。
        val fixedSize = dp(74f)
        val widthLimit = (width - gap * (maxPerRow - 1)) / maxPerRow
        val heightLimit = (height - gap * (rows.size - 1)) / rows.size
        val size = min(fixedSize, min(widthLimit, heightLimit)).coerceAtLeast(dp(24f))

        val totalHeight = size * rows.size + gap * (rows.size - 1)
        var y = (height - totalHeight) / 2f

        // 摇动时整体抖动，幅度随进度衰减
        val shakeAmount = sin(progress * Math.PI.toFloat() * 6f) * size * 0.06f * (1f - progress)
        val wobble = progress * (1f - progress) * 4f * 18f // 局部旋转幅度

        var index = 0
        for (rowCount in rows) {
            val rowWidth = size * rowCount + gap * (rowCount - 1)
            var x = (width - rowWidth) / 2f
            for (i in 0 until rowCount) {
                val jitter = if (progress > 0f) {
                    (shake.nextInt(3) - 1) * size * 0.02f * (1f - progress)
                } else {
                    0f
                }
                val rect = RectF(x + jitter, y + shakeAmount + jitter, x + size + jitter, y + size + shakeAmount + jitter)
                drawDie(canvas, rect, shownValues.getOrElse(index) { 1 }, wobble * (if (index % 2 == 0) 1f else -1f))
                x += size + gap
                index++
            }
            y += size + gap
        }
    }

    private fun drawDie(canvas: Canvas, rect: RectF, value: Int, rotation: Float) {
        val radius = rect.width() * 0.24f
        val pivotX = rect.centerX()
        val pivotY = rect.centerY()
        canvas.save()
        canvas.rotate(rotation, pivotX, pivotY)

        // 落地阴影
        shadowPaint.setShadowLayer(rect.width() * 0.09f, 0f, rect.width() * 0.05f, Color.parseColor("#66000000"))
        canvas.drawRoundRect(rect, radius, radius, shadowPaint)
        shadowPaint.clearShadowLayer()

        canvas.drawRoundRect(rect, radius, radius, facePaint)

        val dotRadius = rect.width() * 0.085f
        for ((dx, dy) in DOT_POSITIONS[value] ?: DOT_POSITIONS[1]!!) {
            canvas.drawCircle(
                rect.left + rect.width() * dx,
                rect.top + rect.height() * dy,
                dotRadius,
                dotPaint,
            )
        }
        canvas.restore()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun layoutRows(total: Int): IntArray = when (total.coerceIn(1, 6)) {
        1 -> intArrayOf(1)
        2 -> intArrayOf(2)
        3 -> intArrayOf(1, 2)
        4 -> intArrayOf(2, 2)
        5 -> intArrayOf(2, 3)
        else -> intArrayOf(3, 3)
    }

    private companion object {
        const val ROLL_DURATION_MS = 520L

        /** 标准骰子点位（相对坐标） */
        val DOT_POSITIONS: Map<Int, List<Pair<Float, Float>>> = mapOf(
            1 to listOf(0.5f to 0.5f),
            2 to listOf(0.28f to 0.28f, 0.72f to 0.72f),
            3 to listOf(0.28f to 0.28f, 0.5f to 0.5f, 0.72f to 0.72f),
            4 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.28f to 0.72f, 0.72f to 0.72f),
            5 to listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.5f to 0.5f, 0.28f to 0.72f, 0.72f to 0.72f),
            6 to listOf(0.28f to 0.26f, 0.72f to 0.26f, 0.28f to 0.5f, 0.72f to 0.5f, 0.28f to 0.74f, 0.72f to 0.74f),
        )
    }
}
