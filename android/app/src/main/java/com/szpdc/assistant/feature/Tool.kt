package com.szpdc.assistant.feature

import android.app.Activity

/**
 * 一个工具/功能项。
 *
 * 新增功能只需两步：
 *   1. 在 feature/<功能名>/ 下实现自己的 Activity（自包含，别依赖别的功能的内部实现）；
 *   2. 在 [ToolRegistry.tools] 里加一行（含图标），并在 AndroidManifest.xml 注册该 Activity。
 * 首页会自动渲染，不用改 HomeActivity。默认出现在宫格里，用户可在设置中隐藏。
 */
data class Tool(
    /** 稳定标识，菜单显示开关按这个 id 记住 */
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    /** 首页宫格上的图标 */
    val iconRes: Int,
    /** 点击后进入的 Activity */
    val entry: Class<out Activity>,
)
