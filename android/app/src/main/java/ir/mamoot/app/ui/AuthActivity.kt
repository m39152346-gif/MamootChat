package ir.mamoot.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads
import ir.mamoot.app.net.ApiException

class AuthActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var layoutAuth: LinearLayout
    private lateinit var layoutProfile: LinearLayout
    private lateinit var etDisplayName: EditText
    private lateinit var etUsername: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var lblDisplayName: TextView
    private lateinit var lblUsername: TextView
    private lateinit var tvAuthError: TextView
    private lateinit var btnAuthSubmit: Button
    private lateinit var btnModeLogin: Button
    private lateinit var btnModeSignup: Button
    private lateinit var etProfileDisplay: EditText
    private lateinit var etProfileUsername: EditText
    private lateinit var tvProfileError: TextView
    private lateinit var btnProfileSubmit: Button

    private var signupMode = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        layoutAuth = findViewById(R.id.layoutAuth)
        layoutProfile = findViewById(R.id.layoutProfile)
        etDisplayName = findViewById(R.id.etDisplayName)
        etUsername = findViewById(R.id.etUsername)
        lblDisplayName = findViewById(R.id.lblDisplayName)
        lblUsername = findViewById(R.id.lblUsername)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        tvAuthError = findViewById(R.id.tvAuthError)
        btnAuthSubmit = findViewById(R.id.btnAuthSubmit)
        btnModeLogin = findViewById(R.id.btnModeLogin)
        btnModeSignup = findViewById(R.id.btnModeSignup)
        etProfileDisplay = findViewById(R.id.etProfileDisplay)
        etProfileUsername = findViewById(R.id.etProfileUsername)
        tvProfileError = findViewById(R.id.tvProfileError)
        btnProfileSubmit = findViewById(R.id.btnProfileSubmit)

        btnModeLogin.setOnClickListener { setMode(false) }
        btnModeSignup.setOnClickListener { setMode(true) }
        btnAuthSubmit.setOnClickListener { submitAuth() }
        findViewById<CheckBox>(R.id.cbShowPassword).setOnCheckedChangeListener { _, checked ->
            val tf = etPassword.typeface
            etPassword.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etPassword.typeface = tf
            etPassword.setSelection(etPassword.text?.length ?: 0)
        }
        findViewById<Button>(R.id.btnForgot).setOnClickListener { forgotPassword() }
        btnProfileSubmit.setOnClickListener { submitProfile() }
        findViewById<Button>(R.id.btnProfileLogout).setOnClickListener {
            Threads.runIo {
                app.api.signOut()
                Threads.runUi {
                    layoutProfile.visibility = View.GONE
                    layoutAuth.visibility = View.VISIBLE
                }
            }
        }
        setMode(false)

        // Already signed in but profile missing (e.g. after email confirmation).
        if (app.api.session != null) {
            routeAfterAuth()
        }
    }

    private fun setMode(signup: Boolean) {
        signupMode = signup
        etDisplayName.visibility = if (signup) View.VISIBLE else View.GONE
        etUsername.visibility = if (signup) View.VISIBLE else View.GONE
        lblDisplayName.visibility = if (signup) View.VISIBLE else View.GONE
        lblUsername.visibility = if (signup) View.VISIBLE else View.GONE
        btnAuthSubmit.text = if (signup) "ساخت حساب" else "ورود"
        btnModeLogin.alpha = if (signup) 0.55f else 1f
        btnModeSignup.alpha = if (signup) 1f else 0.55f
        tvAuthError.visibility = View.GONE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        btnAuthSubmit.isEnabled = !value
        btnProfileSubmit.isEnabled = !value
        btnAuthSubmit.text = if (value) "لطفاً صبر کنید…" else if (signupMode) "ساخت حساب" else "ورود"
    }

    private fun showAuthError(message: String) {
        tvAuthError.text = message
        tvAuthError.visibility = View.VISIBLE
    }

    private fun submitAuth() {
        if (busy) return
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        val displayName = etDisplayName.text.toString().trim()
        val username = etUsername.text.toString().trim()
        tvAuthError.visibility = View.GONE
        etEmail.error = null
        etPassword.error = null
        etDisplayName.error = null
        etUsername.error = null

        if (email.isEmpty() || "@" !in email) {
            etEmail.error = "ایمیل معتبر وارد کنید."
            etEmail.requestFocus()
            return showAuthError("ایمیل معتبر وارد کنید.")
        }
        if (password.isEmpty()) {
            etPassword.error = "رمز عبور را وارد کنید."
            etPassword.requestFocus()
            return showAuthError("رمز عبور را وارد کنید.")
        }
        if (signupMode) {
            if (displayName.isEmpty()) {
                etDisplayName.error = "نام نمایشی را وارد کنید."
                etDisplayName.requestFocus()
                return showAuthError("نام نمایشی را وارد کنید.")
            }
            if (!USERNAME_RE.matches(username)) {
                etUsername.error = "نام کاربری باید ۳ تا ۲۴ نویسه و فقط حروف انگلیسی، عدد، نقطه یا _ باشد."
                etUsername.requestFocus()
                return showAuthError("نام کاربری باید ۳ تا ۲۴ نویسه و فقط حروف انگلیسی، عدد، نقطه یا _ باشد.")
            }
            if (password.length < 8 || password.none { it.isLetter() } || password.none { it.isDigit() }) {
                etPassword.error = "رمز عبور باید حداقل ۸ نویسه و شامل حرف و عدد باشد."
                etPassword.requestFocus()
                return showAuthError("رمز عبور باید حداقل ۸ نویسه و شامل حرف و عدد باشد.")
            }
        }

        setBusy(true)
        Threads.runIo {
            try {
                if (signupMode) {
                    val tokens = app.api.signUp(email, password)
                    if (tokens == null) {
                        Threads.runUi {
                            setBusy(false)
                            setMode(false)
                            showAuthError("حساب ساخته شد؛ برای تأیید ایمیلتان را بررسی کنید و سپس وارد شوید.")
                        }
                        return@runIo
                    }
                    try {
                        app.api.createProfile(tokens.userId, username, displayName)
                    } catch (e: ApiException) {
                        Threads.runUi {
                            setBusy(false)
                            showProfileSetup(displayName, username, e.message ?: "خطا در ساخت پروفایل")
                        }
                        return@runIo
                    }
                } else {
                    app.api.signIn(email, password)
                }
                routeAfterAuthOnIo()
            } catch (e: Exception) {
                Threads.runUi {
                    setBusy(false)
                    showAuthError(e.message ?: "خطا رخ داد")
                }
            }
        }
    }

    private fun submitProfile() {
        if (busy) return
        val displayName = etProfileDisplay.text.toString().trim()
        val username = etProfileUsername.text.toString().trim()
        tvProfileError.visibility = View.GONE
        etProfileDisplay.error = null
        etProfileUsername.error = null
        if (displayName.isEmpty()) {
            etProfileDisplay.error = "نام نمایشی را وارد کنید."
            etProfileDisplay.requestFocus()
            return showProfileError("نام نمایشی را وارد کنید.")
        }
        if (!USERNAME_RE.matches(username)) {
            etProfileUsername.error = "نام کاربری باید ۳ تا ۲۴ نویسه و فقط حروف انگلیسی، عدد، نقطه یا _ باشد."
            etProfileUsername.requestFocus()
            return showProfileError("نام کاربری باید ۳ تا ۲۴ نویسه و فقط حروف انگلیسی، عدد، نقطه یا _ باشد.")
        }
        val userId = app.api.myUserId ?: return showProfileError("نشست منقضی شده؛ دوباره وارد شوید.")
        setBusy(true)
        Threads.runIo {
            try {
                app.api.createProfile(userId, username, displayName)
                routeAfterAuthOnIo()
            } catch (e: Exception) {
                Threads.runUi {
                    setBusy(false)
                    showProfileError(e.message ?: "خطا رخ داد")
                }
            }
        }
    }

    private fun showProfileError(message: String) {
        tvProfileError.text = message
        tvProfileError.visibility = View.VISIBLE
    }

    private fun showProfileSetup(displayName: String, username: String, error: String?) {
        etProfileDisplay.setText(displayName)
        etProfileUsername.setText(username)
        layoutAuth.visibility = View.GONE
        layoutProfile.visibility = View.VISIBLE
        if (error != null) showProfileError(error)
    }

    private fun forgotPassword() {
        val email = etEmail.text.toString().trim()
        if (email.isEmpty() || "@" !in email) {
            showAuthError("برای بازیابی رمز، ابتدا ایمیل را وارد کنید.")
            return
        }
        Threads.runIo {
            try {
                app.api.resetPassword(email)
                Threads.runUi {
                    Toast.makeText(this, "اگر این ایمیل ثبت شده باشد، لینک بازیابی ارسال می‌شود.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Threads.runUi { showAuthError(e.message ?: "خطا رخ داد") }
            }
        }
    }

    private fun routeAfterAuth() {
        setBusy(true)
        Threads.runIo {
            try {
                routeAfterAuthOnIo()
            } catch (e: Exception) {
                Threads.runUi {
                    setBusy(false)
                    showAuthError(e.message ?: "خطا رخ داد")
                }
            }
        }
    }

    /** Must be called on the IO thread. */
    private fun routeAfterAuthOnIo() {
        val userId = app.api.myUserId ?: throw ApiException("نشست یافت نشد")
        val profile = app.api.fetchProfile(userId)
        if (profile == null) {
            Threads.runUi {
                setBusy(false)
                showProfileSetup("", "", null)
            }
            return
        }
        // Publish this device's secret-chat identity key (public part only).
        val myKey = app.identity.publicKeyB64()
        if (profile.e2eIdentityKey != myKey) {
            app.api.updateIdentityKey(userId, myKey)
        }
        app.scheduleBackgroundChecks()
        Threads.runUi {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private companion object {
        val USERNAME_RE = Regex("^[A-Za-z0-9_.]{3,24}$")
    }
}
