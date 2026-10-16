package com.szpdc.assistant

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.szpdc.assistant.feature.ToolRegistry

/**
 * 首页：功能菜单。
 *
 * 菜单项全部来自 [ToolRegistry]，这里不写任何具体功能名 —— 新增功能不用动这个文件。
 * 功能少（个位数）用 LinearLayout 动态填充就够了，没必要上 RecyclerView。
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val container = findViewById<LinearLayout>(R.id.toolList)
        val inflater = LayoutInflater.from(this)

        for (tool in ToolRegistry.tools) {
            val item = inflater.inflate(R.layout.item_tool, container, false)
            item.findViewById<TextView>(R.id.toolTitle).setText(tool.titleRes)
            item.findViewById<TextView>(R.id.toolDescription).setText(tool.descriptionRes)
            item.setOnClickListener {
                startActivity(Intent(this, tool.entry))
            }
            container.addView(item)
        }
    }
}
