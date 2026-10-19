package ir.mamoot.app.net

import ir.mamoot.app.BuildConfig
import ir.mamoot.app.storage.SecureStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

class ApiException(message: String, val code: String = "", val status: Int = 0) : Exception(message)

data class Profile(
    val id: String,
    val username: String,
    val displayName: String,
    val e2eIdentityKey: String?,
)

data class MemberInfo(
    val id: String,
    val username: String,
    val displayName: String,
    val lastReadAt: String,
    val e2eIdentityKey: String?,
)

data class LastMessageInfo(
    val id: String,
    val body: String,
    val senderId: String,
    val createdAt: String,
    val deletedAt: String?,
    val kind: String,
)

data class Conversation(
    val id: String,
    val kind: String,
    val title: String?,
    val createdAt: String,
    val lastMessageAt: String,
    val members: List<MemberInfo>,
    val lastMessage: LastMessageInfo?,
    val unreadCount: Int,
)

data class MessageRow(
    val id: String,
    val clientId: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val kind: String,
    val createdAt: String,
    val editedAt: String?,
    val deletedAt: String?,
)

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val expiresAtMs: Long,
)

/**
 * Minimal Supabase client over HttpURLConnection: Auth + PostgREST + RPC.
 * Only the publishable key is embedded; every request is authorized by
 * Postgres RLS. Delta-only queries, tight column selection and gzip keep
 * traffic tiny (free-plan egress and Iranian mobile data friendly).
 */
class SupabaseApi(private val secure: SecureStore) {
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    @Volatile
    var session: SessionTokens? = loadSession()
        private set

    val myUserId: String?
        get() = session?.userId

    // ---------------------------------------------------------- auth
    fun signUp(email: String, password: String): SessionTokens? {
        val body = JSONObject().put("email", email).put("password", password)
        val res = rawRequest("POST", "/auth/v1/signup", body.toString(), anonKey, null)
        throwIfError(res)
        val json = JSONObject(res.body)
        if (!json.has("access_token")) return null // email confirmation pending
        return parseAndSaveSession(json)
    }

    fun signIn(email: String, password: String): SessionTokens {
        val body = JSONObject().put("email", email).put("password", password)
        val res = rawRequest("POST", "/auth/v1/token?grant_type=password", body.toString(), anonKey, null)
        throwIfError(res)
        return parseAndSaveSession(JSONObject(res.body))
    }

    fun resetPassword(email: String) {
        val body = JSONObject().put("email", email)
        val res = rawRequest("POST", "/auth/v1/recover", body.toString(), anonKey, null)
        throwIfError(res)
    }

    fun signOut() {
        try {
            val token = session?.accessToken
            if (token != null) {
                rawRequest("POST", "/auth/v1/logout", "{}", token, null)
            }
        } catch (e: Exception) {
            // Local sign-out proceeds regardless.
        }
        session = null
        secure.removeSecret(KEY_SESSION)
    }

    @Synchronized
    fun refreshSession(): Boolean {
        val current = session ?: return false
        return try {
            val body = JSONObject().put("refresh_token", current.refreshToken)
            val res = rawRequest("POST", "/auth/v1/token?grant_type=refresh_token", body.toString(), anonKey, null)
            if (res.status !in 200..299) {
                false
            } else {
                parseAndSaveSession(JSONObject(res.body))
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureFreshToken() {
        val current = session ?: return
        if (current.expiresAtMs < System.currentTimeMillis() + 60_000) {
            refreshSession()
        }
    }

    private fun parseAndSaveSession(json: JSONObject): SessionTokens {
        val user = json.optJSONObject("user") ?: JSONObject()
        val expiresAtMs = when {
            json.has("expires_at") && json.optLong("expires_at") > 0 -> json.optLong("expires_at") * 1000
            json.has("expires_in") -> System.currentTimeMillis() + json.optLong("expires_in") * 1000
            else -> System.currentTimeMillis() + 3600_000
        }
        val tokens = SessionTokens(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            userId = user.optString("id"),
            email = user.optString("email"),
            expiresAtMs = expiresAtMs,
        )
        session = tokens
        val stored = JSONObject()
            .put("access_token", tokens.accessToken)
            .put("refresh_token", tokens.refreshToken)
            .put("user_id", tokens.userId)
            .put("email", tokens.email)
            .put("expires_at_ms", tokens.expiresAtMs)
        secure.putSecret(KEY_SESSION, stored.toString())
        return tokens
    }

    private fun loadSession(): SessionTokens? {
        val raw = secure.getSecret(KEY_SESSION) ?: return null
        return try {
            val json = JSONObject(raw)
            SessionTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                userId = json.getString("user_id"),
                email = json.optString("email"),
                expiresAtMs = json.optLong("expires_at_ms"),
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------- profiles
    fun fetchProfile(userId: String): Profile? {
        val path = "/rest/v1/profiles?select=id,username,display_name,e2e_identity_key&id=eq.${enc(userId)}"
        val arr = JSONArray(authed("GET", path, null, null))
        if (arr.length() == 0) return null
        return parseProfile(arr.getJSONObject(0))
    }

    fun createProfile(userId: String, username: String, displayName: String): Profile {
        val body = JSONObject()
            .put("id", userId)
            .put("username", username.trim().lowercase())
            .put("display_name", displayName.trim())
        val arr = JSONArray(authed("POST", "/rest/v1/profiles", body, "return=representation"))
        return parseProfile(arr.getJSONObject(0))
    }

    fun updateProfile(userId: String, username: String, displayName: String): Profile {
        val body = JSONObject()
            .put("username", username.trim().lowercase())
            .put("display_name", displayName.trim())
        val arr = JSONArray(authed("PATCH", "/rest/v1/profiles?id=eq.${enc(userId)}", body, "return=representation"))
        return parseProfile(arr.getJSONObject(0))
    }

    fun updateIdentityKey(userId: String, identityKeyB64: String) {
        val body = JSONObject().put("e2e_identity_key", identityKeyB64)
        authed("PATCH", "/rest/v1/profiles?id=eq.${enc(userId)}", body, "return=minimal")
    }

    private fun parseProfile(json: JSONObject): Profile = Profile(
        id = json.getString("id"),
        username = json.optString("username"),
        displayName = json.optString("display_name"),
        e2eIdentityKey = json.optStringOrNull("e2e_identity_key"),
    )

    // ---------------------------------------------------------- chat RPC / REST
    fun searchUsers(query: String): List<Profile> {
        val body = JSONObject().put("p_query", query).put("p_limit", 20)
        val arr = JSONArray(authed("POST", "/rest/v1/rpc/search_users", body, null))
        return List(arr.length()) { parseProfile(arr.getJSONObject(it)) }
    }

    fun listConversations(): List<Conversation> {
        val arr = JSONArray(authed("POST", "/rest/v1/rpc/list_conversations", JSONObject(), null))
        return List(arr.length()) { i ->
            val json = arr.getJSONObject(i)
            val membersArr = json.optJSONArray("members") ?: JSONArray()
            val members = List(membersArr.length()) { j ->
                val m = membersArr.getJSONObject(j)
                MemberInfo(
                    id = m.getString("id"),
                    username = m.optString("username"),
                    displayName = m.optString("display_name"),
                    lastReadAt = m.optString("last_read_at"),
                    e2eIdentityKey = m.optStringOrNull("e2e_identity_key"),
                )
            }
            val last = json.optJSONObjectOrNull("last_message")?.let { lm ->
                LastMessageInfo(
                    id = lm.optString("id"),
                    body = lm.optString("body"),
                    senderId = lm.optString("sender_id"),
                    createdAt = lm.optString("created_at"),
                    deletedAt = lm.optStringOrNull("deleted_at"),
                    kind = lm.optString("kind", "text"),
                )
            }
            Conversation(
                id = json.getString("id"),
                kind = json.optString("kind", "dm"),
                title = json.optStringOrNull("title"),
                createdAt = json.optString("created_at"),
                lastMessageAt = json.optString("last_message_at"),
                members = members,
                lastMessage = last,
                unreadCount = json.optLong("unread_count").toInt(),
            )
        }
    }

    fun getOrCreateDm(otherUserId: String): String {
        val body = JSONObject().put("p_other", otherUserId)
        val raw = authed("POST", "/rest/v1/rpc/get_or_create_dm", body, null)
        return raw.trim().trim('"')
    }

    fun markRead(conversationId: String) {
        val body = JSONObject().put("p_conversation", conversationId)
        authed("POST", "/rest/v1/rpc/mark_read", body, null)
    }


    fun fetchMessagesBefore(conversationId: String, beforeIso: String, limit: Int = 100): List<MessageRow> {
        val path = "/rest/v1/messages?select=$MESSAGE_COLUMNS" +
            "&conversation_id=eq.${enc(conversationId)}" +
            "&created_at=lt.${enc(beforeIso)}" +
            "&order=created_at.desc,id.desc&limit=$limit"
        return parseMessages(JSONArray(authed("GET", path, null, null))).asReversed()
    }

    /**
     * Reconciliation for edits/deletes of older messages: the created_at
     * watermark sync can never see them, so poll the edited_at/deleted_at
     * columns with their own watermark (usually returns zero rows).
     */
    fun fetchEditedOrDeletedSince(conversationId: String, sinceIso: String): List<MessageRow> {
        val path = "/rest/v1/messages?select=$MESSAGE_COLUMNS" +
            "&conversation_id=eq.${enc(conversationId)}" +
            "&or=(edited_at.gte.${enc(sinceIso)},deleted_at.gte.${enc(sinceIso)})" +
            "&order=created_at.asc,id.asc&limit=100"
        return parseMessages(JSONArray(authed("GET", path, null, null)))
    }

    fun updateMessageBody(serverId: String, newBody: String): MessageRow {
        val body = JSONObject().put("body", newBody)
        val arr = JSONArray(authed("PATCH", "/rest/v1/messages?id=eq.${enc(serverId)}", body, "return=representation"))
        return parseMessage(arr.getJSONObject(0))
    }

    fun createGroup(title: String, memberIds: List<String>): String {
        val ids = JSONArray()
        memberIds.forEach { ids.put(it) }
        val body = JSONObject().put("p_title", title).put("p_member_ids", ids)
        val raw = authed("POST", "/rest/v1/rpc/create_group", body, null)
        return raw.trim().trim('"')
    }

    fun addGroupMembers(conversationId: String, userIds: List<String>) {
        val ids = JSONArray()
        userIds.forEach { ids.put(it) }
        val body = JSONObject().put("p_conversation", conversationId).put("p_user_ids", ids)
        authed("POST", "/rest/v1/rpc/add_group_members", body, null)
    }

    fun leaveConversation(conversationId: String) {
        val body = JSONObject().put("p_conversation", conversationId)
        authed("POST", "/rest/v1/rpc/leave_conversation", body, null)
    }

    fun myBlockedIds(): List<String> {
        val me = myUserId ?: return emptyList()
        val arr = JSONArray(authed("GET", "/rest/v1/blocks?select=blocked&blocker=eq.${enc(me)}", null, null))
        return List(arr.length()) { arr.getJSONObject(it).getString("blocked") }
    }

    fun blockUser(userId: String) {
        val me = myUserId ?: throw ApiException("وارد نشده‌اید")
        val body = JSONObject().put("blocker", me).put("blocked", userId)
        try {
            authed("POST", "/rest/v1/blocks", body, "return=minimal")
        } catch (e: ApiException) {
            if (e.code != "23505") throw e // already blocked
        }
    }

    fun unblockUser(userId: String) {
        val me = myUserId ?: return
        authed("DELETE", "/rest/v1/blocks?blocker=eq.${enc(me)}&blocked=eq.${enc(userId)}", null, "return=minimal")
    }

    fun updatePassword(newPassword: String) {
        val body = JSONObject().put("password", newPassword)
        authed("PUT", "/auth/v1/user", body, null)
    }

    fun deleteMyAccount() {
        authed("POST", "/rest/v1/rpc/delete_my_account", JSONObject(), null)
    }

    fun fetchMessagesAfter(conversationId: String, afterIso: String): List<MessageRow> {
        val path = "/rest/v1/messages?select=$MESSAGE_COLUMNS" +
            "&conversation_id=eq.${enc(conversationId)}" +
            "&created_at=gte.${enc(afterIso)}" +
            "&order=created_at.asc,id.asc&limit=200"
        return parseMessages(JSONArray(authed("GET", path, null, null)))
    }

    fun fetchLatestMessages(conversationId: String, limit: Int = 100): List<MessageRow> {
        val path = "/rest/v1/messages?select=$MESSAGE_COLUMNS" +
            "&conversation_id=eq.${enc(conversationId)}" +
            "&order=created_at.desc,id.desc&limit=$limit"
        return parseMessages(JSONArray(authed("GET", path, null, null))).asReversed()
    }

    fun insertMessage(
        conversationId: String,
        senderId: String,
        bodyText: String,
        kind: String,
        clientId: String,
    ): MessageRow {
        val body = JSONObject()
            .put("conversation_id", conversationId)
            .put("sender_id", senderId)
            .put("body", bodyText)
            .put("kind", kind)
            .put("client_id", clientId)
        val arr = JSONArray(authed("POST", "/rest/v1/messages", body, "return=representation"))
        return parseMessage(arr.getJSONObject(0))
    }

    fun deleteMessage(serverId: String) {
        val body = JSONObject().put("deleted_at", Instant.now().toString())
        authed("PATCH", "/rest/v1/messages?id=eq.${enc(serverId)}", body, "return=minimal")
    }

    private fun parseMessages(arr: JSONArray): List<MessageRow> =
        List(arr.length()) { parseMessage(arr.getJSONObject(it)) }

    private fun parseMessage(json: JSONObject): MessageRow = MessageRow(
        id = json.getString("id"),
        clientId = json.optString("client_id"),
        conversationId = json.getString("conversation_id"),
        senderId = json.getString("sender_id"),
        body = json.optString("body"),
        kind = json.optString("kind", "text"),
        createdAt = json.getString("created_at"),
        editedAt = json.optStringOrNull("edited_at"),
        deletedAt = json.optStringOrNull("deleted_at"),
    )

    // ---------------------------------------------------------- HTTP plumbing
    private data class HttpResult(val status: Int, val body: String)

    private fun authed(method: String, path: String, body: JSONObject?, prefer: String?): String {
        ensureFreshToken()
        var result = rawRequest(method, path, body?.toString(), session?.accessToken ?: anonKey, prefer)
        if (result.status == 401 && refreshSession()) {
            result = rawRequest(method, path, body?.toString(), session?.accessToken ?: anonKey, prefer)
        }
        throwIfError(result)
        return result.body
    }

    private fun rawRequest(method: String, path: String, body: String?, bearer: String, prefer: String?): HttpResult {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("X-Client-Info", "mamoot-android/0.3.0")
            if (prefer != null) setRequestProperty("Prefer", prefer)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = if (stream == null) {
                ""
            } else {
                val decoded = if (connection.contentEncoding?.lowercase() == "gzip") GZIPInputStream(stream) else stream
                BufferedReader(InputStreamReader(decoded, Charsets.UTF_8)).use { it.readText() }
            }
            return HttpResult(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun throwIfError(result: HttpResult) {
        if (result.status in 200..299) return
        var message = result.body.take(240)
        var code = ""
        try {
            val json = JSONObject(result.body)
            message = sequenceOf("message", "msg", "error_description", "error", "hint")
                .map { json.optString(it) }
                .firstOrNull { it.isNotEmpty() } ?: message
            code = json.optString("code")
        } catch (e: Exception) {
            // keep raw body snippet
        }
        throw ApiException(translateError(message), code, result.status)
    }

    private fun translateError(raw: String): String {
        val m = raw.lowercase()
        return when {
            "rate_limit_exceeded" in m -> "خیلی سریع پیام می‌فرستید؛ کمی صبر کنید."
            "invalid login credentials" in m -> "ایمیل یا رمز عبور اشتباه است."
            "already registered" in m || "already been registered" in m -> "این ایمیل قبلاً ثبت شده است؛ وارد شوید."
            "email not confirmed" in m -> "ایمیل شما هنوز تأیید نشده است."
            "duplicate key" in m && "username" in m -> "این نام کاربری قبلاً گرفته شده است."
            "password should be" in m -> "رمز عبور ضعیف است."
            m.trim() == "blocked" || "blocked" in m -> "امکان ارسال پیام به این کاربر وجود ندارد (مسدودیت)."
            "invalid_title" in m -> "نام گروه را وارد کنید (حداکثر ۸۰ نویسه)."
            "invalid_member_count" in m -> "برای گروه دست‌کم یک عضو انتخاب کنید."
            "group_full" in m -> "ظرفیت گروه تکمیل است."
            "not_a_group" in m || "cannot_leave" in m -> "این عملیات برای این گفتگو ممکن نیست."
            "user_not_found" in m -> "کاربر یافت نشد."
            else -> raw.ifEmpty { "خطای شبکه یا سرور" }
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val KEY_SESSION = "session_v1"
        const val MESSAGE_COLUMNS = "id,client_id,conversation_id,sender_id,body,kind,created_at,edited_at,deleted_at"
    }
}

fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name)
    return value.ifEmpty { null }
}

fun JSONObject.optJSONObjectOrNull(name: String): JSONObject? {
    if (!has(name) || isNull(name)) return null
    return optJSONObject(name)
}
