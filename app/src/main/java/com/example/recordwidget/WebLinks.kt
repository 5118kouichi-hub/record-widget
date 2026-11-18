package com.example.recordwidget

import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Web links that can be streamed: direct links to an audio/video file or an HLS stream. */
object WebLinks {
    private const val TAG = "WebLinks"
    private const val TIMEOUT_MS = 10_000

    enum class Check { OK, INVALID, UNSUPPORTED_SITE }

    /**
     * Video/music sites whose page links are not media files. Their players are not
     * reachable from other apps (and their terms forbid it), so say so instead of failing later.
     */
    private val PAGE_SITES = listOf(
        "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "x.com", "twitter.com",
        "spotify.com", "nicovideo.jp", "nico.ms", "music.apple.com", "soundcloud.com",
        "facebook.com", "vimeo.com",
    )

    private val VIDEO_EXT = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "ts")
    private val MEDIA_EXT = VIDEO_EXT + setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "m3u8")

    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String?): String? = text?.let { URL_RE.find(it)?.value }

    fun check(url: String): Check {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) return Check.INVALID
        if (PAGE_SITES.any { host == it || host.endsWith(".$it") }) return Check.UNSUPPORTED_SITE
        return Check.OK
    }

    /** Percent-encodes spaces and non-ASCII characters (typed or copied links). */
    fun normalize(url: String): String = try {
        val u = URL(url)
        URI(u.protocol, u.userInfo, u.host, u.port, u.path, u.query, u.ref).toASCIIString()
    } catch (e: Exception) {
        url
    }

    /** What reading the link told us. */
    class Probe(val hasVideo: Boolean)

    /**
     * Network call, off the main thread. Asks the server for the first 64 KB only (so a big
     * file is never downloaded here), follows redirects incl. http<->https, and gives up
     * after [TIMEOUT_MS]. Null when the link is not audio/video (e.g. a web page).
     */
    fun probe(url: String): Probe? {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection)
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Range", "bytes=0-65535")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val ext = extension(current)
                return when {
                    type.startsWith("video/") -> Probe(hasVideo = true)
                    type.startsWith("audio/") || type.contains("mpegurl") || type == "application/ogg" ->
                        Probe(hasVideo = false)
                    type.isEmpty() || type.contains("octet-stream") ->
                        if (ext in MEDIA_EXT || ext.isEmpty()) Probe(hasVideo = ext in VIDEO_EXT) else null
                    else -> null // text/html and friends: a page, not a file
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot read $current", e)
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null // too many redirects
    }

    private fun extension(url: String): String =
        Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /** "song%20name.mp3" -> "song name". */
    fun nameFromUrl(url: String): String? {
        val last = Uri.parse(url).lastPathSegment ?: return null
        val base = last.substringBeforeLast('.', last).trim()
        return base.ifEmpty { null }
    }
}
