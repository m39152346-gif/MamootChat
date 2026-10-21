package ir.mamoot.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import ir.mamoot.app.util.UiUtil
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads

/**
 * Blocking recovery screen shown when stored identity key material can no
 * longer be read (audit F5). The app never silently regenerates and
 * republishes an identity on its own; the user either restores an
 * encrypted backup or explicitly accepts a brand-new key.
 */
class RecoveryActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.api.session == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_recovery)
        findViewById<Button>(R.id.btnRecoverBackup).setOnClickListener { launchOpenDocument() }
        findViewById<Button>(R.id.btnRecoverNewKey).setOnClickListener { confirmNewKey() }
    }

    @Deprecated("back is disabled on the blocking recovery screen")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun launchOpenDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(intent, REQ_OPEN_DOC)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN_DOC || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Threads.runIo {
            try {
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IllegalStateException("empty")
                Threads.runUi { if (!isFinishing) promptPassphrase(text) }
            } catch (e: Exception) {
                Threads.runUi { if (!isFinishing) toast("خواندن فایل پشتیبان ناموفق بود.") }
            }
        }
    }

    private fun promptPassphrase(blob: String) {
        val pass = EditText(this).apply {
            hint = "گذرواژه پشتیبان"
            // Never echo a backup passphrase in clear text while typing.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.text2))
            textSize = 16f
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiUtil.dp(this@RecoveryActivity, 24), UiUtil.dp(this@RecoveryActivity, 12), UiUtil.dp(this@RecoveryActivity, 24), 0)
            addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle("بازیابی پشتیبان")
            .setMessage("گذرواژه‌ای را که هنگام ساخت پشتیبان گذاشتید وارد کنید. کلید هویت و تاریخچه از داخل فایل برمی‌گردد؛ نشست‌های امن هنگام باز کردن هر چت از نو ساخته می‌شوند.")
            .setView(container)
            .setNegativeButton("لغو", null)
            .setPositiveButton("بازیابی") { _, _ ->
                val progress = ProgressDialog(this).apply {
                    setMessage("در حال رمزگشایی پشتیبان… (ممکن است چند ثانیه طول بکشد)")
                    setCancelable(false)
                    show()
                }
                app.repo.restoreEncryptedBackup(blob, pass.text.toString()) { result ->
                    progress.dismiss()
                    result.onSuccess {
                        toast("بازیابی انجام شد.")
                        goMain()
                    }.onFailure { e ->
                        toast(e.message ?: "بازیابی ناموفق بود")
                    }
                }
            }
            .show()
    }

    private fun confirmNewKey() {
        AlertDialog.Builder(this)
            .setTitle("ساخت کلید تازه؟")
            .setMessage("یک کلید هویت تازه ساخته و روی نمایه‌تان منتشر می‌شود. چت‌های امن قبلی روی این گوشی دیگر خوانده نمی‌شوند و مخاطب‌ها اخطار «تغییر کلید» می‌بینند. این کار برنمی‌گردد. ادامه می‌دهید؟")
            .setNegativeButton("لغو", null)
            .setPositiveButton("بله، کلید تازه بساز") { _, _ ->
                Threads.runIo {
                    app.repo.regenerateIdentity()
                    Threads.runUi {
                        toast("کلید تازه ساخته شد.")
                        goMain()
                    }
                }
            }
            .show()
    }

    private fun goMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQ_OPEN_DOC = 7712
    }
}
