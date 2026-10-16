package com.szpdc.assistant.feature

import com.szpdc.assistant.R
import com.szpdc.assistant.feature.random.RandomActivity
import com.szpdc.assistant.feature.scan.ScanActivity

/**
 * 功能注册表 —— 首页菜单的唯一数据源。
 *
 * 加新功能就在这里加一行（顺序即菜单顺序）。保持"一个功能一个 feature 子包"的约定，
 * 功能之间不要互相引用内部实现，公共代码往 common/ 放。
 */
object ToolRegistry {

    val tools: List<Tool> = listOf(
        Tool(
            id = "scan_login",
            titleRes = R.string.tool_scan_login_title,
            descriptionRes = R.string.tool_scan_login_desc,
            entry = ScanActivity::class.java,
        ),
        Tool(
            id = "random_event",
            titleRes = R.string.tool_random_title,
            descriptionRes = R.string.tool_random_desc,
            entry = RandomActivity::class.java,
        ),
        // 后续功能示例：
        // Tool(id = "todo", titleRes = R.string.tool_todo_title,
        //      descriptionRes = R.string.tool_todo_desc, entry = TodoActivity::class.java),
    )
}
