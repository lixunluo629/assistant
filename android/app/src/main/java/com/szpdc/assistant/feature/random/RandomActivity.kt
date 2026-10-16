package com.szpdc.assistant.feature.random

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.szpdc.assistant.R

/**
 * 功能：随机事件 —— 一组"帮我随机决定"的小工具。
 *
 * 界面参考系统随机工具的形态：顶部胶囊标签切换，中间是大号可视元素
 * （骰子 / 硬币可以点、会动），底部一句提示，右下角按钮展开骰子数量。
 *
 * 公平性与展示分离：点数、正反面、抽取结果全部由 [RandomOps] 生成，
 * 这里的动画只负责好看，动画里用的随机数不参与结果。
 */
class RandomActivity : AppCompatActivity() {

    private lateinit var diceView: DiceView
    private lateinit var coinView: CoinView
    private lateinit var diceTotal: TextView
    private lateinit var coinResult: TextView
    private lateinit var coinStats: TextView
    private lateinit var hintText: TextView
    private lateinit var countButton: TextView

    private val tabIds = listOf(R.id.tabDice, R.id.tabCoin, R.id.tabNumber)
    private val panelIds = listOf(R.id.panelDice, R.id.panelCoin, R.id.panelNumber)
    private val hints = listOf(
        R.string.random_hint_dice,
        R.string.random_hint_coin,
        R.string.random_hint_number,
    )

    private var selected = 0
    private var diceCount = 3
    private var rolling = false
    private var flipping = false
    private var coinHead = 0
    private var coinTail = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_random)

        diceView = findViewById(R.id.diceView)
        coinView = findViewById(R.id.coinView)
        diceTotal = findViewById(R.id.diceTotal)
        coinResult = findViewById(R.id.coinResult)
        coinStats = findViewById(R.id.coinStats)
        hintText = findViewById(R.id.hintText)
        countButton = findViewById(R.id.countButton)

        for ((index, id) in tabIds.withIndex()) {
            findViewById<TextView>(id).setOnClickListener { select(index) }
        }
        select(0)

        diceView.setCount(diceCount)
        updateCountButton()
        diceView.setOnClickListener { rollDice() }
        countButton.setOnClickListener { showCountSheet() }

        coinView.setOnClickListener { flipCoin() }
        coinStats.text = getString(R.string.random_coin_stats, 0, 0, 0)

        setupNumber()
    }

    // ---------------- 标签切换 ----------------

    private fun select(index: Int) {
        selected = index
        for ((i, id) in tabIds.withIndex()) {
            findViewById<TextView>(id).isSelected = i == index
        }
        for ((i, id) in panelIds.withIndex()) {
            findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
        }
        hintText.setText(hints[index])
        countButton.visibility = if (index == 0) View.VISIBLE else View.GONE
    }

    // ---------------- 掷骰子 ----------------

    private fun rollDice() {
        if (rolling) return
        rolling = true
        val rolls = RandomOps.rollDice(diceCount, SIDES)
        diceView.animateTo(rolls.toIntArray()) {
            rolling = false
            diceTotal.text = getString(R.string.random_dice_total, rolls.sum())
            settle(diceTotal)
        }
    }

    private fun updateCountButton() {
        countButton.text = getString(R.string.random_dice_count_short, diceCount)
    }

    private fun showCountSheet() {
        val sheet = BottomSheetDialog(this)
        val content = LayoutInflater.from(this).inflate(R.layout.sheet_dice_count, null)
        val grid = content.findViewById<GridLayout>(R.id.countGrid)

        for (n in 1..MAX_DICE) {
            val item = LayoutInflater.from(this)
                .inflate(R.layout.item_dice_count, grid, false) as TextView
            item.text = getString(R.string.random_dice_count_n, n)
            item.isSelected = n == diceCount
            item.setOnClickListener {
                diceCount = n
                diceView.setCount(n)
                diceTotal.text = ""
                updateCountButton()
                sheet.dismiss()
            }
            grid.addView(item)
        }
        sheet.setContentView(content)
        sheet.show()
    }

    // ---------------- 抛硬币 ----------------

    private fun flipCoin() {
        if (flipping) return
        flipping = true
        val head = RandomOps.flipCoins(1).first()
        coinView.animateTo(head) {
            flipping = false
            if (head) coinHead++ else coinTail++
            coinResult.setText(if (head) R.string.random_coin_head else R.string.random_coin_tail)
            coinStats.text = getString(R.string.random_coin_stats, coinHead, coinTail, coinHead + coinTail)
            settle(coinResult)
        }
    }

    // ---------------- 抽数字 ----------------

    private fun setupNumber() {
        val from = findViewById<TextInputEditText>(R.id.numberFrom)
        val to = findViewById<TextInputEditText>(R.id.numberTo)
        val count = findViewById<TextInputEditText>(R.id.numberCount)
        val unique = findViewById<SwitchCompat>(R.id.numberUnique)
        val result = findViewById<TextView>(R.id.numberResult)
        val sub = findViewById<TextView>(R.id.numberResultSub)

        findViewById<Button>(R.id.numberDraw).setOnClickListener {
            val fromValue = from.text?.toString()?.trim()?.toIntOrNull()
            val toValue = to.text?.toString()?.trim()?.toIntOrNull()
            val countValue = count.text?.toString()?.trim()?.toIntOrNull()
            if (fromValue == null || toValue == null || countValue == null) {
                toast(getString(R.string.random_number_invalid))
                return@setOnClickListener
            }
            try {
                val picked = RandomOps.sampleNumbers(fromValue, toValue, countValue, unique.isChecked)
                result.text = picked.joinToString(", ")
                sub.text = getString(R.string.random_number_range, fromValue, toValue, countValue)
                settle(result)
            } catch (error: IllegalArgumentException) {
                result.setText(R.string.random_placeholder)
                sub.text = error.message
            }
        }
    }

    // ---------------- 通用 ----------------

    /** 结果浮现：淡入 + 轻微上移，比直接跳字舒服 */
    private fun settle(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = size(10f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun size(dp: Float): Float = dp * resources.displayMetrics.density

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 骰子固定 6 面 */
        const val SIDES = 6
        const val MAX_DICE = 6
    }
}
