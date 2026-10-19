package ir.mamoot.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads
import ir.mamoot.app.net.ApiException
import ir.mamoot.app.util.PhoneNumbers
import ir.mamoot.app.util.UiUtil

class AuthActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var layoutAuth: LinearLayout
    private lateinit var layoutProfile: LinearLayout
    private lateinit var etDisplayName: EditText
    private lateinit var etUsername: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var tvAuthError: TextView
    private lateinit var btnAuthSubmit: Button
    private lateinit var btnModeLogin: Button
    private lateinit var btnModeSignup: Button
    private lateinit var etProfileDisplay: EditText
    private lateinit var etProfileUsername: EditText
    private lateinit var tvProfileError: TextView
    private lateinit var btnProfileSubmit: Button
    private lateinit var layoutMethodToggle: LinearLayout
    private lateinit var layoutPhone: LinearLayout
    private lateinit var btnMethodPhone: Button
    private lateinit var btnMethodEmail: Button
    private lateinit var etPhone: EditText
    private lateinit var btnSendCode: Button
    private lateinit var layoutOtp: LinearLayout
    private lateinit var etOtpCode: EditText
    private lateinit var btnVerifyCode: Button
    private lateinit var btnResend: Button
    private lateinit var tvPhoneError: TextView

    private var signupMode = false
    private var busy = false
    private var phoneMethod = true
    private var phoneBusy = false
    private var pendingPhoneE164: String? = null
    private var resendLeftSec = 0
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTick: Runnable = object : Runnable {
        override fun run() {
            if (resendLeftSec > 0) {
                resendLeftSec--
                updateResendButton()
                if (resendLeftSec > 0) countdownHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        layoutAuth = findViewById(R.id.layoutAuth)
        layoutProfile = findViewById(R.id.layoutProfile)
        etDisplayName = findViewById(R.id.etDisplayName)
        etUsername = findViewById(R.id.etUsername)
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
        layoutMethodToggle = findViewById(R.id.layoutMethodToggle)
        layoutPhone = findViewById(R.id.layoutPhone)
        btnMethodPhone = findViewById(R.id.btnMethodPhone)
        btnMethodEmail = findViewById(R.id.btnMethodEmail)
        etPhone = findViewById(R.id.etPhone)
        btnSendCode = findViewById(R.id.btnSendCode)
        layoutOtp = findViewById(R.id.layoutOtp)
        etOtpCode = findViewById(R.id.etOtpCode)
        btnVerifyCode = findViewById(R.id.btnVerifyCode)
        btnResend = findViewById(R.id.btnResend)
        tvPhoneError = findViewById(R.id.tvPhoneError)

        btnMethodPhone.setOnClickListener { setMethod(true) }
        btnMethodEmail.setOnClickListener { setMethod(false) }
        btnSendCode.setOnClickListener { sendPhoneCode() }
        btnVerifyCode.setOnClickListener { verifyPhoneCode() }
        btnResend.setOnClickListener { sendPhoneCode() }
        findViewById<Button>(R.id.btnChangeNumber).setOnClickListener { changePhoneNumber() }
        btnModeLogin.setOnClickListener { setMode(false) }
        btnModeSignup.setOnClickListener { setMode(true) }
        btnAuthSubmit.setOnClickListener { submitAuth() }
        findViewById<Button>(R.id.btnForgot).setOnClickListener { forgotPassword() }
        btnProfileSubmit.setOnClickListener { submitProfile() }
        findViewById<Button>(R.id.btnProfileLogout).setOnClickListener {
            Threads.runIo {
                app.api.signOut()
                Threads.runUi {
                    layoutProfile.visibility = View.GONE
                    layoutMethodToggle.visibility = View.VISIBLE
                    changePhoneNumber()
                    setMethod(phoneMethod)
                }
            }
        }
        setMode(false)
        setMethod(true)

        // Already signed in but profile missing (e.g. after email confirmation).
        if (app.api.session != null) {
            routeAfterAuth()
        }
    }

    private fun setMode(signup: Boolean) {
        signupMode = signup
        etDisplayName.visibility = if (signup) View.VISIBLE else View.GONE
        etUsername.visibility = if (signup) View.VISIBLE else View.GONE
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

        if (email.isEmpty() || "@" !in email) return showAuthError("ایمیل معتبر وارد کنید.")
        if (password.isEmpty()) return showAuthError("رمز عبور را وارد کنید.")
        if (signupMode) {
            if (displayName.isEmpty()) return showAuthError("نام نمایشی را وارد کنید.")
            if (!USERNAME_RE.matches(username)) {
                return showAuthError("نام کاربری باید ۳ تا ۲۴ نویسه و فقط حروف انگلیسی، عدد، نقطه یا _ باشد.")
            }
            if (password.length < 8 || password.none { it.isLetter() } || password.none { it.isDigit() }) {
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
        if (displayName.isEmpty()) return showProfileError("نام نمایشی را وارد کنید.")
        if (!USERNAME_RE.matches(username)) {
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
        phoneBusy = false
        btnVerifyCode.isEnabled = true
        btnVerifyCode.text = "تأیید و ورود"
        layoutMethodToggle.visibility = View.GONE
        layoutPhone.visibility = View.GONE
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
                    val msg = e.message ?: "خطا رخ داد"
                    if (phoneMethod) showPhoneError(msg) else showAuthError(msg)
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


    private fun setMethod(phone: Boolean) {
        phoneMethod = phone
        layoutPhone.visibility = if (phone) View.VISIBLE else View.GONE
        layoutAuth.visibility = if (phone) View.GONE else View.VISIBLE
        btnMethodPhone.alpha = if (phone) 1f else 0.55f
        btnMethodEmail.alpha = if (phone) 0.55f else 1f
        tvAuthError.visibility = View.GONE
        tvPhoneError.visibility = View.GONE
    }

    private fun showPhoneError(message: String) {
        tvPhoneError.text = message
        tvPhoneError.visibility = View.VISIBLE
    }

    private fun sendPhoneCode() {
        if (phoneBusy) return
        tvPhoneError.visibility = View.GONE
        val e164 = PhoneNumbers.toE164Iran(etPhone.text.toString())
            ?: return showPhoneError("شماره موبایل معتبر وارد کنید؛ مثال: ۰۹۱۲۳۴۵۶۷۸۹")
        phoneBusy = true
        btnSendCode.isEnabled = false
        btnSendCode.text = "در حال ارسال کد…"
        btnResend.isEnabled = false
        Threads.runIo {
            try {
                app.api.sendPhoneOtp(e164)
                Threads.runUi {
                    phoneBusy = false
                    pendingPhoneE164 = e164
                    layoutOtp.visibility = View.VISIBLE
                    etPhone.isEnabled = false
                    btnSendCode.visibility = View.GONE
                    etOtpCode.text.clear()
                    etOtpCode.requestFocus()
                    startResendCountdown()
                    Toast.makeText(this, "کد تأیید پیامک شد.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Threads.runUi {
                    phoneBusy = false
                    btnSendCode.text = "دریافت کد تأیید"
                    if (pendingPhoneE164 == null) {
                        btnSendCode.visibility = View.VISIBLE
                        btnSendCode.isEnabled = true
                    }
                    updateResendButton()
                    showPhoneError(e.message ?: "خطا رخ داد")
                }
            }
        }
    }

    private fun verifyPhoneCode() {
        if (phoneBusy) return
        tvPhoneError.visibility = View.GONE
        val e164 = pendingPhoneE164 ?: return showPhoneError("ابتدا شماره را وارد و کد دریافت کنید.")
        val code = PhoneNumbers.latinDigits(etOtpCode.text.toString()).filter { it.isDigit() }
        if (code.length != 6) return showPhoneError("کد پیامک‌شده ۶ رقمی را کامل وارد کنید.")
        phoneBusy = true
        btnVerifyCode.isEnabled = false
        btnVerifyCode.text = "لطفاً صبر کنید…"
        Threads.runIo {
            try {
                app.api.verifyPhoneOtp(e164, code)
                routeAfterAuthOnIo()
            } catch (e: Exception) {
                Threads.runUi {
                    phoneBusy = false
                    btnVerifyCode.isEnabled = true
                    btnVerifyCode.text = "تأیید و ورود"
                    showPhoneError(e.message ?: "خطا رخ داد")
                }
            }
        }
    }

    private fun changePhoneNumber() {
        countdownHandler.removeCallbacks(countdownTick)
        resendLeftSec = 0
        pendingPhoneE164 = null
        layoutOtp.visibility = View.GONE
        etPhone.isEnabled = true
        btnSendCode.visibility = View.VISIBLE
        btnSendCode.isEnabled = true
        btnSendCode.text = "دریافت کد تأیید"
        tvPhoneError.visibility = View.GONE
    }

    private fun startResendCountdown() {
        resendLeftSec = 60
        updateResendButton()
        countdownHandler.removeCallbacks(countdownTick)
        countdownHandler.postDelayed(countdownTick, 1000)
    }

    private fun updateResendButton() {
        if (resendLeftSec > 0) {
            btnResend.isEnabled = false
            btnResend.text = "ارسال دوباره کد (${UiUtil.faNum(resendLeftSec)})"
        } else {
            btnResend.isEnabled = !phoneBusy
            btnResend.text = "ارسال دوباره کد"
        }
    }

    override fun onDestroy() {
        countdownHandler.removeCallbacks(countdownTick)
        super.onDestroy()
    }

    private companion object {
        val USERNAME_RE = Regex("^[A-Za-z0-9_.]{3,24}$")
    }
}
