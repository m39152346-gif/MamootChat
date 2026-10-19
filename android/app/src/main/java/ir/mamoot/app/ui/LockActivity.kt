package ir.mamoot.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads

class LockActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var etPin: EditText
    private lateinit var tvError: TextView
    private lateinit var btnUnlock: Button
    private var failedAttempts = 0
    private var lockedUntilMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        etPin = findViewById(R.id.etPin)
        tvError = findViewById(R.id.tvLockError)
        btnUnlock = findViewById(R.id.btnUnlock)

        btnUnlock.setOnClickListener { tryUnlock() }
        etPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryUnlock()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.btnForgotPin).setOnClickListener { confirmWipe() }

        val btnBiometric = findViewById<Button>(R.id.btnBiometric)
        if (canUseBiometrics()) {
            btnBiometric.visibility = View.VISIBLE
            btnBiometric.setOnClickListener { showBiometricPrompt() }
            showBiometricPrompt()
        } else {
            etPin.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun tryUnlock() {
        val now = System.currentTimeMillis()
        if (now < lockedUntilMs) {
            showError("تلاش‌های ناموفق زیاد بود؛ ${(lockedUntilMs - now) / 1000 + 1} ثانیه صبر کنید.")
            return
        }
        val pin = etPin.text.toString()
        if (pin.length < 4) {
            showError("رمز قفل دست‌کم ۴ رقم است.")
            return
        }
        btnUnlock.isEnabled = false
        Threads.runIo {
            val ok = app.appLock.verify(pin)
            Threads.runUi {
                btnUnlock.isEnabled = true
                if (ok) {
                    unlockAndFinish()
                } else {
                    failedAttempts++
                    if (failedAttempts >= 5) {
                        lockedUntilMs = System.currentTimeMillis() + 30_000
                        failedAttempts = 0
                        showError("رمز اشتباه است. ۳۰ ثانیه صبر کنید.")
                    } else {
                        showError("رمز اشتباه است.")
                    }
                    etPin.text.clear()
                }
            }
        }
    }

    private fun unlockAndFinish() {
        app.unlocked = true
        finish()
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun canUseBiometrics(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val manager = getSystemService(BiometricManager::class.java)
            manager?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    private fun showBiometricPrompt() {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("باز کردن مموت")
                .setNegativeButton("استفاده از رمز", mainExecutor) { _, _ -> etPin.requestFocus() }
                .build()
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        unlockAndFinish()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        etPin.requestFocus()
                    }
                },
            )
        } catch (e: Exception) {
            // PIN path remains available.
        }
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("فراموشی رمز قفل")
            .setMessage("رمز قفل قابل بازیابی نیست. تنها راه، خروج از حساب و پاک‌کردن همه داده‌های مموت روی این گوشی است (از جمله کلیدهای چت امن). ادامه می‌دهید؟")
            .setNegativeButton("لغو", null)
            .setPositiveButton("خروج و پاک‌کردن") { _, _ ->
                app.cancelBackgroundChecks()
                Threads.runIo {
                    app.api.signOut()
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
            .show()
    }
}
