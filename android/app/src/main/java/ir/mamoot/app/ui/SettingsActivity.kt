package ir.mamoot.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads
import ir.mamoot.app.crypto.Crypto
import ir.mamoot.app.util.UiUtil

class SettingsActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var etDisplay: EditText
    private lateinit var etUsername: EditText
    private lateinit var tvError: TextView
    private lateinit var btnAppLock: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etDisplay = findViewById(R.id.etSettingsDisplay)
        etUsername = findViewById(R.id.etSettingsUsername)
        tvError = findViewById(R.id.tvSettingsError)

        findViewById<TextView>(R.id.tvSettingsEmail).text = app.api.session?.email ?: ""
        findViewById<Button>(R.id.btnBackSettings).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { save() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { confirmLogout() }
        btnAppLock = findViewById(R.id.btnAppLock)
        findViewById<Button>(R.id.btnChangePassword).setOnClickListener { showChangePassword() }
        btnAppLock.setOnClickListener { showAppLockOptions() }
        findViewById<Button>(R.id.btnBlockedUsers).setOnClickListener { showBlockedUsers() }
        findViewById<Button>(R.id.btnDeleteAccount).setOnClickListener { confirmDeleteAccount() }
        updateAppLockLabel()

        val fingerprint = Crypto.sha256(app.identity.publicKey().encoded)
            .joinToString("") { "%02x".format(it) }.take(24).uppercase().chunked(4).joinToString(" ")
        findViewById<TextView>(R.id.tvIdentityInfo).text =
            "🔐 چت امن مموت (مثل چت مخفی تلگرام) به همین گوشی وابسته است: کلید هویت فقط روی همین دستگاه ساخته و نگهداری می‌شود و هرگز به سرور نمی‌رسد.\n" +
                "اثر انگشت کلید عمومی این دستگاه:\n$fingerprint\n" +
                "نکته: برای چت امن، هر حساب را فقط روی یک گوشی استفاده کنید؛ ورود از گوشی دوم کلید را عوض می‌کند و برای مخاطبان هشدار امنیتی می‌سازد. " +
                "با پاک‌کردن اپ یا خروج از حساب، کلیدها حذف می‌شوند و پیام‌های امن قبلی روی سرور دیگر خوانا نخواهند بود. گفتگوهای عادی (بدون قفل) این محدودیت را ندارند."

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


    private fun field(hint: String, password: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 12 }
    }

    private fun column(vararg views: View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        views.forEach { box.addView(it) }
        return box
    }

    private fun showChangePassword() {
        val email = app.api.session?.email.orEmpty()
        if (email.isEmpty()) {
            Toast.makeText(this, "نشست معتبر نیست؛ دوباره وارد شوید.", Toast.LENGTH_LONG).show()
            return
        }
        val current = field("رمز عبور فعلی", password = true)
        val next = field("رمز عبور تازه (حداقل ۸ نویسه، حرف و عدد)", password = true)
        val repeat = field("تکرار رمز تازه", password = true)
        AlertDialog.Builder(this)
            .setTitle("تغییر رمز عبور")
            .setView(column(current, next, repeat))
            .setNegativeButton("لغو", null)
            .setPositiveButton("تغییر رمز") { _, _ ->
                val newPass = next.text.toString()
                when {
                    current.text.isEmpty() -> toast("رمز فعلی را وارد کنید.")
                    newPass.length < 8 || newPass.none { it.isLetter() } || newPass.none { it.isDigit() } ->
                        toast("رمز تازه باید حداقل ۸ نویسه و شامل حرف و عدد باشد.")
                    newPass != repeat.text.toString() -> toast("تکرار رمز تازه درست نیست.")
                    else -> Threads.runIo {
                        try {
                            app.api.signIn(email, current.text.toString()) // verify current password
                            app.api.updatePassword(newPass)
                            Threads.runUi { toast("رمز عبور عوض شد.") }
                        } catch (e: Exception) {
                            Threads.runUi { toast(e.message ?: "تغییر رمز ناموفق بود") }
                        }
                    }
                }
            }
            .show()
    }

    private fun updateAppLockLabel() {
        val state = if (app.appLock.isPinSet()) "روشن" else "خاموش"
        btnAppLock.text = "🔒 قفل برنامه ($state)"
    }

    private fun showAppLockOptions() {
        if (!app.appLock.isPinSet()) {
            promptNewPin("تنظیم رمز قفل برنامه") { pin ->
                app.appLock.setPin(pin)
                app.unlocked = true
                updateAppLockLabel()
                toast("قفل برنامه فعال شد.")
            }
            return
        }
        val options = arrayOf("تغییر رمز قفل", "زمان قفل خودکار", "خاموش‌کردن قفل")
        AlertDialog.Builder(this)
            .setTitle("قفل برنامه")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptCurrentPin {
                        promptNewPin("رمز قفل تازه") { pin ->
                            app.appLock.setPin(pin)
                            toast("رمز قفل عوض شد.")
                        }
                    }
                    1 -> showAutoLockPicker()
                    2 -> promptCurrentPin {
                        app.appLock.clear()
                        updateAppLockLabel()
                        toast("قفل برنامه خاموش شد.")
                    }
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun promptCurrentPin(onOk: () -> Unit) {
        val input = field("رمز قفل فعلی", password = true).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("تأیید رمز قفل")
            .setView(column(input))
            .setNegativeButton("لغو", null)
            .setPositiveButton("تأیید") { _, _ ->
                Threads.runIo {
                    val ok = app.appLock.verify(input.text.toString())
                    Threads.runUi {
                        if (ok) onOk() else toast("رمز قفل اشتباه است.")
                    }
                }
            }
            .show()
    }

    private fun promptNewPin(title: String, onOk: (String) -> Unit) {
        val first = field("رمز تازه (۴ تا ۱۲ رقم)", password = true).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val second = field("تکرار رمز تازه", password = true).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(column(first, second))
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val pin = first.text.toString().trim()
                when {
                    pin.length !in 4..12 || !pin.all { it.isDigit() } -> toast("رمز قفل باید ۴ تا ۱۲ رقم باشد.")
                    pin != second.text.toString().trim() -> toast("تکرار رمز درست نیست.")
                    else -> onOk(pin)
                }
            }
            .show()
    }

    private fun showAutoLockPicker() {
        val options = listOf(
            0 to "به‌محض خروج از برنامه",
            60 to "بعد از ۱ دقیقه",
            300 to "بعد از ۵ دقیقه",
            -1 to "فقط با باز شدن دوباره برنامه",
        )
        val labels = options.map { it.second }.toTypedArray()
        val checked = options.indexOfFirst { it.first == app.appLock.autoLockSeconds }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("قفل خودکار")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                app.appLock.autoLockSeconds = options[which].first
                dialog.dismiss()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun showBlockedUsers() {
        Threads.runIo {
            try {
                val ids = app.api.myBlockedIds()
                val profiles = ids.mapNotNull { id ->
                    try {
                        app.api.fetchProfile(id)
                    } catch (e: Exception) {
                        null
                    }
                }
                Threads.runUi {
                    if (isFinishing) return@runUi
                    if (profiles.isEmpty()) {
                        toast("کاربر مسدودی ندارید.")
                        return@runUi
                    }
                    val labels = profiles.map { "${it.displayName} (@${it.username})" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("کاربران مسدود — برای رفع مسدودیت بزنید")
                        .setItems(labels) { _, which ->
                            val target = profiles[which]
                            AlertDialog.Builder(this)
                                .setMessage("مسدودیت «${target.displayName}» رفع شود؟")
                                .setNegativeButton("لغو", null)
                                .setPositiveButton("رفع مسدودیت") { _, _ ->
                                    Threads.runIo {
                                        try {
                                            app.api.unblockUser(target.id)
                                            Threads.runUi { if (!isFinishing) toast("مسدودیت رفع شد.") }
                                        } catch (e: Exception) {
                                            Threads.runUi { if (!isFinishing) toast(e.message ?: "خطا رخ داد") }
                                        }
                                    }
                                }
                                .show()
                        }
                        .setNegativeButton("بستن", null)
                        .show()
                }
            } catch (e: Exception) {
                Threads.runUi { toast(e.message ?: "خطا در دریافت فهرست مسدودها") }
            }
        }
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle("حذف حساب کاربری؟")
            .setMessage("حساب، پروفایل و همه پیام‌های شما برای همیشه از سرور پاک می‌شود و قابل برگشت نیست. گفتگوهای مشترک برای طرف مقابل (بدون پیام‌های شما) باقی می‌ماند.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("ادامه") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("مطمئن هستید؟")
                    .setMessage("این کار برگشت ندارد. برای ساخت حساب تازه باید دوباره ثبت‌نام کنید.")
                    .setNegativeButton("لغو", null)
                    .setPositiveButton("حذف همیشگی حساب") { _, _ -> deleteAccount() }
                    .show()
            }
            .show()
    }

    private fun deleteAccount() {
        app.cancelBackgroundChecks()
        Threads.runIo {
            try {
                app.api.deleteMyAccount()
            } catch (e: Exception) {
                Threads.runUi { toast(e.message ?: "حذف حساب ناموفق بود") }
                return@runIo
            }
            // Server-side account is gone; wipe this device.
            try {
                app.api.signOut()
            } catch (e: Exception) {
                // Session no longer exists server-side; local cleanup below.
            }
            app.repo.wipeLocal()
            app.appLock.clear()
            Threads.runUi {
                app.unlocked = false
                val intent = Intent(this, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
                        app.unlocked = false
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
