package com.szpdc.assistant.feature.otp

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.szpdc.assistant.CloudConfig
import com.szpdc.assistant.R

/** 绑定账号。密码只在本机派生密钥，服务器收不到令牌原文。 */
class OtpAccountActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_account)
        window.statusBarColor = getColor(R.color.surface)
        window.navigationBarColor = getColor(R.color.surface)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        val savedServer = VaultSession.server(this)
        findViewById<EditText>(R.id.serverInput).setText(savedServer.ifBlank { CloudConfig.BASE_URL })
        findViewById<EditText>(R.id.usernameInput).setText(VaultSession.username(this))
        findViewById<View>(R.id.registerButton).setOnClickListener { submit(register = true) }
        findViewById<View>(R.id.loginButton).setOnClickListener { submit(register = false) }
        findViewById<View>(R.id.logoutButton).setOnClickListener {
            VaultSession.logout(this)
            refreshStatus()
            Toast.makeText(this, R.string.otp_logged_out, Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    private fun submit(register: Boolean) {
        val server = findViewById<EditText>(R.id.serverInput).text.toString().trim().trimEnd('/')
        val username = findViewById<EditText>(R.id.usernameInput).text.toString().trim().lowercase()
        val password = findViewById<EditText>(R.id.passwordInput).text.toString()
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            toast(getString(R.string.otp_server_invalid))
            return
        }
        if (!username.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            toast(getString(R.string.otp_username_invalid))
            return
        }
        if (password.length < 8) {
            toast(getString(R.string.otp_password_short))
            return
        }
        VaultSession.saveServerDraft(this, server, username)
        val keys = VaultCrypto.derive(username, password)
        setBusy(true)
        Thread {
            val error = runCatching {
                if (register) VaultSync.register(server, username, keys.authKey)
                val token = VaultSync.login(server, username, keys.authKey)
                VaultSession.saveLogin(this, server, username, token, keys.vaultKey)
                VaultSync.pullMergeAsync(this) { }
            }.exceptionOrNull()?.message
            runOnUiThread {
                setBusy(false)
                if (error != null) {
                    toast(error)
                } else {
                    toast(getString(if (register) R.string.otp_registered else R.string.otp_logged_in))
                    refreshStatus()
                    finish()
                }
            }
        }.start()
    }

    private fun refreshStatus() {
        val status = findViewById<TextView>(R.id.loginStatus)
        val logout = findViewById<View>(R.id.logoutButton)
        if (VaultSession.isLoggedIn(this)) {
            status.text = getString(R.string.otp_bound, VaultSession.username(this))
            logout.visibility = View.VISIBLE
        } else {
            status.text = getString(R.string.otp_unbound)
            logout.visibility = View.GONE
        }
    }

    private fun setBusy(busy: Boolean) {
        findViewById<View>(R.id.registerButton).isEnabled = !busy
        findViewById<View>(R.id.loginButton).isEnabled = !busy
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
