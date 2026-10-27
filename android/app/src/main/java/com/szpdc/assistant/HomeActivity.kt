package com.szpdc.assistant

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.szpdc.assistant.feature.MenuVisibility

/**
 * 首页：两列宫格。显示哪些工具由 [MenuVisibility] 决定，从设置页返回后会重新排。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var toolGrid: GridLayout
    private lateinit var emptyHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        window.statusBarColor = getColor(R.color.surface)
        window.navigationBarColor = getColor(R.color.surface)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        toolGrid = findViewById(R.id.toolGrid)
        emptyHint = findViewById(R.id.emptyHint)
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, MenuSettingsActivity::class.java))
        }
        AppUpdate.check(this)
    }

    override fun onResume() {
        super.onResume()
        bindTools()
    }

    private fun bindTools() {
        toolGrid.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val tools = MenuVisibility.visibleTools(this)
        emptyHint.visibility = if (tools.isEmpty()) View.VISIBLE else View.GONE
        val cardSize = cardSize()

        for (tool in tools) {
            val item = inflater.inflate(R.layout.item_tool, toolGrid, false)
            item.findViewById<ImageView>(R.id.toolIcon).setImageResource(tool.iconRes)
            item.findViewById<TextView>(R.id.toolTitle).setText(tool.titleRes)
            item.setOnClickListener { startActivity(Intent(this, tool.entry)) }
            val params = GridLayout.LayoutParams().apply {
                width = cardSize
                height = cardSize
                setGravity(Gravity.CENTER)
                val gap = (8 * resources.displayMetrics.density).toInt()
                setMargins(gap, gap, gap, gap)
            }
            toolGrid.addView(item, params)
        }
    }

    /** 一行两格，各占一半宽度，高度跟宽度走，多出来的屏幕不往下拉。 */
    private fun cardSize(): Int {
        val density = resources.displayMetrics.density
        val gridPadding = (10 + 10) * density
        val margin = 8 * density
        val available = resources.displayMetrics.widthPixels - gridPadding - margin * 4
        return (available / 2f).toInt()
    }
}
