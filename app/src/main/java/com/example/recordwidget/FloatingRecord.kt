package com.example.recordwidget

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The record that floats over every app while [PlaybackService] runs (like picture-in-picture,
 * but it can be scratched). Needs the "display over other apps" permission.
 *
 * Owned by the service; all calls on the main thread.
 */
class FloatingRecord(
    private val ctx: Context,
    private val player: Player,
    private val actions: Actions,
) {
    interface Actions {
        fun togglePlay()
        fun previous()
        fun next()
        /** Shown/hidden: the service keeps itself in the foreground while the record is shown. */
        fun onShownChanged(shown: Boolean)
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var root: LinearLayout? = null
    private var record: FloatingRecordView? = null
    private var params: WindowManager.LayoutParams? = null
    private var closeTarget: View? = null
    private var thumbId: String? = null

    /** Closed by dragging onto ×: stays away until playback is started again from outside. */
    private var dismissed = false

    /** Setting + permission, cached: re-read only on [refreshSettings] (no binder call per event). */
    private var allowed = readAllowed()
    private var releasing = false

    private fun readAllowed() = isEnabled(ctx) && Settings.canDrawOverlays(ctx)

    /** The app changed the setting or came back from the permission screen. */
    fun refreshSettings() {
        allowed = readAllowed()
        dismissed = false
        sync()
    }

    /** Rotation / screen size change: keep the record on screen. */
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val lp = params ?: return
            clamp(lp)
            updateLayout(lp)
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    val isShown: Boolean get() = root != null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
    }

    // ---- show / hide ---------------------------------------------------------------------

    /** Shows or hides according to the setting, the permission and the player. */
    fun sync() {
        val want = allowed && !dismissed && !releasing && player.mediaItemCount > 0
        if (want && root == null) show() else if (!want && root != null) hide()
        val rec = record ?: return
        rec.setPlaying(isTurning())
        val id = player.currentMediaItem?.mediaId
        if (id != thumbId) {
            val bmp = id?.let { loadThumb(it) }
            rec.setThumbnail(bmp)
            // Remember only a found thumbnail, so one made later (web videos) is picked up.
            thumbId = if (bmp != null) id else null
        }
    }

    /** Playback was started from the widget/notification: a dismissed record may come back. */
    fun onStartedFromOutside() {
        dismissed = false
        sync()
    }

    /** Service is being destroyed: remove the window without asking for foreground changes. */
    fun release() {
        releasing = true
        player.removeListener(playerListener)
        ctx.unregisterComponentCallbacks(configCallbacks)
        hide()
    }

    init {
        player.addListener(playerListener)
        ctx.registerComponentCallbacks(configCallbacks)
    }

    private fun isTurning(): Boolean =
        player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    private fun show() {
        val view = FloatingRecordView(ctx).apply {
            contentDescription = ctx.getString(R.string.cd_floating)
            listener = recordListener
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(smallButton(R.drawable.ic_prev, R.string.cd_prev) { actions.previous() })
            addView(smallButton(R.drawable.ic_next, R.string.cd_next) { actions.next() })
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(view, LinearLayout.LayoutParams(dp(RECORD_DP), dp(RECORD_DP)))
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        val screen = ctx.resources.displayMetrics
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, screen.widthPixels - dp(RECORD_DP + 12))
            y = prefs.getInt(KEY_Y, (screen.heightPixels * 0.3f).toInt())
        }
        clamp(lp)
        try {
            wm.addView(box, lp)
        } catch (e: Exception) {
            // Permission revoked between the check and now, or the window token is gone.
            Log.w(TAG, "cannot show floating record", e)
            return
        }
        root = box
        record = view
        params = lp
        thumbId = null
        actions.onShownChanged(true)
    }

    private fun hide() {
        hideCloseTarget()
        root?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "remove failed", e)
            }
        }
        val wasShown = root != null
        root = null
        record = null
        params = null
        if (wasShown && !releasing) actions.onShownChanged(false)
    }

    private fun smallButton(icon: Int, cd: Int, onClick: () -> Unit) = ImageButton(ctx).apply {
        setImageResource(icon)
        contentDescription = ctx.getString(cd)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp(6)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x99000000.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }
        setOnClickListener { onClick() }
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w,
        h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    // ---- gestures ------------------------------------------------------------------------

    private var scratchFrom = 0L
    private var scratchIndex = 0
    private var scratchTarget = 0L
    private var dragStartX = 0
    private var dragStartY = 0

    private val recordListener = object : FloatingRecordView.Listener {
        override fun onTap() = actions.togglePlay()

        override fun onDragStart() {
            val lp = params ?: return
            dragStartX = lp.x
            dragStartY = lp.y
            showCloseTarget()
        }

        override fun onDrag(totalDxPx: Float, totalDyPx: Float) {
            val lp = params ?: return
            lp.x = dragStartX + totalDxPx.roundToInt()
            lp.y = dragStartY + totalDyPx.roundToInt()
            clamp(lp)
            updateLayout(lp)
            val near = nearClose()
            closeTarget?.alpha = if (near) 1f else 0.6f
            closeTarget?.scaleX = if (near) 1.2f else 1f
            closeTarget?.scaleY = if (near) 1.2f else 1f
        }

        override fun onDragEnd() {
            val close = nearClose()
            hideCloseTarget()
            if (close) {
                dismissed = true
                hide()
                return
            }
            val lp = params ?: return
            prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
        }

        override fun onScratchStart(): Boolean {
            if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !player.isCurrentMediaItemSeekable) {
                return false // e.g. a live stream: the record just turns under the finger
            }
            scratchFrom = player.currentPosition
            scratchIndex = player.currentMediaItemIndex
            scratchTarget = scratchFrom
            player.volume = 0f // the record is "held": silent while scratching
            return true
        }

        override fun onScratch(totalDeg: Float) {
            scratchTarget = targetFor(totalDeg)
            record?.setOverlayText(formatTime(scratchTarget))
        }

        override fun onScratchEnd(totalDeg: Float, cancelled: Boolean) {
            record?.setOverlayText(null)
            if (!cancelled) {
                scratchTarget = targetFor(totalDeg)
                if (abs(scratchTarget - scratchFrom) > 200 && scratchIndex < player.mediaItemCount) {
                    // Same track even if it changed underneath while muted.
                    player.seekTo(scratchIndex, scratchTarget)
                }
            }
            player.volume = 1f
        }
    }

    private fun updateLayout(lp: WindowManager.LayoutParams) {
        val box = root ?: return
        try {
            wm.updateViewLayout(box, lp)
        } catch (e: Exception) {
            Log.w(TAG, "move failed", e)
        }
    }

    /** Keeps the whole record (and its buttons) on screen. */
    private fun clamp(lp: WindowManager.LayoutParams) {
        val m = ctx.resources.displayMetrics
        val w = root?.width?.takeIf { it > 0 } ?: dp(RECORD_DP)
        val h = root?.height?.takeIf { it > 0 } ?: dp(RECORD_DP + 36)
        lp.x = lp.x.coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (m.heightPixels - h).coerceAtLeast(0))
    }

    /** One full turn = [MS_PER_TURN]; clamped to the track. */
    private fun targetFor(totalDeg: Float): Long {
        val delta = (totalDeg / 360f * MS_PER_TURN).toLong()
        val dur = player.duration
        val max = if (dur > 0) dur - 500 else scratchFrom + 10 * 60_000L
        return (scratchFrom + delta).coerceIn(0L, max.coerceAtLeast(0L))
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---- close target ("×" at the bottom while dragging) -----------------------------------

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val size = dp(CLOSE_DP)
        val v = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCB71C1C.toInt())
            }
            addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_close)
                    contentDescription = ctx.getString(R.string.cd_close_floating)
                },
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER),
            )
            alpha = 0.6f
        }
        val lp = overlayParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(48)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            alpha = 0.8f // API 31+: a not-fully-opaque window lets touches below pass through
        }
        try {
            wm.addView(v, lp)
            closeTarget = v
        } catch (e: Exception) {
            Log.w(TAG, "cannot show close target", e)
        }
    }

    private fun hideCloseTarget() {
        closeTarget?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "remove failed", e)
            }
        }
        closeTarget = null
    }

    /** Record center within ~64 dp of the ×, both measured on screen. */
    private fun nearClose(): Boolean {
        val rec = record ?: return false
        val target = closeTarget ?: return false
        if (target.width == 0) return false
        val a = IntArray(2).also { rec.getLocationOnScreen(it) }
        val b = IntArray(2).also { target.getLocationOnScreen(it) }
        val dx = (a[0] + rec.width / 2f) - (b[0] + target.width / 2f)
        val dy = (a[1] + rec.height / 2f) - (b[1] + target.height / 2f)
        return hypot(dx, dy) < dp(64)
    }

    private fun loadThumb(id: String) = TrackStore(ctx.filesDir).thumbFor(id).let {
        if (it.isFile) BitmapFactory.decodeFile(it.path) else null
    }

    companion object {
        private const val TAG = "FloatingRecord"
        private const val PREFS = "floating"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val RECORD_DP = 96
        private const val CLOSE_DP = 64
        private const val MS_PER_TURN = 15_000f

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        }
    }
}

