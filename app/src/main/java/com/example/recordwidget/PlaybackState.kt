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

    // ---- favorites ------------------------------------------------------------------------

    private const val FAV_PREFS = "favorites"
    private const val KEY_FAV_IDS = "ids"
    private const val KEY_FAV_ONLY = "only"
    private const val KEY_VOLUME = "volume"

    fun favorites(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FAV_IDS, emptySet())!!.toSet()

    fun isFavorite(ctx: Context, id: String) = id in favorites(ctx)

    fun setFavorite(ctx: Context, id: String, on: Boolean) {
        val set = favorites(ctx).toMutableSet()
        if (on) set.add(id) else set.remove(id)
        ctx.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FAV_IDS, set).apply()
    }

    /** Setting: pause when the screen goes off (default off: keep playing). */
    fun pauseOnScreenOff(ctx: Context): Boolean =
        ctx.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).getBoolean("pause_screen_off", false)

    fun setPauseOnScreenOff(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).edit().putBoolean("pause_screen_off", on).apply()
    }

    /** "Play favorites only" switch (in the floating record's list). */
    fun favoritesOnly(ctx: Context): Boolean =
        ctx.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FAV_ONLY, false)

    fun setFavoritesOnly(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FAV_ONLY, on).apply()
    }

    /**
     * What the player plays: the playable tracks, or only the favorite ones when that switch
     * is on. With the switch on but no playable favorite, everything plays (never silence).
     */
    fun queueTracks(ctx: Context): List<Track> {
        val all = playableTracks(ctx)
        if (!favoritesOnly(ctx)) return all
        val fav = favorites(ctx)
        val only = all.filter { it.id in fav }
        return only.ifEmpty { all }
    }

    // ---- volume (the app's own, inside the phone's media volume) ---------------------------

    fun volume(ctx: Context): Float =
        ctx.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f)

    fun setVolume(ctx: Context, v: Float) {
        ctx.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_VOLUME, v.coerceIn(0f, 1f)).apply()
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
