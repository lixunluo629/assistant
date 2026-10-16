package com.szpdc.assistant.feature.random

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.szpdc.assistant.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 硬币展示与翻转动画。
 *
 * 币面用的是贴图（drawable-nodpi/coin_heads.png、coin_tails.png）：金属质感与浮雕是
 * 由 tools/coin-make.py 离线渲染好的，Canvas 手绘达不到那个效果。这里只负责
 * "上抛弧线 + 横向压缩翻转"，图案本身不参与随机，结果由调用方用 [RandomOps] 生成后传入。
 *
 * 翻转用 |cos(角度)| 做横向压缩模拟 3D；角度落在半圈之间时切换到另一面，
 * 因此视觉上翻过去是哪一面，结果就是哪一面。
 */
class CoinView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    private val headsBitmap: Bitmap? =
        BitmapFactory.decodeResource(resources, R.drawable.coin_heads)
    private val tailsBitmap: Bitmap? =
        BitmapFactory.decodeResource(resources, R.drawable.coin_tails)

    private var heads = true          // 当前朝上的是否为正面（字面）
    private var startHeads = true
    private var targetHeads = true
    private var angle = 0f            // 已转过的角度
    private var totalAngle = 0f
    private var progress = 0f         // 0..1，用于上抛弧线
    private var animator: ValueAnimator? = null

    fun isHeads(): Boolean = heads

    fun setHeads(value: Boolean) {
        heads = value
        startHeads = value
        invalidate()
    }

    /** 抛一次，翻到 [targetHeads]。结果与当前面相同时多转一圈（视觉上也确实翻过了）。 */
    fun animateTo(targetHeads: Boolean, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        startHeads = heads
        this.targetHeads = targetHeads
        totalAngle = if (targetHeads == heads) 720f else 540f
        angle = 0f

        val builder = ValueAnimator.ofFloat(0f, totalAngle)
        builder.duration = FLIP_DURATION_MS
        builder.interpolator = DecelerateInterpolator(1.5f)
        builder.addUpdateListener { animator ->
            angle = animator.animatedValue as Float
            progress = (angle / totalAngle).coerceIn(0f, 1f)
            invalidate()
        }
        builder.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                heads = targetHeads
                startHeads = targetHeads
                angle = 0f
                progress = 0f
                invalidate()
                onEnd?.invoke()
            }
        })
        animator = builder
        builder.start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        val radians = Math.toRadians(angle.toDouble())
        val cosValue = cos(radians)
        val compress = abs(cosValue).toFloat().coerceAtLeast(0.06f)
        val showHeads = if (cosValue >= 0) startHeads else !startHeads

        // 上抛弧线：翻到一半时最高
        val liftMax = size * 0.15f
        val lift = sin(progress * Math.PI).toFloat() * liftMax
        val heightRatio = lift / (liftMax + 0.001f)

        // 硬币下方的投影：升得越高越小越淡
        paint.color = Color.BLACK
        paint.alpha = (70 * (1f - 0.5f * heightRatio)).toInt().coerceIn(0, 255)
        canvas.drawOval(
            cx - size * 0.30f * (1f - 0.18f * heightRatio),
            cy + size * 0.36f,
            cx + size * 0.30f * (1f - 0.18f * heightRatio),
            cy + size * 0.44f,
            paint,
        )

        val bitmap = if (showHeads) headsBitmap else tailsBitmap
        canvas.save()
        canvas.translate(0f, -lift)
        canvas.scale(compress, 1f, cx, cy)
        // 转到侧面时略微变暗，像真的翻过去了
        paint.alpha = (120 + 135 * compress).toInt().coerceIn(0, 255)
        rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, rect, paint)
        } else {
            paint.color = Color.GRAY
            canvas.drawOval(rect, paint)
        }
        canvas.restore()
    }

    private companion object {
        const val FLIP_DURATION_MS = 640L
    }
}
