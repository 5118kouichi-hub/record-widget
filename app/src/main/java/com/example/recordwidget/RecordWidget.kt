package com.example.recordwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** Home-screen receiver. Only (re)draws; playback state lives in [PlaybackService]. */
class RecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // After boot / process start the remembered state is "stopped" with no title, so this
        // shows the still record with the track playback will resume with.
        appWidgetManager.updateAppWidget(appWidgetIds, RecordWidget.buildViews(context))
    }
}

object RecordWidget {
    private const val MAIN_ACTIVITY = "com.example.recordwidget.MainActivity"

    private const val RC_TOGGLE = 1
    private const val RC_NEXT = 2
    private const val RC_OPEN_APP = 3
    private const val RC_PREV = 5

    /**
     * How the record moves. The launcher animates it (see tools/gen_widget_res.py); this
     * object only picks which pre-built animation to show and remembers the angle.
     */
    private enum class Phase { REST, SPINNING, STOPPING }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // All guarded by [lock].
    private var phase = Phase.REST
    private var restAngle = 0 // still record angle, multiple of STEP_DEG
    private var upAngle = 0 // angle the current spin-up started from
    private var upStartedAt = 0L // elapsedRealtime when the spin-up was pushed
    private var downAngle = 0 // angle the current spin-down started from
    private var downStartedAt = 0L
    private var thumbGeneration = 0 // bumped by refresh(); stale decodes are not cached
    private var lastTitle: String? = null
    private var lastTrackId: String? = null
    private var thumbCache: Pair<String, Bitmap?>? = null

    private val settle = Runnable {
        synchronized(lock) {
            if (phase != Phase.STOPPING) return@Runnable
            phase = Phase.REST
        }
        pushSoon()
    }

    @Volatile private var appContext: Context? = null

    /**
     * Pushes the state to every placed widget. [title]/[trackId] null = the track playback
     * will resume with. [playing] = playing or about to (buffering), so the record keeps
     * turning between tracks.
     */
    fun update(ctx: Context, playing: Boolean, title: String?, trackId: String? = null) {
        appContext = ctx.applicationContext
        synchronized(lock) {
            lastTitle = title
            lastTrackId = trackId
            val now = SystemClock.elapsedRealtime()
            if (playing && phase != Phase.SPINNING) {
                // Restarting while still slowing down: start from where the record is now.
                upAngle = if (phase == Phase.STOPPING) snap(downAngleAt(now)) else restAngle
                upStartedAt = now
                phase = Phase.SPINNING
                main.removeCallbacks(settle)
                main.removeCallbacks(beginStop)
            } else if (playing) {
                main.removeCallbacks(beginStop) // a brief stop (skip, track change) is over
            } else if (phase == Phase.SPINNING) {
                // Wait a moment: skipping a broken track or changing tracks passes through
                // "not playing" for a split second, and the record should not stutter.
                main.removeCallbacks(beginStop)
                main.postDelayed(beginStop, STOP_DEBOUNCE_MS)
            }
        }
        pushSoon()
    }

    private const val STOP_DEBOUNCE_MS = 400L

    private val beginStop = Runnable {
        synchronized(lock) {
            if (phase != Phase.SPINNING) return@Runnable
            val now = SystemClock.elapsedRealtime()
            downAngle = snap(angleAt(now))
            downStartedAt = now
            restAngle = (downAngle + RecordFrames.DOWN_DEG) % 360
            phase = Phase.STOPPING
            main.postDelayed(settle, RecordFrames.DOWN_MS + 300)
        }
        pushSoon()
    }

    /** List or thumbnail changed -> redraw without touching the motion. Any thread. */
    fun refresh(ctx: Context) {
        appContext = ctx.applicationContext
        synchronized(lock) {
            thumbCache = null
            thumbGeneration++
        }
        pushSoon()
    }

    /**
     * Every push runs on the main thread, one after another, and builds from the newest
     * state, so a slow background caller can never overwrite a newer picture.
     */
    private val pushRunnable = Runnable { appContext?.let { push(it) } }

    private fun pushSoon() {
        main.removeCallbacks(pushRunnable)
        main.post(pushRunnable)
    }

    /** By class name: keeps this file independent of MainActivity. */
    fun mainActivityIntent(ctx: Context): Intent =
        Intent().setClassName(ctx, MAIN_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun push(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, RecordWidgetProvider::class.java))
        if (ids.isEmpty()) return
        mgr.updateAppWidget(ids, buildViews(ctx))
    }

    /** Current angle of a spinning record, from the spin-up curve and then constant speed. */
    private fun angleAt(now: Long): Double {
        val t = (now - upStartedAt).coerceAtLeast(0L)
        val turned = if (t < RecordFrames.UP_MS) {
            val f = t.toDouble() / RecordFrames.UP_MS
            RecordFrames.UP_DEG * f * f // accelerate_quad
        } else {
            RecordFrames.UP_DEG + 360.0 * (t - RecordFrames.UP_MS) / RecordFrames.TURN_MS
        }
        return upAngle + turned
    }

    /** Angle while slowing down (decelerate_quad over DOWN_MS). */
    private fun downAngleAt(now: Long): Double {
        val f = ((now - downStartedAt).toDouble() / RecordFrames.DOWN_MS).coerceIn(0.0, 1.0)
        return downAngle + RecordFrames.DOWN_DEG * (1 - (1 - f) * (1 - f))
    }

    private fun snap(deg: Double): Int {
        val step = RecordFrames.STEP_DEG
        val n = (deg / step).roundToInt()
        return ((n * step) % 360 + 360) % 360
    }

    private fun index(angle: Int) = angle / RecordFrames.STEP_DEG

    /**
     * Android 12+: the launcher picks the small/medium/large record by the widget's size
     * (the record box is a fixed square so it is never squashed). Older: always small.
     */
    internal fun buildViews(ctx: Context): RemoteViews {
        val tracks = PlaybackState.playableTracks(ctx)
        val state = synchronized(lock) {
            Snapshot(phase, restAngle, upAngle, downAngle, lastTitle, lastTrackId)
        }
        val track = tracks.firstOrNull { it.id == state.trackId } ?: PlaybackState.resumeTrack(ctx, tracks)
        val thumb = track?.let { thumbnail(ctx, it.id) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return buildLayout(ctx, R.layout.widget_record, tracks, state, track, thumb)
        }
        return RemoteViews(
            mapOf(
                SizeF(170f, 96f) to buildLayout(ctx, R.layout.widget_record, tracks, state, track, thumb),
                SizeF(240f, 136f) to buildLayout(ctx, R.layout.widget_record_m, tracks, state, track, thumb),
                SizeF(318f, 180f) to buildLayout(ctx, R.layout.widget_record_l, tracks, state, track, thumb),
            ),
        )
    }

    private data class Snapshot(
        val phase: Phase,
        val restAngle: Int,
        val upAngle: Int,
        val downAngle: Int,
        val title: String?,
        val trackId: String?,
    )

    /** Decoded once per track; 128 px circle PNG written at import (Thumbnails.kt). */
    private fun thumbnail(ctx: Context, id: String): Bitmap? {
        val gen = synchronized(lock) {
            thumbCache?.let { if (it.first == id) return it.second }
            thumbGeneration
        }
        val file = TrackStore(ctx.applicationContext.filesDir).thumbFor(id)
        val bmp = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
        synchronized(lock) { if (gen == thumbGeneration) thumbCache = id to bmp }
        return bmp
    }

    private fun buildLayout(
        ctx: Context,
        layoutId: Int,
        tracks: List<Track>,
        s: Snapshot,
        track: Track?,
        thumb: Bitmap?,
    ): RemoteViews {
        val views = RemoteViews(ctx.packageName, layoutId)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val phase = if (tracks.isEmpty()) Phase.REST else s.phase

        // Exactly one record layer is visible. Re-sending the same visibility does not
        // restart a running animation, so title changes while playing stay smooth.
        val up = if (phase == Phase.SPINNING) RecordFrames.UP_IDS[index(s.upAngle)] else 0
        val down = if (phase == Phase.STOPPING) RecordFrames.DOWN_IDS[index(s.downAngle)] else 0
        for (id in RecordFrames.UP_IDS) views.setViewVisibility(id, if (id == up) View.VISIBLE else View.GONE)
        for (id in RecordFrames.DOWN_IDS) views.setViewVisibility(id, if (id == down) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.disc, if (phase == Phase.REST) View.VISIBLE else View.GONE)
        views.setImageViewResource(R.id.disc, RecordFrames.REST_DRAWABLES[index(s.restAngle)])

        if (thumb != null) {
            views.setImageViewBitmap(R.id.thumb, thumb)
            views.setViewVisibility(R.id.thumb, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.thumb, View.GONE)
        }

        val playing = phase == Phase.SPINNING
        views.setImageViewResource(R.id.tonearm, if (playing) R.drawable.player_tonearm_down else R.drawable.player_tonearm_up)

        if (tracks.isEmpty()) {
            // Nothing to play -> tapping the record opens the app to add sounds.
            views.setTextViewText(R.id.title, ctx.getString(R.string.widget_empty_hint))
            views.setContentDescription(R.id.disc_area, ctx.getString(R.string.cd_disc_empty))
            views.setOnClickPendingIntent(
                R.id.disc_area,
                PendingIntent.getActivity(ctx, RC_OPEN_APP, mainActivityIntent(ctx), flags),
            )
            views.setViewVisibility(R.id.next, View.GONE)
            views.setViewVisibility(R.id.prev, View.GONE)
            views.setViewVisibility(R.id.title, View.VISIBLE)
        } else {
            views.setTextViewText(R.id.title, s.title ?: track?.title)
            views.setContentDescription(
                R.id.disc_area,
                ctx.getString(if (playing) R.string.cd_disc_playing else R.string.cd_disc_stopped),
            )
            views.setOnClickPendingIntent(R.id.disc_area, servicePendingIntent(ctx, PlaybackService.ACTION_TOGGLE, RC_TOGGLE))
            views.setOnClickPendingIntent(R.id.next, servicePendingIntent(ctx, PlaybackService.ACTION_NEXT, RC_NEXT))
            views.setOnClickPendingIntent(R.id.prev, servicePendingIntent(ctx, PlaybackService.ACTION_PREV, RC_PREV))
            views.setViewVisibility(R.id.next, View.VISIBLE)
            views.setViewVisibility(R.id.prev, View.VISIBLE)
            // The small player has no room for a readable title on its front.
            views.setViewVisibility(R.id.title, if (layoutId == R.layout.widget_record) View.GONE else View.VISIBLE)
        }
        return views
    }

    private fun servicePendingIntent(ctx: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            ctx,
            requestCode,
            Intent(ctx, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
