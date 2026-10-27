package com.szpdc.assistant.feature

import android.content.Context

/**
 * 首页宫格的显示开关。没存过的工具默认显示，新加的功能会自己出现在菜单里。
 */
object MenuVisibility {
    private const val PREFS = "menu_visibility"

    fun isShown(context: Context, toolId: String): Boolean =
        prefs(context).getBoolean(toolId, true)

    fun setShown(context: Context, toolId: String, shown: Boolean) {
        prefs(context).edit().putBoolean(toolId, shown).apply()
    }

    /** 设置里拖出来的顺序。没排过的、以及后来新加的工具，按注册表顺序接在后面。 */
    fun orderedTools(context: Context): List<Tool> {
        val saved = prefs(context).getString(KEY_ORDER, null).orEmpty()
        val byId = ToolRegistry.tools.associateBy { it.id }
        val ordered = ArrayList<Tool>()
        for (id in saved.split(',')) {
            val tool = byId[id] ?: continue
            if (ordered.none { it.id == tool.id }) ordered.add(tool)
        }
        for (tool in ToolRegistry.tools) {
            if (ordered.none { it.id == tool.id }) ordered.add(tool)
        }
        return ordered
    }

    fun setOrder(context: Context, ids: List<String>) {
        prefs(context).edit().putString(KEY_ORDER, ids.joinToString(",")).apply()
    }

    fun visibleTools(context: Context): List<Tool> =
        orderedTools(context).filter { isShown(context, it.id) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val KEY_ORDER = "order"
}
