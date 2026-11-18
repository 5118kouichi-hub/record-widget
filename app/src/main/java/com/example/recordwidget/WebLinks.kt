package com.example.recordwidget

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/** Web links that can be streamed: direct links to an audio/video file or an HLS stream. */
object WebLinks {
    private const val TAG = "WebLinks"

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

    /** What reading the link told us. */
    class Probe(val title: String?, val hasVideo: Boolean)

    /**
     * Network call, off the main thread. Null when the link is not readable media.
     * HLS playlists (.m3u8) cannot be opened by MediaMetadataRetriever but play fine.
     */
    fun probe(url: String): Probe? {
        val path = Uri.parse(url).path?.lowercase().orEmpty()
        if (path.endsWith(".m3u8")) return Probe(null, false)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(url, HashMap())
            val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val video = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            if (!audio && !mime.startsWith("audio/")) return null
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            Probe(title?.ifEmpty { null }, video)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $url", e)
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "release failed", e)
            }
        }
    }

    /** "song%20name.mp3" -> "song name". */
    fun nameFromUrl(url: String): String? {
        val last = Uri.parse(url).lastPathSegment ?: return null
        val base = last.substringBeforeLast('.', last).trim()
        return base.ifEmpty { null }
    }
}
