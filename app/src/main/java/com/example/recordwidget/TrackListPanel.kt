package com.example.recordwidget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The track list that opens over everything when the floating record is long-pressed.
 *
 *  - tap a track        -> play it
 *  - long-press a track -> repeat only that track (again: back to playing the list)
 *  - drag ≡             -> change the order (saved as the app's order)
 *  - "この順番を保存"    -> keep the current order under a name; "保存した順番" -> call one back
 *
 * Owned by [FloatingRecord]; main thread only.
 */
class TrackListPanel(ctx: Context, private val actions: Actions) {

    interface Actions {
        fun currentId(): String?
        fun loopingId(): String?
        fun playTrack(id: String)
        fun toggleLoop(id: String)
        /** [visibleOnly]: new order of the shown tracks (hidden ones stay put); else a saved order. */
        fun reorder(ids: List<String>, visibleOnly: Boolean)
        fun volume(): Float
        fun setVolume(v: Float)
        fun isFavorite(id: String): Boolean
        fun toggleFavorite(id: String)
        fun favoritesOnly(): Boolean
        fun setFavoritesOnly(on: Boolean)
    }

    private val ctx: Context = ContextThemeWrapper(ctx.applicationContext, R.style.Theme_RecordWidget)

    /** Look picked in the app (One UI-like / iOS-like) and light/dark; re-read on every opening. */
    private var st: UiStyle = UiStyle.current(this.ctx)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var root: View? = null
    private lateinit var body: FrameLayout
    private lateinit var status: TextView
    private val trackAdapter = TrackAdapter()
    private val thumbs = HashMap<String, Bitmap?>()

    /** One list view per opening (re-added when coming back to the list page). */
    private var listView: RecyclerView? = null
    private var dragging = false
    private var shownCurrent: String? = null
    private var shownLoop: String? = null

    val isShown: Boolean get() = root != null

    fun show() {
        if (root != null) return
        st = UiStyle.current(ctx)
        val scrim = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    hide()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x88000000.toInt())
            isFocusableInTouchMode = true
            setOnClickListener { hide() } // tap outside the card closes
            // Keyboard: overlay windows get no automatic resize on Android 11+, so pad for it.
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    insets.getInsets(android.view.WindowInsets.Type.ime() or android.view.WindowInsets.Type.systemBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                v.setPadding(0, 0, 0, bottom)
                insets
            }
        }
        val m = ctx.resources.displayMetrics
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(if (st.isIos) 14 else 26).toFloat()
                setColor(st.card)
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true // keeps taps on the card from closing it
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                text(R.string.panel_title, 18f, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_close)
                    imageTintList = android.content.res.ColorStateList.valueOf(st.text)
                    background = null
                    contentDescription = ctx.getString(R.string.cd_close_panel)
                    setOnClickListener { hide() }
                },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
        }
        body = FrameLayout(ctx)
        // Feedback inside the card: toasts would appear behind this overlay.
        status = text(0, 13f).apply {
            setTextColor(st.accent)
            visibility = View.GONE
        }
        card.addView(header)
        card.addView(status)
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val w = min(m.widthPixels - dp(32), dp(380))
        // Scrolls as a whole when the screen is short (landscape, keyboard up).
        val scroller = android.widget.ScrollView(ctx).apply { addView(card) }
        scrim.addView(scroller, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0, // focusable: back key and the name field need it
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            wm.addView(scrim, lp)
        } catch (e: Exception) {
            Log.w(TAG, "cannot show list", e)
            return
        }
        root = scrim
        scrim.requestFocus()
        showList()
    }

    fun hide() {
        val r = root ?: return
        hideKeyboard()
        root = null
        dragging = false
        listView?.adapter = null
        listView = null
        thumbs.clear()
        try {
            wm.removeView(r)
        } catch (e: Exception) {
            Log.w(TAG, "remove failed", e)
        }
    }

    /** Playback changed: repaint the marks, only if the current/looping track changed. */
    fun refresh() {
        if (root == null || dragging) return
        if (actions.currentId() == shownCurrent && actions.loopingId() == shownLoop) return
        repaint()
    }

    private fun repaint() {
        shownCurrent = actions.currentId()
        shownLoop = actions.loopingId()
        trackAdapter.notifyItemRangeChanged(0, trackAdapter.itemCount)
    }

    private fun say(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    // ---- pages ---------------------------------------------------------------------------

    @SuppressLint("NotifyDataSetChanged")
    private fun showList() {
        val tracks = PlaybackState.playableTracks(ctx)
        trackAdapter.items = tracks.toMutableList()
        shownCurrent = actions.currentId()
        shownLoop = actions.loopingId()
        trackAdapter.notifyDataSetChanged()
        val list = listView ?: RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = trackAdapter
            itemAnimator = null
            trackAdapter.dragHelper.attachToRecyclerView(this)
            listView = this
        }
        (list.parent as? ViewGroup)?.removeView(list)
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val listH = min(maxH, dp(ROW_DP) * tracks.size.coerceAtLeast(1))
        val page = column(
            volumeRow(),
            favoritesOnlyRow(),
            text(R.string.panel_hint, 12f, sub = true),
            if (tracks.isEmpty()) text(R.string.panel_empty, 14f, sub = true) else list.withHeight(listH),
            row(
                button(R.string.panel_save_order, primary = true) { showSave() },
                button(R.string.panel_saved_orders) { showSaved() },
            ),
        )
        setPage(page)
    }

    /** − [slider] +  : the record's own volume (within the phone's media volume). */
    private fun volumeRow(): View {
        val bar = android.widget.SeekBar(ctx).apply {
            max = 100
            progress = (actions.volume() * 100).roundToInt()
            progressTintList = android.content.res.ColorStateList.valueOf(st.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(st.accent)
            contentDescription = ctx.getString(R.string.cd_volume)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) actions.setVolume(p / 100f)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        fun step(delta: Int) {
            bar.progress = (bar.progress + delta).coerceIn(0, 100)
            actions.setVolume(bar.progress / 100f)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(R.drawable.ic_volume_down, R.string.cd_volume_down, st.text) { step(-10) })
            addView(bar, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(iconButton(R.drawable.ic_volume_up, R.string.cd_volume_up, st.text) { step(10) })
        }
    }

    private fun favoritesOnlyRow(): View {
        val sw = androidx.appcompat.widget.SwitchCompat(ctx).apply {
            isChecked = actions.favoritesOnly()
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(st.switchOn, st.separator))
            setOnCheckedChangeListener { _, on ->
                actions.setFavoritesOnly(on)
                if (on && trackAdapter.items.none { actions.isFavorite(it.id) }) {
                    say(ctx.getString(R.string.panel_fav_none))
                }
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_star)
                    imageTintList = android.content.res.ColorStateList.valueOf(STAR)
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) },
            )
            addView(text(R.string.panel_fav_only, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
    }

    /** Playing a non-favorite turned "favorites only" off: redraw the switch and say why. */
    private fun favoritesOnlyLeft(wasOnly: Boolean) {
        if (wasOnly && !actions.favoritesOnly()) {
            showList()
            say(ctx.getString(R.string.panel_fav_only_off))
        }
    }

    private fun iconButton(icon: Int, cd: Int, tint: Int, onClick: () -> Unit) = ImageButton(ctx).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(tint)
        background = null
        contentDescription = ctx.getString(cd)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun showSave() {
        val edit = EditText(ctx).apply {
            setText(ctx.getString(R.string.panel_default_name, SavedOrders.all(ctx).size + 1))
            setSelectAllOnFocus(true)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(st.text)
            backgroundTintList = android.content.res.ColorStateList.valueOf(st.accent)
        }
        val save = {
            val name = edit.text.toString().trim().ifEmpty { ctx.getString(R.string.panel_default_name, 1) }
            val ids = PlaybackState.playableTracks(ctx).map { it.id }
            SavedOrders.add(ctx, SavedOrder(UUID.randomUUID().toString(), name, ids))
            hideKeyboard()
            showList()
            say(ctx.getString(R.string.panel_saved, name))
        }
        edit.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) save(); true }
        setPage(
            column(
                text(R.string.panel_save_prompt, 14f),
                edit,
                row(button(R.string.panel_back) { showList() }, button(R.string.panel_save, primary = true) { save() }),
            ),
        )
        edit.requestFocus()
        edit.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val r = root ?: return
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(r.windowToken, 0)
    }

    private fun showSaved() {
        val orders = SavedOrders.all(ctx)
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (orders.isEmpty()) rows.addView(text(R.string.panel_no_saved, 14f, sub = true))
        orders.forEach { o -> rows.addView(savedRow(o)) }
        val scroll = android.widget.ScrollView(ctx).apply { addView(rows) }
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val h = min(maxH, dp(ROW_DP) * orders.size.coerceAtLeast(1))
        setPage(
            column(
                text(R.string.panel_saved_hint, 12f, sub = true),
                scroll.withHeight(h),
                row(button(R.string.panel_back) { showList() }),
            ),
        )
    }

    private fun savedRow(o: SavedOrder): View {
        var armedDelete = false
        val name = text(0, 15f).apply {
            text = o.name
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val count = text(0, 12f, sub = true).apply { text = ctx.getString(R.string.panel_count, o.trackIds.size) }
        val del = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_delete)
            imageTintList = android.content.res.ColorStateList.valueOf(st.danger)
            background = null
            contentDescription = ctx.getString(R.string.cd_delete_order)
        }
        val rowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_DP)
            addView(column(name, count), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(del, LinearLayout.LayoutParams(dp(44), dp(44)))
            setOnClickListener {
                actions.reorder(o.trackIds, visibleOnly = false)
                showList()
                say(ctx.getString(R.string.panel_applied, o.name))
            }
        }
        del.setOnClickListener {
            if (!armedDelete) {
                // Two taps: the first one only warns, so a stray tap never deletes.
                armedDelete = true
                count.text = ctx.getString(R.string.panel_tap_again_to_delete)
                count.setTextColor(st.accent)
            } else {
                SavedOrders.delete(ctx, o.id)
                showSaved()
            }
        }
        return rowView
    }

    private fun setPage(page: View) {
        body.removeAllViews()
        body.addView(page)
    }

    // ---- track list ----------------------------------------------------------------------

    private inner class TrackAdapter : RecyclerView.Adapter<TrackAdapter.VH>() {
        var items: MutableList<Track> = mutableListOf()
        private var dragFrom = -1

        inner class VH(
            val rowView: LinearLayout,
            val thumb: ImageView,
            val title: TextView,
            val loop: ImageView,
            val star: ImageButton,
            val handle: ImageView,
        ) : RecyclerView.ViewHolder(rowView)

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val thumb = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val title = TextView(ctx).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val loop = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_repeat_one)
                imageTintList = android.content.res.ColorStateList.valueOf(st.accent)
                contentDescription = ctx.getString(R.string.cd_looping)
            }
            val star = ImageButton(ctx).apply {
                background = null
                val p = dp(9)
                setPadding(p, p, p, p)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val handle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag)
                imageTintList = android.content.res.ColorStateList.valueOf(st.sub)
                contentDescription = ctx.getString(R.string.cd_drag)
                val p = dp(10)
                setPadding(p, p, p, p)
            }
            val rowView = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP))
                addView(thumb, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(loop, LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(6) })
                addView(star, LinearLayout.LayoutParams(dp(40), dp(44)))
                addView(handle, LinearLayout.LayoutParams(dp(44), dp(44)))
                isLongClickable = true
            }
            val vh = VH(rowView, thumb, title, loop, star, handle)
            star.setOnClickListener {
                items.getOrNull(vh.bindingAdapterPosition)?.let {
                    actions.toggleFavorite(it.id)
                    notifyItemChanged(vh.bindingAdapterPosition)
                }
            }
            rowView.setOnClickListener {
                items.getOrNull(vh.bindingAdapterPosition)?.let {
                    val wasOnly = actions.favoritesOnly()
                    actions.playTrack(it.id)
                    favoritesOnlyLeft(wasOnly)
                }
            }
            rowView.setOnLongClickListener {
                if (dragging) return@setOnLongClickListener true
                items.getOrNull(vh.bindingAdapterPosition)?.let {
                    val wasOn = actions.loopingId() == it.id
                    val wasOnly = actions.favoritesOnly()
                    actions.toggleLoop(it.id)
                    favoritesOnlyLeft(wasOnly)
                    val on = actions.loopingId() == it.id
                    if (on != wasOn) say(ctx.getString(if (on) R.string.panel_loop_on else R.string.panel_loop_off, it.title))
                    repaint()
                }
                true
            }
            handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    dragHelper.startDrag(vh)
                    true // keep the row from also starting its long-press (= loop)
                } else {
                    false
                }
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            h.title.text = t.title
            val current = t.id == actions.currentId()
            h.title.setTextColor(if (current) st.accent else st.text)
            h.title.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
            h.loop.visibility = if (t.id == actions.loopingId()) View.VISIBLE else View.GONE
            val fav = actions.isFavorite(t.id)
            h.star.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
            h.star.imageTintList = android.content.res.ColorStateList.valueOf(if (fav) STAR else st.sub)
            h.star.contentDescription = ctx.getString(if (fav) R.string.cd_fav_remove else R.string.cd_fav_add)
            val bmp = if (thumbs.containsKey(t.id)) {
                thumbs[t.id]
            } else {
                TrackStore(ctx.filesDir).thumbFor(t.id).takeIf { it.isFile }
                    ?.let { BitmapFactory.decodeFile(it.path) }
                    .also { thumbs[t.id] = it }
            }
            if (bmp != null) {
                h.thumb.setImageBitmap(bmp)
                h.thumb.background = null
            } else {
                h.thumb.setImageResource(R.drawable.record_disc)
            }
        }

        override fun getItemCount() = items.size

        val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false // long-press = loop; drag via ≡

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragging = true
            }

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                if (dragFrom < 0) dragFrom = a
                items.add(b, items.removeAt(a))
                notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                dragging = false
                if (dragFrom >= 0) {
                    dragFrom = -1
                    // Dropped: save once. Hidden (missing-file) tracks keep their places.
                    actions.reorder(items.map { it.id }, visibleOnly = true)
                }
                repaint()
            }
        })
    }

    // ---- small view helpers ----------------------------------------------------------------

    private fun text(res: Int, sizeSp: Float, bold: Boolean = false, sub: Boolean = false) = TextView(ctx).apply {
        if (res != 0) setText(res)
        textSize = sizeSp
        setTextColor(if (sub) st.sub else st.text)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(res: Int, primary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        setText(res)
        isAllCaps = false
        // Filled accent for the main action; tinted grey for the other (both systems do this).
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else st.accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(if (st.isIos) 12 else 22).toFloat()
            setColor(if (primary) st.accent else 0xFFE6D8BC.toInt())
        }
        minHeight = dp(44)
        setOnClickListener { onClick() }
    }

    private fun column(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it) }
    }

    private fun row(vararg buttons: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        buttons.forEachIndexed { i, b ->
            addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(8)
                },
            )
        }
    }

    private fun View.withHeight(h: Int): View {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        return this
    }


    companion object {
        private const val TAG = "TrackListPanel"
        private const val ROW_DP = 56
        private const val STAR = 0xFFFFB300.toInt()
    }
}
