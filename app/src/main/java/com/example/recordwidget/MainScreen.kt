package com.example.recordwidget

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/**
 * The app screen: a retro listening room. A walnut table with the big portable player on
 * it (play from here too), and below it cream paper cards like record sleeves, each under a
 * small red record-label caption.
 */
class MainScreen(
    private val activity: Activity,
    private val s: UiStyle,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onAddVideo()
        fun onAddUrl()
        fun onFloatingSwitch(on: Boolean)
        fun onRename(t: Track)
        fun onChooseSkin()
        fun onJacketLongPress(onScreen: android.graphics.RectF)
        fun onChooseFont()
        fun onDelete(t: Track)
        fun onMove(from: Int, to: Int)
        fun onFavorite(t: Track)
        fun isFavorite(t: Track): Boolean
        fun onAutoUpdate(on: Boolean)
        fun volume(): Float
        fun onVolume(v: Float)
        fun autoUpdateOn(): Boolean
        fun onCheckUpdate()
        fun onToggle()
        fun onPrevious()
        fun onNext()
    }

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dpf(v: Int) = v * density

    lateinit var addVideoRow: View
        private set
    lateinit var addUrlRow: View
        private set
    lateinit var floatingSwitch: SwitchCompat
        private set
    private lateinit var progressText: TextView
    private lateinit var countText: TextView
    private lateinit var tracksCount: TextView
    private lateinit var tracksGroup: LinearLayout
    private lateinit var hero: HeroPlayerView
    private lateinit var heroHint: TextView
    private lateinit var updateStatus: TextView
    private var syncing = false

    /**
     * Turning the record in the app moves through the track like the floating record:
     * one turn = 15 s, clockwise forward; silent while held, jumps when let go.
     */
    private val scratch = object {
        private var from = 0L
        private var index = 0
        private var player: androidx.media3.common.Player? = null

        fun start(): Boolean {
            val p = PlaybackService.seekablePlayer() ?: return false
            player = p
            from = p.currentPosition
            index = p.currentMediaItemIndex
            p.volume = 0f
            return true
        }

        private fun target(totalDeg: Float): Long {
            val p = player ?: return 0L
            val dur = p.duration
            val max = if (dur > 0) dur - 500 else from + 10 * 60_000L
            return (from + (totalDeg / 360f * 15_000f).toLong()).coerceIn(0L, max.coerceAtLeast(0L))
        }

        fun move(totalDeg: Float): String {
            val t = target(totalDeg) / 1000
            return "%d:%02d".format(t / 60, t % 60)
        }

        fun end(totalDeg: Float, cancelled: Boolean) {
            val p = player ?: return
            if (!cancelled) {
                val t = target(totalDeg)
                if (kotlin.math.abs(t - from) > 200 && index < p.mediaItemCount) p.seekTo(index, t)
            }
            p.volume = PlaybackState.volume(activity)
            player = null
        }
    }

    /** Sections added by the shared lists (set before [install]). */
    var homeExtra: View? = null
    var tracksExtra: View? = null
    var settingsExtra: View? = null

    private lateinit var root: FrameLayout
    private lateinit var fan: FanMenu
    private lateinit var addPage: View
    private lateinit var tracksPage: View
    private lateinit var settingsPage: View
    private var openPage: View? = null
    private var insetTop = 0
    private var insetBottom = 0

    /**
     * Home is only the player on the table. Everything else lives on three pages that slide
     * in from the right, opened from the FAB fan: 追加 / 音声一覧 / 設定.
     */
    fun install() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = s.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // ---- home: the player on the table ----
        val home = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(40))
        }
        home.addView(masthead())
        hero = HeroPlayerView(activity).apply {
            titleTypeface = s.serifBold
            listener = object : HeroPlayerView.Listener {
                override fun onToggle() = callbacks.onToggle()
                override fun onPrevious() = callbacks.onPrevious()
                override fun onNext() = callbacks.onNext()
                override fun onJacketLongPress(onScreen: android.graphics.RectF) = callbacks.onJacketLongPress(onScreen)
                override fun onScratchStart() = scratch.start()
                override fun onScratch(totalDeg: Float) = scratch.move(totalDeg)
                override fun onScratchEnd(totalDeg: Float, cancelled: Boolean) = scratch.end(totalDeg, cancelled)
            }
        }
        home.addView(
            hero,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                topMargin = dp(14)
            },
        )
        heroHint = onBgText(activity.getString(R.string.hero_hint), 12f, s.onBgSub).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        home.addView(heroHint)
        home.addView(volumeBar())
        homeExtra?.let { home.addView(it) }
        val homeScroll = ScrollView(activity).apply {
            background = WoodDrawable(s.bg, s.bgGrain, density)
            isFillViewport = true
            clipToPadding = false
            addView(home)
        }

        // ---- pages ----
        addVideoRow = actionRow(R.drawable.ic_video, R.string.add_from_video) { callbacks.onAddVideo() }
        addUrlRow = actionRow(R.drawable.ic_link, R.string.add_from_url) { callbacks.onAddUrl() }
        progressText = footer(R.string.extracting).apply {
            setTextColor(s.mustard)
            visibility = View.GONE
        }
        addPage = page(R.string.page_add, card(addVideoRow, addUrlRow), progressText, footer(R.string.add_footer))

        tracksGroup = card()
        tracksCount = onBgText("", 13f, s.mustard).apply {
            typeface = s.serifBold
            setPadding(dp(s.groupMarginDp + 6), 0, 0, dp(10))
        }
        tracksPage = page(
            R.string.page_tracks,
            *listOfNotNull(tracksCount, tracksGroup, footer(R.string.hint_widget), tracksExtra).toTypedArray(),
        )

        floatingSwitch = switch { on -> if (!syncing) callbacks.onFloatingSwitch(on) }
        val updateSwitch = switch { on -> callbacks.onAutoUpdate(on) }
        updateSwitch.isChecked = callbacks.autoUpdateOn()
        updateStatus = footer(0)
        val screenOffSwitch = switch { on -> PlaybackState.setPauseOnScreenOff(activity, on) }
        screenOffSwitch.isChecked = PlaybackState.pauseOnScreenOff(activity)
        settingsPage = page(
            R.string.page_settings,
            caption(R.string.section_playback),
            card(switchRow(R.drawable.ic_note, R.string.pause_screen_off, screenOffSwitch)),
            footer(R.string.pause_screen_off_hint),
            caption(R.string.section_look),
            card(
                actionRow(R.drawable.ic_settings, R.string.look_skin) { callbacks.onChooseSkin() },
                actionRow(R.drawable.ic_note, R.string.look_font) { callbacks.onChooseFont() },
            ),
            footer(R.string.look_footer),
            caption(R.string.section_floating),
            card(switchRow(R.drawable.ic_floating, R.string.floating_switch_short, floatingSwitch)),
            footer(R.string.floating_hint),
            caption(R.string.section_update),
            card(
                switchRow(R.drawable.ic_update, R.string.update_auto, updateSwitch),
                actionRow(R.drawable.ic_update, R.string.update_check_now) { callbacks.onCheckUpdate() },
            ),
            *listOfNotNull(updateStatus, settingsExtra).toTypedArray(),
        )

        // ---- FAB fan in the top-right corner, above everything ----
        fan = FanMenu(
            activity,
            s,
            listOf(
                FanMenu.Item(R.drawable.ic_add, R.string.fan_add) { showPage(addPage) },
                FanMenu.Item(R.drawable.ic_list, R.string.fan_tracks) { showPage(tracksPage) },
                FanMenu.Item(R.drawable.ic_settings, R.string.fan_settings) { showPage(settingsPage) },
            ),
        )
        root = FrameLayout(activity)
        fan.host = root
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(homeScroll, match)
        for (p in listOf(addPage, tracksPage, settingsPage)) {
            p.visibility = View.GONE
            root.addView(p, FrameLayout.LayoutParams(match))
        }
        val fabLp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16)
            rightMargin = dp(16)
        }
        root.addView(fan.button, fabLp)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetTop = bars.top
            insetBottom = bars.bottom
            homeScroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            for (p in listOf(addPage, tracksPage, settingsPage)) p.setPadding(bars.left, bars.top, bars.right, 0)
            fabLp.topMargin = bars.top + dp(16)
            fabLp.rightMargin = bars.right + dp(16)
            fan.button.layoutParams = fabLp
            insets
        }
        activity.setContentView(root)
    }

    /** A page: wood background, ← and a title, then its content (scrolls). */
    private fun page(title: Int, vararg children: View): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(48))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(72), dp(6))
            addView(
                ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_back)
                    imageTintList = ColorStateList.valueOf(s.onBg)
                    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
                    contentDescription = activity.getString(R.string.cd_back)
                    setOnClickListener { closePage() }
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
            addView(onBgText(activity.getString(title), 26f, s.onBg).apply { typeface = s.serifBold })
        }
        col.addView(header)
        children.forEach { col.addView(it) }
        return ScrollView(activity).apply {
            background = WoodDrawable(s.bg, s.bgGrain, density)
            isFillViewport = true
            clipToPadding = false
            isClickable = true // nothing behind the page gets touches
            addView(col)
        }
    }

    private fun showPage(p: View) {
        if (openPage === p) return
        val previous = openPage
        openPage = p
        p.visibility = View.VISIBLE
        p.bringToFront()
        fan.button.bringToFront()
        root.requestLayout()
        p.translationX = root.width.toFloat()
        p.animate().translationX(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
            if (previous != null && previous !== openPage) previous.visibility = View.GONE
        }.start()
    }

    private fun closePage(): Boolean {
        val p = openPage ?: return false
        openPage = null
        p.animate().translationX(root.width.toFloat()).setDuration(220).withEndAction { p.visibility = View.GONE }.start()
        return true
    }

    /** Back key: fan first, then the open page. False = leave the app. */
    fun handleBack(): Boolean = closeFanIfOpen() || closePage()

    private fun closeFanIfOpen(): Boolean {
        if (!fan.isOpen) return false
        fan.close(null)
        return true
    }

    // ---- state from MainActivity ---------------------------------------------------------

    fun setFloatingChecked(on: Boolean) {
        syncing = true
        floatingSwitch.isChecked = on
        syncing = false
    }

    fun setBusy(busy: Boolean) {
        addVideoRow.isEnabled = !busy
        addUrlRow.isEnabled = !busy
        addVideoRow.alpha = if (busy) 0.4f else 1f
        addUrlRow.alpha = if (busy) 0.4f else 1f
        progressText.visibility = if (busy) View.VISIBLE else View.GONE
    }

    fun setUpdateStatus(text: String) {
        updateStatus.text = text
    }

    fun setSkin(skin: Skin) {
        hero.skin = skin
    }

    fun showNowPlaying(title: String?, thumb: Bitmap?, turning: Boolean, hasTracks: Boolean, jacket: Bitmap?) {
        hero.show(title ?: activity.getString(R.string.widget_empty_hint), thumb, turning, jacket)
        heroHint.text = activity.getString(if (hasTracks) R.string.hero_hint else R.string.hero_hint_empty)
    }

    fun showTracks(tracks: List<Track>) {
        countText.text = activity.getString(R.string.header_count, tracks.size)
        tracksCount.text = activity.getString(R.string.header_count, tracks.size)
        tracksGroup.removeAllViews()
        if (tracks.isEmpty()) {
            tracksGroup.addView(
                text(activity.getString(R.string.empty_list), 15f, s.sub).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(22), dp(16), dp(22))
                },
            )
            return
        }
        tracks.forEachIndexed { i, t ->
            if (i > 0) tracksGroup.addView(separator())
            tracksGroup.addView(trackRow(t, i, tracks.size))
        }
    }

    // ---- pieces --------------------------------------------------------------------------

    /** App name in serif on the wood, with the number of records like a shelf label. */
    private fun masthead(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(dp(20), dp(12), dp(72), 0) // right: room for the fan button
        addView(
            onBgText(activity.getString(R.string.header_title), 28f, s.onBg).apply { typeface = s.serifBold },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        countText = onBgText("", 13f, s.mustard).apply {
            typeface = s.serifBold
            setPadding(0, 0, 0, dp(4))
        }
        addView(countText)
    }

    /** − slider + on the wood, under the player. */
    private fun volumeBar(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(6), dp(20), 0)
        val bar = SeekBar(activity).apply {
            max = 100
            progress = (callbacks.volume() * 100).roundToInt()
            progressTintList = ColorStateList.valueOf(s.mustard)
            thumbTintList = ColorStateList.valueOf(s.onBg)
            progressBackgroundTintList = ColorStateList.valueOf(s.onBgSub)
            contentDescription = activity.getString(R.string.cd_volume)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) callbacks.onVolume(p / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        addView(icon(R.drawable.ic_volume_down, s.onBgSub))
        addView(bar, LinearLayout.LayoutParams(0, dp(44), 1f))
        val up = icon(R.drawable.ic_volume_up, s.onBgSub)
        (up.layoutParams as LinearLayout.LayoutParams).apply {
            rightMargin = 0
            leftMargin = dp(4)
        }
        addView(up)
    }

    /** Section caption: a small red record-label dot and serif mustard text. */
    internal fun caption(res: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(s.groupMarginDp + 4), dp(26), dp(20), dp(8))
        addView(
            View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(s.accent)
                    setStroke(dp(2), s.mustard)
                }
            },
            LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) },
        )
        addView(
            onBgText(activity.getString(res), 15f, s.mustard).apply {
                typeface = s.serifBold
                letterSpacing = 0.08f
            },
        )
    }

    internal fun footer(res: Int): TextView = onBgText(if (res == 0) "" else activity.getString(res), 12.5f, s.onBgSub).apply {
        setPadding(dp(s.groupMarginDp + 6), dp(8), dp(s.groupMarginDp + 6), 0)
        setLineSpacing(0f, 1.15f)
    }

    /** A cream paper sleeve with a soft shadow. */
    internal fun card(vararg rows: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(s.card, 0xFFEBDDBF.toInt())).apply {
            cornerRadius = dpf(s.cardRadiusDp)
        }
        elevation = dpf(6)
        clipToOutline = true
        setPadding(0, dp(4), 0, dp(4))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(s.groupMarginDp)
            rightMargin = dp(s.groupMarginDp)
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(separator())
            addView(r)
        }
    }

    internal fun separator(): View = View(activity).apply {
        setBackgroundColor(s.separator)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
        }
    }

    internal fun pressable(v: View) {
        v.background = RippleDrawable(ColorStateList.valueOf(s.pressed), null, ColorDrawable(0xFFFFFFFF.toInt()))
        v.isClickable = true
        v.isFocusable = true
    }

    internal fun rowBase(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(s.rowHeightDp)
        setPadding(dp(18), dp(4), dp(12), dp(4))
    }

    internal fun icon(res: Int, tint: Int): ImageView = ImageView(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) }
    }

    private fun actionRow(iconRes: Int, label: Int, onClick: () -> Unit): View = rowBase().apply {
        addView(icon(iconRes, s.accent))
        addView(text(activity.getString(label), 16f, s.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            ImageView(activity).apply {
                setImageResource(R.drawable.ic_next)
                imageTintList = ColorStateList.valueOf(s.sub)
            },
            LinearLayout.LayoutParams(dp(14), dp(14)),
        )
        pressable(this)
        setOnClickListener { onClick() }
    }

    private fun switch(onChange: (Boolean) -> Unit) = SwitchCompat(activity).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        trackTintList = ColorStateList(states, intArrayOf(s.accent, s.separator))
        thumbTintList = ColorStateList(states, intArrayOf(0xFFFFF8EA.toInt(), 0xFFFFFFFF.toInt()))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun switchRow(iconRes: Int, label: Int, sw: SwitchCompat): View = rowBase().apply {
        addView(icon(iconRes, s.accent))
        addView(text(activity.getString(label), 16f, s.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(sw)
        pressable(this)
        setOnClickListener { sw.toggle() }
    }

    /** A track like a line on the back of a record sleeve: number, title (tap to rename), controls. */
    private fun trackRow(t: Track, index: Int, count: Int): View = rowBase().apply {
        addView(
            text("%02d".format(index + 1), 13f, s.accent).apply {
                typeface = s.serifBold
                minWidth = dp(28)
            },
        )
        addView(
            text(t.title, 16f, s.text).apply {
                typeface = s.serifBold
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                contentDescription = activity.getString(R.string.rename_title) + " " + t.title
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val fav = callbacks.isFavorite(t)
        addView(
            smallButton(
                if (fav) R.drawable.ic_star else R.drawable.ic_star_border,
                if (fav) R.string.cd_fav_remove else R.string.cd_fav_add,
                if (fav) s.mustard else s.sub,
                true,
            ) { callbacks.onFavorite(t) },
        )
        addView(smallButton(R.drawable.ic_up, R.string.move_up, s.sub, index > 0) { callbacks.onMove(index, index - 1) })
        addView(smallButton(R.drawable.ic_down, R.string.move_down, s.sub, index < count - 1) { callbacks.onMove(index, index + 1) })
        addView(smallButton(R.drawable.ic_delete, R.string.delete, s.danger, true) { callbacks.onDelete(t) })
        pressable(this)
        setOnClickListener { callbacks.onRename(t) }
    }

    private fun smallButton(iconRes: Int, cd: Int, tint: Int, enabled: Boolean, onClick: () -> Unit) =
        ImageButton(activity).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            contentDescription = activity.getString(cd)
            background = RippleDrawable(ColorStateList.valueOf(s.pressed), null, null)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            val p = dp(10)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
            setOnClickListener { onClick() }
        }

    internal fun text(value: String, sizeSp: Float, color: Int): TextView = TextView(activity).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
    }

    internal fun onBgText(value: String, sizeSp: Float, color: Int): TextView = text(value, sizeSp, color).apply {
        setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
    }

    /** Dark walnut with faint wavy grain lines and a warm lamp glow at the top. */
    private class WoodDrawable(private val base: Int, private val grain: Int, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint()
        private var cache: Bitmap? = null

        /** Drawn once per size into a bitmap; scrolling then only copies it. */
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val c = cache?.takeIf { it.width == b.width() && it.height == b.height() }
                ?: Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.RGB_565).also {
                    cache?.recycle()
                    paintWood(Canvas(it), it.width, it.height)
                    cache = it
                }
            canvas.drawBitmap(c, b.left.toFloat(), b.top.toFloat(), null)
        }

        private fun paintWood(canvas: Canvas, w: Int, h: Int) {
            val b = android.graphics.Rect(0, 0, w, h)
            canvas.drawColor(base)
            paint.color = grain
            paint.strokeWidth = 1.2f * density
            var y = 0f
            var i = 0
            while (y < b.height()) {
                y += (10 + (i * 37 % 23)) * density
                val amp = (2 + i % 4) * density
                var x = 0f
                var lastY = y + amp * kotlin.math.sin(i.toFloat())
                while (x < b.width()) {
                    val nx = x + 24 * density
                    val ny = y + amp * kotlin.math.sin(nx / (90 * density) + i)
                    canvas.drawLine(x, lastY, nx, ny, paint)
                    x = nx
                    lastY = ny
                }
                i++
            }
            glow.shader = LinearGradient(0f, 0f, 0f, b.height() * 0.45f, 0x33FFC680, 0x00FFC680, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, b.width().toFloat(), b.height() * 0.45f, glow)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
