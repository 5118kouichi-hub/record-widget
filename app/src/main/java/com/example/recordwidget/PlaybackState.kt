package com.example.recordwidget

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * State shared by [PlaybackService] and [RecordWidget]: which tracks are playable, and where
 * playback resumes after the service was stopped (SharedPreferences "playback").
 */
object PlaybackState {
    private const val TAG = "PlaybackState"
    private const val PREFS = "playback"
    private const val KEY_ID = "lastMediaId"
    private const val KEY_POS = "lastPositionMs"

    /**
     * The single "playable" predicate: tracks in list order that are web tracks or whose audio
     * file exists.
     * The widget's empty state and the service's queue both use this, so they always agree.
     */
    fun playableTracks(ctx: Context): List<Track> {
        val store = TrackStore(ctx.applicationContext.filesDir)
        val all = try {
            store.all()
        } catch (e: IOException) {
            Log.w(TAG, "cannot read track list", e)
            emptyList()
        }
        return all.filter { it.isRemote || store.fileFor(it).isFile }
    }

    fun saveResume(ctx: Context, mediaId: String?, positionMs: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, mediaId)
            .putLong(KEY_POS, positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun resumeId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ID, null)

    fun resumePositionMs(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_POS, 0L)

    /** The track playback will resume with; the first track if nothing (valid) is saved. */
    fun resumeTrack(ctx: Context, tracks: List<Track>): Track? {
        val id = resumeId(ctx)
        return tracks.firstOrNull { it.id == id } ?: tracks.firstOrNull()
    }

    fun resumeTitle(ctx: Context, tracks: List<Track>): String? = resumeTrack(ctx, tracks)?.title
}
