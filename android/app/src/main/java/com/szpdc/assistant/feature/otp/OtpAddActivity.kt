package com.szpdc.assistant.feature.otp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.szpdc.assistant.R

/** 右上角加号：扫二维码，或手动输入激活码。 */
class OtpAddActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_add)
        window.statusBarColor = getColor(R.color.surface)
        window.navigationBarColor = getColor(R.color.surface)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.scanButton).setOnClickListener {
            scanLauncher.launch(Intent(this, OtpScanActivity::class.java))
        }
        findViewById<View>(R.id.manualButton).setOnClickListener {
            findViewById<View>(R.id.manualForm).visibility = View.VISIBLE
        }
        findViewById<View>(R.id.saveButton).setOnClickListener { saveManual() }
    }

    private fun saveManual() {
        val label = findViewById<EditText>(R.id.labelInput).text.toString()
        val account = findViewById<EditText>(R.id.accountInput).text.toString()
        val rawSecret = findViewById<EditText>(R.id.secretInput).text.toString().trim()
        val pasted = Totp.parse(rawSecret)
        if (pasted != null) {
            save(
                label.ifBlank { pasted.label },
                pasted.issuer,
                account.ifBlank { pasted.account },
                pasted.secret,
                pasted.algorithm,
                pasted.digits,
                pasted.period,
            )
            return
        }
        val secret = Totp.normalizeSecret(rawSecret)
        if (secret == null) {
            Toast.makeText(this, R.string.otp_secret_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        save(label, "", account, secret, "SHA1", 6, 30)
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
        if (VaultSession.isLoggedIn(this)) {
            VaultSync.pushAsync(this) { syncError ->
                if (syncError != null) runOnUiThread {
                    Toast.makeText(this, syncError, Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }
}
