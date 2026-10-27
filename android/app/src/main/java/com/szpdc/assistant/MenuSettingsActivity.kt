package com.szpdc.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.szpdc.assistant.feature.MenuVisibility
import com.szpdc.assistant.feature.Tool
import java.util.Collections

/** 右上角设置：决定首页宫格里哪些工具显示，以及它们的先后顺序。 */
class MenuSettingsActivity : AppCompatActivity() {

    private val tools = ArrayList<Tool>()
    private var orderChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_settings)
        window.statusBarColor = getColor(R.color.surface)
        window.navigationBarColor = getColor(R.color.surface)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        tools.addAll(MenuVisibility.orderedTools(this))
        val list = findViewById<RecyclerView>(R.id.settingList)
        val adapter = SettingAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val drag = if (viewHolder.itemViewType == TYPE_TOOL && tools.size > 1) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN
                } else {
                    0
                }
                return makeMovementFlags(drag, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (adapter.getItemViewType(from) != TYPE_TOOL || adapter.getItemViewType(to) != TYPE_TOOL) return false
                Collections.swap(tools, from, to)
                adapter.notifyItemMoved(from, to)
                orderChanged = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (!orderChanged) return
                orderChanged = false
                MenuVisibility.setOrder(this@MenuSettingsActivity, tools.map { it.id })
            }
        }).attachToRecyclerView(list)
    }

    private inner class SettingAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = tools.size + 1

        override fun getItemViewType(position: Int): Int = if (position < tools.size) TYPE_TOOL else TYPE_UPDATE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            if (viewType == TYPE_UPDATE) {
                val view = inflater.inflate(R.layout.item_check_update, parent, false)
                val status = view.findViewById<TextView>(R.id.updateStatus)
                status.text = getString(R.string.update_current, AppUpdate.currentVersionName(this@MenuSettingsActivity))
                view.setOnClickListener {
                    status.setText(R.string.update_checking)
                    AppUpdate.checkNow(this@MenuSettingsActivity) { status.text = it }
                }
                return UpdateHolder(view)
            }
            return ToolHolder(inflater.inflate(R.layout.item_menu_setting, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is ToolHolder) holder.bind(tools[position])
        }
    }

    private inner class ToolHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.toolIcon)
        private val title: TextView = view.findViewById(R.id.toolTitle)
        private val toggle: SwitchCompat = view.findViewById(R.id.toolVisible)

        fun bind(tool: Tool) {
            icon.setImageResource(tool.iconRes)
            title.setText(tool.titleRes)
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = MenuVisibility.isShown(this@MenuSettingsActivity, tool.id)
            toggle.setOnCheckedChangeListener { _, checked ->
                MenuVisibility.setShown(this@MenuSettingsActivity, tool.id, checked)
            }
            itemView.setOnClickListener { toggle.toggle() }
        }
    }

    private class UpdateHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_TOOL = 0
        const val TYPE_UPDATE = 1
    }
}
