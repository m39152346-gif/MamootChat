package ir.mamoot.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads
import ir.mamoot.app.crypto.Crypto
import ir.mamoot.app.util.UiUtil
import ir.mamoot.app.util.PhoneNumbers

class SettingsActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var etDisplay: EditText
    private lateinit var etUsername: EditText
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etDisplay = findViewById(R.id.etSettingsDisplay)
        etUsername = findViewById(R.id.etSettingsUsername)
        tvError = findViewById(R.id.tvSettingsError)

        val currentSession = app.api.session
        findViewById<TextView>(R.id.tvSettingsEmail).text = when {
            !currentSession?.email.isNullOrBlank() -> currentSession?.email ?: ""
            !currentSession?.phone.isNullOrBlank() -> PhoneNumbers.formatLocalDisplay(currentSession?.phone ?: "")
            else -> ""
        }
        findViewById<Button>(R.id.btnBackSettings).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { save() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { confirmLogout() }

        val fingerprint = Crypto.sha256(app.identity.publicKey().encoded)
            .joinToString("") { "%02x".format(it) }.take(24).uppercase().chunked(4).joinToString(" ")
        findViewById<TextView>(R.id.tvIdentityInfo).text =
            "🔐 کلید هویت چت‌های امن شما فقط روی همین گوشی ساخته و نگهداری می‌شود و هرگز به سرور نمی‌رسد.\n" +
                "اثر انگشت کلید عمومی این دستگاه:\n$fingerprint\n" +
                "با پاک‌کردن اپ یا خروج از حساب، کلیدها حذف می‌شوند و چت‌های امن قبلی روی سرور دیگر خوانا نخواهند بود (دقیقاً مثل چت مخفی تلگرام)."

        loadProfile()
    }

    private fun loadProfile() {
        val userId = app.api.myUserId ?: return
        Threads.runIo {
            try {
                val profile = app.api.fetchProfile(userId)
                Threads.runUi {
                    if (profile != null) {
                        etDisplay.setText(profile.displayName)
                        etUsername.setText(profile.username)
                        val avatar = findViewById<TextView>(R.id.tvSettingsAvatar)
                        avatar.text = UiUtil.initials(profile.displayName)
                        avatar.background.mutate().setTint(UiUtil.avatarColor(profile.id))
                    }
                }
            } catch (e: Exception) {
                Threads.runUi { showError(e.message ?: "خطا در دریافت پروفایل") }
            }
        }
    }

    private fun save() {
        val displayName = etDisplay.text.toString().trim()
        val username = etUsername.text.toString().trim()
        tvError.visibility = View.GONE
        if (displayName.isEmpty()) return showError("نام نمایشی را وارد کنید.")
        if (!Regex("^[A-Za-z0-9_.]{3,24}$").matches(username)) {
            return showError("نام کاربری باید ۳ تا ۲۴ نویسه و فقط حروف انگلیسی، عدد، نقطه یا _ باشد.")
        }
        val userId = app.api.myUserId ?: return
        Threads.runIo {
            try {
                app.api.updateProfile(userId, username, displayName)
                Threads.runUi { Toast.makeText(this, "ذخیره شد.", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Threads.runUi { showError(e.message ?: "ذخیره ناموفق بود") }
            }
        }
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("خروج از حساب")
            .setMessage("همه داده‌های مموت روی این گوشی — از جمله کلیدهای چت امن و پیام‌های ذخیره‌شده — پاک می‌شود. ادامه می‌دهید؟")
            .setNegativeButton("لغو", null)
            .setPositiveButton("خروج و پاک‌کردن") { _, _ ->
                app.cancelBackgroundChecks()
                Threads.runIo {
                    app.api.signOut()
                    app.repo.wipeLocal()
                    Threads.runUi {
                        val intent = Intent(this, AuthActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                    }
                }
            }
            .show()
    }
}
