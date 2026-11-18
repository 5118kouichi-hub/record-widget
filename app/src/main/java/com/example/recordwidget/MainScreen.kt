package com.example.recordwidget

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/**
 * The app screen, built in code so the same content can take two looks ([UiStyle]):
 *
 *  - One UI-like: a tall header with a centered title (content starts low, within thumb
 *    reach), big round cards, roomy rows.
 *  - iOS-like: a left-aligned large title, grey section captions above inset grouped lists,
 *    hairline separators, blue text actions, green switches.
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
        fun onStyle(kind: UiKind)
        fun onRename(t: Track)
        fun onDelete(t: Track)
        fun onMove(from: Int, to: Int)
        fun onFavorite(t: Track)
        fun isFavorite(t: Track): Boolean
        fun onAutoUpdate(on: Boolean)
        fun volume(): Float
        fun onVolume(v: Float)
        fun autoUpdateOn(): Boolean
        fun onCheckUpdate()
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
    private lateinit var tracksGroup: LinearLayout

    /** Builds the screen and makes it the activity's content. */
    fun install() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = s.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !s.dark
            isAppearanceLightNavigationBars = !s.dark
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        content.addView(header())

        content.addView(caption(R.string.section_add))
        addVideoRow = actionRow(R.drawable.ic_video, R.string.add_from_video) { callbacks.onAddVideo() }
        addUrlRow = actionRow(R.drawable.ic_link, R.string.add_from_url) { callbacks.onAddUrl() }
        content.addView(group(addVideoRow, addUrlRow))
        progressText = footer(R.string.extracting).apply {
            setTextColor(s.accent)
            visibility = View.GONE
        }
        content.addView(progressText)

        content.addView(caption(R.string.section_volume))
        content.addView(group(volumeRow()))
        content.addView(footer(R.string.volume_footer))

        content.addView(caption(R.string.section_floating))
        floatingSwitch = SwitchCompat(activity).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(states, intArrayOf(s.switchOn, s.separator))
            thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
            setOnCheckedChangeListener { _, on -> if (!syncing) callbacks.onFloatingSwitch(on) }
        }
        content.addView(group(switchRow(R.drawable.ic_floating, R.string.floating_switch_short, floatingSwitch)))
        content.addView(footer(R.string.floating_hint))

        content.addView(caption(R.string.section_tracks))
        tracksGroup = group()
        content.addView(tracksGroup)
        content.addView(footer(R.string.hint_widget))

        content.addView(caption(R.string.section_design))
        content.addView(
            group(
                choiceRow(R.string.style_oneui, s.kind == UiKind.ONE_UI) { callbacks.onStyle(UiKind.ONE_UI) },
                choiceRow(R.string.style_ios, s.kind == UiKind.IOS) { callbacks.onStyle(UiKind.IOS) },
            ),
        )
        content.addView(footer(R.string.style_footer))

        content.addView(caption(R.string.section_update))
        val updateSwitch = SwitchCompat(activity).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(states, intArrayOf(s.switchOn, s.separator))
            thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
            isChecked = callbacks.autoUpdateOn()
            setOnCheckedChangeListener { _, on -> callbacks.onAutoUpdate(on) }
        }
        updateStatus = footer(0)
        content.addView(
            group(
                switchRow(R.drawable.ic_update, R.string.update_auto, updateSwitch),
                actionRow(R.drawable.ic_update, R.string.update_check_now) { callbacks.onCheckUpdate() },
            ),
        )
        content.addView(updateStatus)

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(s.bg)
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        activity.setContentView(scroll)
    }

    private var syncing = false
    private lateinit var updateStatus: TextView

    /** Line under the update section: current version / progress / result. */
    fun setUpdateStatus(text: String) {
        updateStatus.text = text
    }

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

    fun showTracks(tracks: List<Track>) {
        countText.text = activity.getString(R.string.header_count, tracks.size)
        tracksGroup.removeAllViews()
        if (tracks.isEmpty()) {
            tracksGroup.addView(
                text(activity.getString(R.string.empty_list), 15f, s.sub).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(20), dp(16), dp(20))
                },
            )
            return
        }
        tracks.forEachIndexed { i, t ->
            if (i > 0) tracksGroup.addView(separator(withIcon = false))
            tracksGroup.addView(trackRow(t, i, tracks.size))
        }
    }

    // ---- header --------------------------------------------------------------------------

    private fun header(): View {
        val title = text(activity.getString(R.string.header_title), 0f, s.text)
        countText = text("", 0f, s.sub)
        return if (s.isIos) {
            // Large title, left aligned, just under the status bar.
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(4))
                addView(countText.apply { textSize = 13f })
                addView(
                    title.apply {
                        textSize = 34f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        letterSpacing = -0.01f
                    },
                )
            }
        } else {
            // Expanded header: about a third of the screen, title centered.
            val h = (activity.resources.displayMetrics.heightPixels * 0.30f).toInt()
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = h
                setPadding(dp(24), dp(24), dp(24), dp(24))
                addView(
                    title.apply {
                        textSize = 32f
                        gravity = Gravity.CENTER
                    },
                )
                addView(
                    countText.apply {
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)
                    },
                )
            }
        }
    }

    // ---- groups and rows -----------------------------------------------------------------

    private fun caption(res: Int): View = text(activity.getString(res), if (s.isIos) 13f else 14f, s.sub).apply {
        if (!s.isIos) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        val side = if (s.isIos) s.groupMarginDp + 16 else s.groupMarginDp + 20
        setPadding(dp(side), dp(if (s.isIos) 22 else 20), dp(side), dp(6))
    }

    private fun footer(res: Int): TextView = text(if (res == 0) "" else activity.getString(res), 13f, s.sub).apply {
        val side = if (s.isIos) s.groupMarginDp + 16 else s.groupMarginDp + 20
        setPadding(dp(side), dp(6), dp(side), 0)
    }

    private fun group(vararg rows: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dpf(s.cardRadiusDp)
            setColor(s.card)
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(s.groupMarginDp)
            rightMargin = dp(s.groupMarginDp)
        }
        if (!s.isIos) setPadding(0, dp(6), 0, dp(6))
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(separator(withIcon = true))
            addView(r)
        }
    }

    private fun separator(withIcon: Boolean): View = View(activity).apply {
        setBackgroundColor(s.separator)
        val inset = if (s.isIos) (if (withIcon) 56 else 16) else 20
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (s.isIos) 1 else dp(1)).apply {
            leftMargin = dp(inset)
            rightMargin = if (s.isIos) 0 else dp(20)
        }
    }

    private fun pressable(v: View) {
        v.background = RippleDrawable(ColorStateList.valueOf(s.pressed), null, ColorDrawable(0xFFFFFFFF.toInt()))
        v.isClickable = true
        v.isFocusable = true
    }

    private fun rowBase(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(s.rowHeightDp)
        val side = if (s.isIos) 16 else 20
        setPadding(dp(side), dp(4), dp(side - 4), dp(4))
    }

    private fun volumeRow(): View = rowBase().apply {
        val bar = android.widget.SeekBar(activity).apply {
            max = 100
            progress = (callbacks.volume() * 100).roundToInt()
            progressTintList = ColorStateList.valueOf(s.accent)
            thumbTintList = ColorStateList.valueOf(s.accent)
            contentDescription = activity.getString(R.string.cd_volume)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) callbacks.onVolume(p / 100f)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        addView(icon(R.drawable.ic_volume_down, s.sub).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(4) })
        addView(bar, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(icon(R.drawable.ic_volume_up, s.sub).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = 0 })
    }

    private fun icon(res: Int, tint: Int): ImageView = ImageView(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(16) }
    }

    private fun actionRow(iconRes: Int, label: Int, onClick: () -> Unit): View = rowBase().apply {
        // iOS: blue text actions; One UI: normal text with a tinted icon.
        addView(icon(iconRes, s.accent))
        addView(
            text(activity.getString(label), 17f, if (s.isIos) s.accent else s.text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        pressable(this)
        setOnClickListener { onClick() }
    }

    private fun switchRow(iconRes: Int, label: Int, sw: SwitchCompat): View = rowBase().apply {
        addView(icon(iconRes, s.accent))
        addView(
            text(activity.getString(label), 17f, s.text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(sw)
        pressable(this)
        setOnClickListener { sw.toggle() }
    }

    private fun choiceRow(label: Int, selected: Boolean, onClick: () -> Unit): View = rowBase().apply {
        addView(
            text(activity.getString(label), 17f, s.text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (s.isIos) {
            // iOS: a check mark on the chosen row.
            addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(s.accent)
                    visibility = if (selected) View.VISIBLE else View.INVISIBLE
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(4) },
            )
        } else {
            // One UI: a radio dot.
            addView(radioDot(selected), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(4) })
        }
        contentDescription = activity.getString(label) + if (selected) "（選択中）" else ""
        pressable(this)
        setOnClickListener { if (!selected) onClick() }
    }

    private fun radioDot(selected: Boolean): View = FrameLayout(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), if (selected) s.accent else s.sub)
        }
        if (selected) {
            addView(
                View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(s.accent)
                    }
                },
                FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER),
            )
        }
    }

    private fun trackRow(t: Track, index: Int, count: Int): View = rowBase().apply {
        val title = text(t.title, 17f, s.text).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = activity.getString(R.string.rename_title) + " " + t.title
        }
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fav = callbacks.isFavorite(t)
        addView(
            smallButton(
                if (fav) R.drawable.ic_star else R.drawable.ic_star_border,
                if (fav) R.string.cd_fav_remove else R.string.cd_fav_add,
                if (fav) 0xFFFFB300.toInt() else s.sub,
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
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }

    private fun text(value: String, sizeSp: Float, color: Int): TextView = TextView(activity).apply {
        text = value
        if (sizeSp > 0f) textSize = sizeSp
        setTextColor(color)
    }
}
