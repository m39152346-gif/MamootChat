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
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads
import ir.mamoot.app.net.Profile
import ir.mamoot.app.util.UiUtil

class NewChatActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var adapter: UserAdapter
    private lateinit var tvHint: TextView
    private lateinit var lvUsers: ListView
    private lateinit var btnNewModeDm: Button
    private lateinit var btnNewModeGroup: Button
    private lateinit var layoutGroupCreate: LinearLayout
    private lateinit var etGroupTitle: EditText
    private lateinit var btnCreateGroup: Button

    private val handler = Handler(Looper.getMainLooper())
    private var searchSeq = 0
    private var opening = false
    private var groupMode = false
    private val selected = linkedMapOf<String, Profile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_chat)

        adapter = UserAdapter(this)
        tvHint = findViewById(R.id.tvSearchHint)
        lvUsers = findViewById(R.id.lvUsers)
        lvUsers.adapter = adapter
        btnNewModeDm = findViewById(R.id.btnNewModeDm)
        btnNewModeGroup = findViewById(R.id.btnNewModeGroup)
        layoutGroupCreate = findViewById(R.id.layoutGroupCreate)
        etGroupTitle = findViewById(R.id.etGroupTitle)
        btnCreateGroup = findViewById(R.id.btnCreateGroup)

        findViewById<Button>(R.id.btnBackNew).setOnClickListener { finish() }
        btnNewModeDm.setOnClickListener { setGroupMode(false) }
        btnNewModeGroup.setOnClickListener { setGroupMode(true) }
        btnCreateGroup.setOnClickListener { createGroup() }
        setGroupMode(false)

        val etSearch = findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                handler.removeCallbacksAndMessages(null)
                if (query.length < 2) {
                    adapter.submit(emptyList())
                    adapter.selectedIds = selected.keys.toSet()
                    tvHint.visibility = View.VISIBLE
                    tvHint.text = if (groupMode) {
                        "اعضای گروه را با جستجو انتخاب کنید؛ روی هر نفر بزنید تا تیک بخورد."
                    } else {
                        "نام کاربری مخاطب را جستجو کنید."
                    }
                    return
                }
                val seq = ++searchSeq
                handler.postDelayed({ runSearch(query, seq) }, 300)
            }
        })

        lvUsers.setOnItemClickListener { _, _, position, _ ->
            val user = adapter.getItem(position)
            if (groupMode) {
                if (selected.containsKey(user.id)) selected.remove(user.id) else selected[user.id] = user
                adapter.selectedIds = selected.keys.toSet()
                adapter.notifyDataSetChanged()
                updateCreateGroupButton()
            } else {
                openDm(user)
            }
        }
        etSearch.requestFocus()
    }

    private fun setGroupMode(group: Boolean) {
        groupMode = group
        layoutGroupCreate.visibility = if (group) View.VISIBLE else View.GONE
        btnNewModeDm.alpha = if (group) 0.55f else 1f
        btnNewModeGroup.alpha = if (group) 1f else 0.55f
        if (!group) {
            selected.clear()
            adapter.selectedIds = emptySet()
            adapter.notifyDataSetChanged()
        }
        updateCreateGroupButton()
    }

    private fun updateCreateGroupButton() {
        btnCreateGroup.text = "ساخت گروه (${UiUtil.faNum(selected.size)} عضو انتخاب شده)"
        btnCreateGroup.isEnabled = selected.isNotEmpty() && !opening
    }

    private fun createGroup() {
        if (opening) return
        val title = etGroupTitle.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(this, "نام گروه را وارد کنید.", Toast.LENGTH_SHORT).show()
            etGroupTitle.requestFocus()
            return
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "دست‌کم یک عضو انتخاب کنید.", Toast.LENGTH_SHORT).show()
            return
        }
        opening = true
        updateCreateGroupButton()
        val memberIds = selected.keys.toList()
        Threads.runIo {
            try {
                val convId = app.api.createGroup(title, memberIds)
                Threads.runUi {
                    opening = false
                    startActivity(ChatActivity.intentForGroup(this, convId, title))
                    finish()
                }
            } catch (e: Exception) {
                Threads.runUi {
                    opening = false
                    updateCreateGroupButton()
                    Toast.makeText(this, e.message ?: "ساخت گروه ناموفق بود", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openDm(user: Profile) {
        if (opening) return
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

    private fun runSearch(query: String, seq: Int) {
        tvHint.visibility = View.VISIBLE
        tvHint.text = "در حال جستجو…"
        Threads.runIo {
            try {
                val results = app.api.searchUsers(query)
                Threads.runUi {
                    if (seq != searchSeq) return@runUi
                    adapter.selectedIds = selected.keys.toSet()
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
