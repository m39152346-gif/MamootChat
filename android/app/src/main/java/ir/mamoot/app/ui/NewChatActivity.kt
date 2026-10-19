package ir.mamoot.app.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads

class NewChatActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var adapter: UserAdapter
    private lateinit var tvHint: TextView
    private lateinit var lvUsers: ListView
    private val handler = Handler(Looper.getMainLooper())
    private var searchSeq = 0
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_chat)

        adapter = UserAdapter(this)
        tvHint = findViewById(R.id.tvSearchHint)
        lvUsers = findViewById(R.id.lvUsers)
        lvUsers.adapter = adapter

        findViewById<Button>(R.id.btnBackNew).setOnClickListener { finish() }

        val etSearch = findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                handler.removeCallbacksAndMessages(null)
                if (query.length < 2) {
                    adapter.submit(emptyList())
                    tvHint.visibility = View.VISIBLE
                    tvHint.text = "نام کاربری مخاطب را جستجو کنید.\nچت امن فقط بین دو گوشی دارای اپ مموت برقرار می‌شود."
                    return
                }
                val seq = ++searchSeq
                handler.postDelayed({ runSearch(query, seq) }, 300)
            }
        })

        lvUsers.setOnItemClickListener { _, _, position, _ ->
            if (opening) return@setOnItemClickListener
            val user = adapter.getItem(position)
            opening = true
            Threads.runIo {
                try {
                    val convId = app.api.getOrCreateDm(user.id)
                    Threads.runUi {
                        opening = false
                        startActivity(
                            ChatActivity.intentForDm(
                                this, convId, user.id, user.displayName, user.username, user.e2eIdentityKey,
                            ),
                        )
                        finish()
                    }
                } catch (e: Exception) {
                    Threads.runUi {
                        opening = false
                        Toast.makeText(this, e.message ?: "ساخت گفتگو ناموفق بود", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        etSearch.requestFocus()
    }

    private fun runSearch(query: String, seq: Int) {
        tvHint.visibility = View.VISIBLE
        tvHint.text = "در حال جستجو…"
        Threads.runIo {
            try {
                val results = app.api.searchUsers(query)
                Threads.runUi {
                    if (seq != searchSeq) return@runUi
                    adapter.submit(results)
                    tvHint.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                    if (results.isEmpty()) tvHint.text = "کاربری پیدا نشد."
                }
            } catch (e: Exception) {
                Threads.runUi {
                    if (seq != searchSeq) return@runUi
                    tvHint.visibility = View.VISIBLE
                    tvHint.text = e.message ?: "خطا در جستجو"
                }
            }
        }
    }
}
