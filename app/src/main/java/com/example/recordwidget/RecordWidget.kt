package com.example.recordwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/** Home-screen receiver. Only (re)draws; playback state lives in [PlaybackService]. */
class RecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // After boot / process start the remembered state is "stopped" with no title, so this
        // shows the stopped record with the title of the track playback will resume with
        // (saved in PlaybackState; first track if nothing saved) (Review Focus 5). If the
        // process is alive (widget added while playing) it shows the live state instead.
        appWidgetManager.updateAppWidget(
            appWidgetIds,
            RecordWidget.buildViews(context, RecordWidget.lastPlaying, RecordWidget.lastTitle),
        )
    }
}

object RecordWidget {
    private const val MAIN_ACTIVITY = "com.example.recordwidget.MainActivity"

    private const val RC_TOGGLE = 1
    private const val RC_NEXT = 2
    private const val RC_OPEN_APP = 3

    @Volatile internal var lastPlaying = false
        private set

    @Volatile internal var lastTitle: String? = null
        private set

    /** Pushes the state to every placed widget. [title] null = title of the track to resume. */
    fun update(ctx: Context, playing: Boolean, title: String?) {
        lastPlaying = playing
        lastTitle = title
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, RecordWidgetProvider::class.java))
        if (ids.isEmpty()) return
        mgr.updateAppWidget(ids, buildViews(ctx, playing, title))
    }

    /** By class name: MainActivity is added in a later task. */
    fun mainActivityIntent(ctx: Context): Intent =
        Intent().setClassName(ctx, MAIN_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Android 12+: the launcher picks the small/medium/large record by the widget's size
     * (the disc box must be a fixed square so the spinning record is never squashed).
     * Older versions always get the small one.
     */
    internal fun buildViews(ctx: Context, playing: Boolean, title: String?): RemoteViews {
        val tracks = PlaybackState.playableTracks(ctx)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return buildLayout(ctx, R.layout.widget_record, tracks, playing, title)
        }
        return RemoteViews(
            mapOf(
                SizeF(110f, 110f) to buildLayout(ctx, R.layout.widget_record, tracks, playing, title),
                SizeF(140f, 170f) to buildLayout(ctx, R.layout.widget_record_m, tracks, playing, title),
                SizeF(200f, 230f) to buildLayout(ctx, R.layout.widget_record_l, tracks, playing, title),
            ),
        )
    }

    private fun buildLayout(
        ctx: Context,
        layoutId: Int,
        tracks: List<Track>,
        playing: Boolean,
        title: String?,
    ): RemoteViews {
        val views = RemoteViews(ctx.packageName, layoutId)
        // Playing: show the turning record (indeterminate ProgressBar, animated by the
        // launcher itself); otherwise the still one.
        val spinning = playing && tracks.isNotEmpty()
        views.setViewVisibility(R.id.disc_spin, if (spinning) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.disc, if (spinning) View.GONE else View.VISIBLE)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        if (tracks.isEmpty()) {
            // Review Focus 2: nothing to play -> tapping the record opens the app to import.
            views.setTextViewText(R.id.title, ctx.getString(R.string.widget_empty_hint))
            views.setImageViewResource(R.id.tonearm, R.drawable.tonearm_up)
            views.setContentDescription(R.id.disc_area, ctx.getString(R.string.cd_disc_empty))
            views.setOnClickPendingIntent(
                R.id.disc_area,
                PendingIntent.getActivity(ctx, RC_OPEN_APP, mainActivityIntent(ctx), flags),
            )
            views.setViewVisibility(R.id.next, View.GONE)
        } else {
            views.setTextViewText(R.id.title, title ?: PlaybackState.resumeTitle(ctx, tracks))
            views.setImageViewResource(
                R.id.tonearm,
                if (playing) R.drawable.tonearm_down else R.drawable.tonearm_up,
            )
            views.setContentDescription(
                R.id.disc_area,
                ctx.getString(if (playing) R.string.cd_disc_playing else R.string.cd_disc_stopped),
            )
            views.setOnClickPendingIntent(R.id.disc_area, servicePendingIntent(ctx, PlaybackService.ACTION_TOGGLE, RC_TOGGLE))
            views.setOnClickPendingIntent(R.id.next, servicePendingIntent(ctx, PlaybackService.ACTION_NEXT, RC_NEXT))
            views.setViewVisibility(R.id.next, View.VISIBLE)
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
