package com.szpdc.assistant.feature.otp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.szpdc.assistant.R
import java.util.Collections
import kotlin.math.abs

/** 已添加的动态令牌。点验证码复制，点名称改标签，长按拖动排序，左滑后点删除。 */
class OtpActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshCodes()
            handler.postDelayed(this, 1000)
        }
    }
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(OtpScanActivity.EXTRA_RAW).orEmpty()
        if (raw.isEmpty()) return@registerForActivityResult
        val parsed = Totp.parse(raw)
        if (parsed == null) {
            Toast.makeText(this, R.string.otp_qr_invalid, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        save(parsed.label, parsed.issuer, parsed.account, parsed.secret, parsed.algorithm, parsed.digits, parsed.period)
    }

    private lateinit var tokenList: RecyclerView
    private lateinit var adapter: TokenAdapter
    private lateinit var rowSwipe: RowSwipe
    private var orderChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp)
        window.statusBarColor = getColor(R.color.surface)
        window.navigationBarColor = getColor(R.color.surface)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.accountButton).setOnClickListener {
            startActivity(Intent(this, OtpAccountActivity::class.java))
        }
        findViewById<View>(R.id.addButton).setOnClickListener { showAddMenu(it) }

        tokenList = findViewById(R.id.tokenList)
        tokenList.layoutManager = LinearLayoutManager(this)
        adapter = TokenAdapter()
        tokenList.adapter = adapter
        rowSwipe = RowSwipe(tokenList, dp(88))
        tokenList.addOnItemTouchListener(rowSwipe)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val drag = if (adapter.tokens.size > 1) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
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
                Collections.swap(adapter.tokens, from, to)
                adapter.notifyItemMoved(from, to)
                orderChanged = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) rowSwipe.closeOpen()
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (!orderChanged) return
                orderChanged = false
                VaultStore.reorder(this@OtpActivity, adapter.tokens.map { it.id })
                sync()
            }
        }).attachToRecyclerView(tokenList)
    }

    override fun onResume() {
        super.onResume()
        bindAccountStatus()
        reload()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (VaultSession.isLoggedIn(this)) {
            VaultSync.pullMergeAsync(this) { error ->
                runOnUiThread {
                    if (error == null) reload()
                }
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun bindAccountStatus() {
        val status = findViewById<TextView>(R.id.accountStatus)
        status.text = if (VaultSession.isLoggedIn(this)) {
            getString(R.string.otp_bound, VaultSession.username(this))
        } else {
            getString(R.string.otp_unbound)
        }
    }

    private fun reload() {
        rowSwipe.closeOpen()
        adapter.tokens.clear()
        adapter.tokens.addAll(VaultStore.visible(this))
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.emptyHint).visibility =
            if (adapter.tokens.isEmpty()) View.VISIBLE else View.GONE
        refreshCodes()
    }

    private fun refreshCodes() {
        val now = System.currentTimeMillis()
        for (index in 0 until tokenList.childCount) {
            val holder = tokenList.getChildViewHolder(tokenList.getChildAt(index)) as TokenAdapter.Holder
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) continue
            holder.showCode(adapter.tokens[position], now)
        }
    }

    private fun showAddMenu(anchor: View) {
        val content = layoutInflater.inflate(R.layout.popup_otp_add, null)
        val popup = PopupWindow(content, dp(188), WindowManager.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dp(6).toFloat()
        popup.isOutsideTouchable = true
        content.findViewById<View>(R.id.scanItem).setOnClickListener {
            popup.dismiss()
            scanLauncher.launch(Intent(this, OtpScanActivity::class.java))
        }
        content.findViewById<View>(R.id.manualItem).setOnClickListener {
            popup.dismiss()
            promptSecret()
        }
        popup.showAsDropDown(anchor, 0, dp(4), Gravity.END)
    }

    private fun promptSecret() {
        showInputDialog(getString(R.string.otp_manual), "", getString(R.string.otp_secret)) { saveSecret(it) }
    }

    private fun saveSecret(rawSecret: String) {
        val pasted = Totp.parse(rawSecret)
        if (pasted != null) {
            save(pasted.label, pasted.issuer, pasted.account, pasted.secret, pasted.algorithm, pasted.digits, pasted.period)
            return
        }
        val secret = Totp.normalizeSecret(rawSecret)
        if (secret == null) {
            Toast.makeText(this, R.string.otp_secret_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        save("", "", "", secret, "SHA1", 6, 30)
    }

    private fun save(
        label: String,
        issuer: String,
        account: String,
        secret: String,
        algorithm: String,
        digits: Int,
        period: Int,
    ) {
        val token = OtpToken.create(label, issuer, account, secret, algorithm, digits, period)
        val error = VaultStore.add(this, token)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            return
        }
        reload()
        sync()
    }

    private fun showInputDialog(title: String, initial: String, hint: String, onSave: (String) -> Unit) {
        val content = layoutInflater.inflate(R.layout.dialog_otp_input, null)
        val dialog = AlertDialog.Builder(this).setView(content).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        content.findViewById<TextView>(R.id.dialogTitle).text = title
        val input = content.findViewById<EditText>(R.id.dialogInput)
        input.hint = hint
        input.setText(initial)
        input.setSelection(input.text.length)
        content.findViewById<View>(R.id.dialogCancel).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.dialogConfirm).setOnClickListener {
            dialog.dismiss()
            onSave(input.text.toString().trim())
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun rename(token: OtpToken) {
        showInputDialog(getString(R.string.otp_label), token.displayName(), getString(R.string.otp_label)) {
            VaultStore.rename(this, token.id, it)
            reload()
            sync()
        }
    }

    private fun confirmDelete(token: OtpToken) {
        val content = layoutInflater.inflate(R.layout.dialog_otp_input, null)
        val dialog = AlertDialog.Builder(this).setView(content).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        content.findViewById<TextView>(R.id.dialogTitle).setText(R.string.otp_delete)
        content.findViewById<TextView>(R.id.dialogMessage).apply {
            text = getString(R.string.otp_delete_confirm, token.displayName())
            visibility = View.VISIBLE
        }
        content.findViewById<View>(R.id.dialogInput).visibility = View.GONE
        content.findViewById<TextView>(R.id.dialogConfirm).setText(R.string.otp_delete)
        content.findViewById<View>(R.id.dialogCancel).setOnClickListener { dialog.cancel() }
        dialog.setOnCancelListener { rowSwipe.closeOpen() }
        content.findViewById<View>(R.id.dialogConfirm).setOnClickListener {
            dialog.setOnCancelListener(null)
            dialog.dismiss()
            val index = adapter.tokens.indexOfFirst { it.id == token.id }
            if (index >= 0) {
                adapter.tokens.removeAt(index)
                adapter.notifyItemRemoved(index)
            }
            rowSwipe.openId = null
            VaultStore.remove(this, token.id)
            findViewById<View>(R.id.emptyHint).visibility =
                if (adapter.tokens.isEmpty()) View.VISIBLE else View.GONE
            sync()
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun copyCode(text: String) {
        val plain = text.replace(" ", "")
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("otp", plain))
        Toast.makeText(this, R.string.otp_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sync() {
        if (!VaultSession.isLoggedIn(this)) return
        VaultSync.pushAsync(this) { error ->
            if (error != null) runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class TokenAdapter : RecyclerView.Adapter<TokenAdapter.Holder>() {
        val tokens = ArrayList<OtpToken>()

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.item_otp_token, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(tokens[position])
        }

        override fun getItemCount(): Int = tokens.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val card: View = view.findViewById(R.id.tokenCard)
            private val name: TextView = view.findViewById(R.id.tokenName)
            private val detail: TextView = view.findViewById(R.id.tokenDetail)
            private val code: TextView = view.findViewById(R.id.tokenCode)
            private val remain: TextView = view.findViewById(R.id.tokenRemain)
            private val delete: View = view.findViewById(R.id.tokenDelete)
            var tokenId: String = ""

            fun bind(token: OtpToken) {
                tokenId = token.id
                card.translationX = if (rowSwipe.openId == token.id) -rowSwipe.reveal.toFloat() else 0f
                name.text = token.displayName()
                val extra = listOf(token.issuer, token.account).filter { it.isNotBlank() }.joinToString(" · ")
                detail.text = extra
                detail.visibility = if (extra.isBlank() || extra == token.displayName()) View.GONE else View.VISIBLE
                name.setOnClickListener { tokenAt()?.let { rename(it) } }
                card.setOnClickListener { tokenAt()?.let { rename(it) } }
                code.setOnClickListener { copyCode(code.text.toString()) }
                delete.setOnClickListener { tokenAt()?.let { confirmDelete(it) } }
                showCode(token, System.currentTimeMillis())
            }

            fun showCode(token: OtpToken, now: Long) {
                code.text = Totp.formatCode(Totp.code(token.secret, token.algorithm, token.digits, token.period, now))
                remain.text = getString(R.string.otp_remain, Totp.remainingSeconds(token.period, now))
            }

            private fun tokenAt(): OtpToken? = adapter.tokens.firstOrNull { it.id == tokenId }
        }
    }

    /** 左滑卡片，露出右侧删除。点删除后再弹确认框。 */
    private inner class RowSwipe(
        private val recycler: RecyclerView,
        val reveal: Int,
    ) : RecyclerView.OnItemTouchListener {
        var openId: String? = null
        private val slop = ViewConfiguration.get(recycler.context).scaledTouchSlop
        private var tracking: TokenAdapter.Holder? = null
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var swiping = false
        private var closingTap = false

        fun closeOpen() {
            val id = openId ?: return
            openId = null
            for (index in 0 until recycler.childCount) {
                val holder = recycler.getChildViewHolder(recycler.getChildAt(index)) as TokenAdapter.Holder
                if (holder.tokenId == id) {
                    holder.card.animate().translationX(0f).setDuration(160).start()
                }
            }
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val child = rv.findChildViewUnder(event.x, event.y)
                    val holder = child?.let { rv.getChildViewHolder(it) as? TokenAdapter.Holder }
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    swiping = false
                    closingTap = false
                    tracking = holder
                    if (holder == null) {
                        if (openId != null) closeOpen()
                        return false
                    }
                    if (openId != null && holder.tokenId != openId) {
                        closeOpen()
                        tracking = null
                        return true
                    }
                    if (holder.tokenId == openId && event.x < holder.card.right + holder.card.translationX) {
                        closingTap = true
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val holder = tracking ?: return false
                    if (swiping) return true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        swiping = true
                        lastX = event.x
                        holder.card.translationX = dx.coerceIn(-reveal.toFloat(), 0f)
                        rv.parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracking = null
                    return false
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
            val holder = tracking
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (holder == null || !swiping) return
                    val dx = holder.card.translationX + (event.x - lastX)
                    holder.card.translationX = dx.coerceIn(-reveal.toFloat(), 0f)
                    lastX = event.x
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (closingTap && event.actionMasked == MotionEvent.ACTION_UP) {
                        closeOpen()
                    } else if (holder != null && swiping) {
                        val open = holder.card.translationX <= -reveal / 2f
                        val target = if (open) -reveal.toFloat() else 0f
                        holder.card.animate().translationX(target).setDuration(160).start()
                        openId = if (open) holder.tokenId else null
                    }
                    swiping = false
                    closingTap = false
                    tracking = null
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }
}
