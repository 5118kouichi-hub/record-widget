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
import android.widget.Toast
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
        /** New full order of the shown tracks. */
        fun reorder(ids: List<String>)
    }

    private val ctx: Context = ContextThemeWrapper(ctx.applicationContext, R.style.Theme_RecordWidget)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var root: View? = null
    private lateinit var body: FrameLayout
    private val trackAdapter = TrackAdapter()
    private val thumbs = HashMap<String, Bitmap?>()

    val isShown: Boolean get() = root != null

    fun show() {
        if (root != null) return
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
        }
        val m = ctx.resources.displayMetrics
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(COLOR_CARD)
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
                    background = null
                    contentDescription = ctx.getString(R.string.cd_close_panel)
                    setOnClickListener { hide() }
                },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
        }
        body = FrameLayout(ctx)
        card.addView(header)
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val w = min(m.widthPixels - dp(32), dp(380))
        scrim.addView(card, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

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
        root = null
        try {
            wm.removeView(r)
        } catch (e: Exception) {
            Log.w(TAG, "remove failed", e)
        }
    }

    /** Playback changed (current / loop): repaint the marks. */
    fun refresh() {
        if (root != null) trackAdapter.notifyDataSetChanged()
    }

    // ---- pages ---------------------------------------------------------------------------

    @SuppressLint("NotifyDataSetChanged")
    private fun showList() {
        val tracks = PlaybackState.playableTracks(ctx)
        trackAdapter.items = tracks.toMutableList()
        trackAdapter.notifyDataSetChanged()
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = trackAdapter
            itemAnimator = null
        }
        trackAdapter.dragHelper.attachToRecyclerView(list)
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val listH = min(maxH, dp(ROW_DP) * tracks.size.coerceAtLeast(1))
        val page = column(
            text(R.string.panel_hint, 12f, sub = true),
            if (tracks.isEmpty()) text(R.string.panel_empty, 14f, sub = true) else list.withHeight(listH),
            row(
                button(R.string.panel_save_order, primary = true) { showSave() },
                button(R.string.panel_saved_orders) { showSaved() },
            ),
        )
        setPage(page)
    }

    private fun showSave() {
        val edit = EditText(ctx).apply {
            setText(ctx.getString(R.string.panel_default_name, SavedOrders.all(ctx).size + 1))
            setSelectAllOnFocus(true)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(COLOR_TEXT)
        }
        val save = {
            val name = edit.text.toString().trim().ifEmpty { ctx.getString(R.string.panel_default_name, 1) }
            val ids = PlaybackState.playableTracks(ctx).map { it.id }
            SavedOrders.add(ctx, SavedOrder(UUID.randomUUID().toString(), name, ids))
            toast(ctx.getString(R.string.panel_saved, name))
            showList()
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
                actions.reorder(o.trackIds)
                toast(ctx.getString(R.string.panel_applied, o.name))
                showList()
            }
        }
        del.setOnClickListener {
            if (!armedDelete) {
                // Two taps: the first one only warns, so a stray tap never deletes.
                armedDelete = true
                count.text = ctx.getString(R.string.panel_tap_again_to_delete)
                count.setTextColor(COLOR_ACCENT)
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
                contentDescription = ctx.getString(R.string.cd_looping)
            }
            val handle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag)
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
                addView(handle, LinearLayout.LayoutParams(dp(44), dp(44)))
                isLongClickable = true
            }
            val vh = VH(rowView, thumb, title, loop, handle)
            rowView.setOnClickListener {
                items.getOrNull(vh.bindingAdapterPosition)?.let { actions.playTrack(it.id) }
            }
            rowView.setOnLongClickListener {
                items.getOrNull(vh.bindingAdapterPosition)?.let {
                    actions.toggleLoop(it.id)
                    val on = actions.loopingId() == it.id
                    toast(ctx.getString(if (on) R.string.panel_loop_on else R.string.panel_loop_off, it.title))
                }
                true
            }
            handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) dragHelper.startDrag(vh)
                false
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            h.title.text = t.title
            val current = t.id == actions.currentId()
            h.title.setTextColor(if (current) COLOR_ACCENT else COLOR_TEXT)
            h.title.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
            h.loop.visibility = if (t.id == actions.loopingId()) View.VISIBLE else View.GONE
            val bmp = thumbs.getOrPut(t.id) {
                TrackStore(ctx.filesDir).thumbFor(t.id).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
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
                if (dragFrom >= 0) {
                    dragFrom = -1
                    actions.reorder(items.map { it.id }) // dropped: save once
                }
            }
        })
    }

    // ---- small view helpers ----------------------------------------------------------------

    private fun text(res: Int, sizeSp: Float, bold: Boolean = false, sub: Boolean = false) = TextView(ctx).apply {
        if (res != 0) setText(res)
        textSize = sizeSp
        setTextColor(if (sub) COLOR_SUB else COLOR_TEXT)
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(res: Int, primary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        setText(res)
        isAllCaps = false
        setTextColor(COLOR_TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(if (primary) COLOR_ACCENT else COLOR_SURFACE)
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

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "TrackListPanel"
        private const val ROW_DP = 56
        private const val COLOR_CARD = 0xF21B1512.toInt()
        private const val COLOR_SURFACE = 0xFF3A2E27.toInt()
        private const val COLOR_TEXT = 0xFFF3E6D8.toInt()
        private const val COLOR_SUB = 0xFFB8A593.toInt()
        private const val COLOR_ACCENT = 0xFFE64A19.toInt()
    }
}
