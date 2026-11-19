package com.example.recordwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Supabase project behind the shared lists (plain HTTPS, no SDK).
 *
 * Identity: an anonymous Supabase user created on first use; its tokens live in the
 * "cloud" SharedPreferences. Only the publishable key is in the app; every rule about who
 * may do what is enforced by the database (supabase/schema.sql).
 *
 * Everything here blocks: call it off the main thread.
 */
class Cloud(ctx: Context) {
    class CloudException(message: String, val status: Int = 0) : IOException(message)

    data class Profile(val name: String, val isAdmin: Boolean)

    data class SharedList(
        val id: String,
        val title: String,
        val ownerId: String,
        val ownerName: String,
        val isPublic: Boolean,
        val likes: Int,
        val trackCount: Int,
    )

    data class SharedTrack(val id: String, val title: String, val path: String)

    private val prefs = ctx.applicationContext.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    val userId: String? get() = prefs.getString(K_UID, null)

    // ---- session -------------------------------------------------------------------------

    /** A valid access token, signing up anonymously the first time and refreshing when old. */
    private fun token(): String = synchronized(LOCK) {
        val access = prefs.getString(K_ACCESS, null)
        val refresh = prefs.getString(K_REFRESH, null)
        val expires = prefs.getLong(K_EXPIRES, 0)
        val now = System.currentTimeMillis() / 1000
        if (access != null && now < expires - 60) return access
        val signUp = { JSONObject(request("POST", "/auth/v1/signup", jsonBody(JSONObject().put("data", JSONObject())), auth = null)) }
        val json = if (refresh == null) {
            signUp()
        } else {
            try {
                JSONObject(
                    request(
                        "POST",
                        "/auth/v1/token?grant_type=refresh_token",
                        jsonBody(JSONObject().put("refresh_token", refresh)),
                        auth = null,
                    ),
                )
            } catch (e: CloudException) {
                // The login was revoked (e.g. the user was deleted): start over as a new member
                // rather than failing forever. Network errors are IOExceptions, not this.
                if (e.status != 400 && e.status != 401 && e.status != 403) throw e
                signUp()
            }
        }
        val newAccess = json.getString("access_token")
        prefs.edit()
            .putString(K_ACCESS, newAccess)
            .putString(K_REFRESH, json.getString("refresh_token"))
            .putLong(K_EXPIRES, now + json.optLong("expires_in", 3600))
            .putString(K_UID, json.getJSONObject("user").getString("id"))
            .commit()
        newAccess
    }

    private fun uid(): String {
        token()
        return userId ?: throw CloudException("no user")
    }

    // ---- profile -------------------------------------------------------------------------

    fun myProfile(): Profile? {
        val id = uid()
        val arr = JSONArray(rest("GET", "/rest/v1/profiles?select=name,is_admin&id=eq.$id"))
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return Profile(o.getString("name"), o.optBoolean("is_admin"))
    }

    fun setName(name: String) {
        val id = uid()
        if (myProfile() == null) {
            rest("POST", "/rest/v1/profiles", JSONObject().put("id", id).put("name", name))
        } else {
            rest("PATCH", "/rest/v1/profiles?id=eq.$id", JSONObject().put("name", name))
        }
    }

    // ---- lists ---------------------------------------------------------------------------

    fun publicLists(): List<SharedList> =
        lists("is_public=eq.true&order=like_count.desc,created_at.desc&limit=100")

    fun myLists(): List<SharedList> = lists("owner=eq.${uid()}&order=created_at.desc")

    private fun lists(filter: String): List<SharedList> {
        val arr = JSONArray(
            rest("GET", "/rest/v1/playlists?select=id,title,owner,is_public,like_count,profiles(name),playlist_tracks(count)&$filter"),
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val counts = o.optJSONArray("playlist_tracks")
            SharedList(
                id = o.getString("id"),
                title = o.getString("title"),
                ownerId = o.getString("owner"),
                ownerName = o.optJSONObject("profiles")?.optString("name").orEmpty(),
                isPublic = o.optBoolean("is_public"),
                likes = o.optInt("like_count"),
                trackCount = if (counts != null && counts.length() > 0) counts.getJSONObject(0).optInt("count") else 0,
            )
        }
    }

    fun myLikes(): Set<String> {
        val arr = JSONArray(rest("GET", "/rest/v1/likes?select=playlist_id&user_id=eq.${uid()}"))
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("playlist_id") }.toSet()
    }

    fun setLiked(listId: String, liked: Boolean) {
        val id = uid()
        if (liked) {
            rest("POST", "/rest/v1/likes", JSONObject().put("playlist_id", listId).put("user_id", id))
        } else {
            rest("DELETE", "/rest/v1/likes?playlist_id=eq.$listId&user_id=eq.$id")
        }
    }

    fun setPublic(listId: String, public: Boolean) {
        rest("PATCH", "/rest/v1/playlists?id=eq.$listId", JSONObject().put("is_public", public))
    }

    fun tracks(listId: String): List<SharedTrack> {
        val arr = JSONArray(
            rest("GET", "/rest/v1/playlist_tracks?select=id,title,storage_path&playlist_id=eq.$listId&order=position"),
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SharedTrack(o.getString("id"), o.getString("title"), o.getString("storage_path"))
        }
    }

    /** Bytes used by all uploaded audio (the free plan has 1 GB). */
    fun storageUsed(): Long = rest("POST", "/rest/v1/rpc/storage_used", JSONObject()).trim().toLongOrNull() ?: 0L

    /**
     * Creates a list and uploads the audio files. On failure everything already made is
     * removed again, so no half list is left behind.
     */
    fun createList(title: String, public: Boolean, items: List<Pair<String, File>>, onProgress: (Int) -> Unit) {
        val owner = uid()
        val created = JSONArray(
            rest(
                "POST",
                "/rest/v1/playlists",
                JSONObject().put("owner", owner).put("title", title).put("is_public", false),
                prefer = "return=representation",
            ),
        )
        val listId = created.getJSONObject(0).getString("id")
        val uploaded = mutableListOf<String>()
        try {
            val rows = JSONArray()
            items.forEachIndexed { i, (name, file) ->
                onProgress(i)
                val path = "$owner/$listId/$i.m4a"
                upload(path, file)
                uploaded += path
                rows.put(
                    JSONObject().put("playlist_id", listId).put("position", i)
                        .put("title", name.take(80)).put("storage_path", path).put("size_bytes", file.length()),
                )
            }
            rest("POST", "/rest/v1/playlist_tracks", rows)
            // Shown to others only once every track is there.
            if (public) setPublic(listId, true)
        } catch (e: Exception) {
            runCatching { removeFiles(uploaded) }
            runCatching { rest("DELETE", "/rest/v1/playlists?id=eq.$listId") }
            throw e
        }
    }

    fun deleteList(listId: String) {
        val paths = tracks(listId).map { it.path }
        removeFiles(paths)
        rest("DELETE", "/rest/v1/playlists?id=eq.$listId")
    }

    /** A playable link to one track, valid for [SIGN_SECONDS]. */
    fun signedUrl(path: String): String {
        val o = JSONObject(rest("POST", "/storage/v1/object/sign/audio/$path", JSONObject().put("expiresIn", SIGN_SECONDS)))
        val rel = o.optString("signedURL").ifEmpty { o.optString("signedUrl") }
        if (rel.isEmpty()) throw CloudException("no signed url")
        return if (rel.startsWith("http")) rel else "$BASE/storage/v1$rel"
    }

    private fun upload(path: String, file: File) {
        request(
            "POST",
            "/storage/v1/object/audio/$path",
            Body("audio/mp4", file.length()) { out -> file.inputStream().use { it.copyTo(out) } },
            auth = token(),
        )
    }

    private fun removeFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        request("DELETE", "/storage/v1/object/audio", jsonBody(JSONObject().put("prefixes", JSONArray(paths))), auth = token())
    }

    // ---- http ----------------------------------------------------------------------------

    private class Body(val type: String, val length: Long, val write: (OutputStream) -> Unit)

    private fun jsonBody(json: Any): Body {
        val bytes = json.toString().toByteArray()
        return Body("application/json", bytes.size.toLong()) { it.write(bytes) }
    }

    private fun rest(method: String, path: String, json: Any? = null, prefer: String = "return=minimal"): String =
        request(method, path, json?.let { jsonBody(it) }, auth = token(), prefer = prefer)

    private fun request(method: String, path: String, body: Body?, auth: String?, prefer: String? = null): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("apikey", KEY)
            if (auth != null) c.setRequestProperty("Authorization", "Bearer $auth")
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", body.type)
                c.setFixedLengthStreamingMode(body.length)
                c.outputStream.use { body.write(it) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw CloudException(errorMessage(text, code), code)
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun errorMessage(text: String, code: Int): String {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val msg = o?.let { it.optString("msg").ifEmpty { it.optString("message") }.ifEmpty { it.optString("error_description") } }
        return "HTTP $code" + if (msg.isNullOrEmpty()) "" else ": $msg"
    }

    companion object {
        const val BASE = "https://lrpllrygddwccvbjawug.supabase.co"

        /** Publishable key: safe in the app, the database rules do the protecting. */
        const val KEY = "sb_publishable_h0uLDg-ljtms3hQG9F0Ctg_k-l3T5ez"

        const val SIGN_SECONDS = 60 * 60 * 24 * 365
        const val FREE_BYTES = 1_000_000_000L
        const val MAX_FILE_BYTES = 50L * 1024 * 1024

        private const val K_ACCESS = "access"
        private const val K_REFRESH = "refresh"
        private const val K_EXPIRES = "expires"
        private const val K_UID = "uid"
        private val LOCK = Any()
    }
}
