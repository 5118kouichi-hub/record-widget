package com.example.recordwidget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * FAB fan: the round button in the top-right corner. Tapped, its ☰ turns into ✕ and small
 * round buttons fan out down-left along an arc, each tied to the button by a fine line.
 * Choosing one rings it, the fan folds back into the button, then the action runs.
 */
class FanMenu(
    private val activity: Activity,
    private val s: UiStyle,
    private val items: List<Item>,
) {
    /** The screen's root; the fan opens in it, under [button]. Set by the screen. */
    lateinit var host: FrameLayout

    class Item(val icon: Int, val label: Int, val action: () -> Unit)

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    /** The button that lives in the screen's header. */
    val button: FrameLayout
    private val iconMenu: ImageView
    private val iconClose: ImageView

    private var overlay: Overlay? = null
    private var animating = false

    init {
        iconMenu = ImageView(activity).apply {
            setImageResource(R.drawable.ic_menu)
            imageTintList = ColorStateList.valueOf(s.onBg)
        }
        iconClose = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(s.onBg)
            alpha = 0f
            rotation = -90f
        }
        button = FrameLayout(activity).apply {
            background = ring(s.onBg, fill = 0x33000000)
            contentDescription = activity.getString(R.string.cd_fan_open)
            addView(iconMenu, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            addView(iconClose, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            isClickable = true
            // NB: inside apply{} a bare `overlay` would be View.getOverlay(), never null.
            setOnClickListener { if (this@FanMenu.overlay == null) open() else close(null) }
            elevation = dp(4).toFloat()
        }
    }

    private fun ring(stroke: Int, fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(1).coerceAtLeast(2), stroke)
    }

    val isOpen get() = overlay != null

    // ---- open / close --------------------------------------------------------------------

    fun open() {
        if (overlay != null || animating) return
        val o = Overlay()
        // just below the button, so the button (now ✕) stays bright above the dimmed screen
        host.addView(o, host.indexOfChild(button), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = o
        button.contentDescription = activity.getString(R.string.cd_fan_close)
        o.post { o.layoutItems() }
        morph(true)
        animate(0f, 1f, 420L, OvershootInterpolator(1.3f)) { o.progress = it }
    }

    /** [then] runs after the fan has folded away. */
    fun close(then: (() -> Unit)?) {
        val o = overlay ?: return
        if (animating) return
        morph(false)
        animate(1f, 0f, 260L, DecelerateInterpolator()) { o.progress = it }.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                (o.parent as? ViewGroup)?.removeView(o)
                overlay = null
                button.contentDescription = activity.getString(R.string.cd_fan_open)
                then?.invoke()
            }
        })
    }

    private fun morph(toClose: Boolean) {
        iconMenu.animate().alpha(if (toClose) 0f else 1f).rotation(if (toClose) 90f else 0f).setDuration(220).start()
        iconClose.animate().alpha(if (toClose) 1f else 0f).rotation(if (toClose) 0f else -90f).setDuration(220).start()
    }

    private fun animate(from: Float, to: Float, ms: Long, interp: android.animation.TimeInterpolator, onValue: (Float) -> Unit) =
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener { onValue(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    animating = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    animating = false
                }
            })
            start()
        }

    // ---- the fan -------------------------------------------------------------------------

    /** Dim layer + lines + the item buttons. Tap outside closes. */
    private inner class Overlay : FrameLayout(activity) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.onBgSub
            strokeWidth = density
        }
        private val itemViews = items.map { itemView(it) }
        private val targets = Array(items.size) { floatArrayOf(0f, 0f) }
        private var cx = 0f
        private var cy = 0f
        private var ringItem = -1
        private var ringProgress = 0f
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = s.mustard
            strokeWidth = 1.5f * density
        }

        var progress = 0f
            set(v) {
                field = v
                apply()
            }

        init {
            setWillNotDraw(false)
            isClickable = true
            setOnClickListener { close(null) }
            itemViews.forEach { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
        }

        /** Arc down-left of the button: from pointing left to pointing straight down. */
        fun layoutItems() {
            val here = IntArray(2).also { getLocationInWindow(it) }
            val b = IntArray(2).also { button.getLocationInWindow(it) }
            cx = b[0] - here[0] + button.width / 2f
            cy = b[1] - here[1] + button.height / 2f
            val radius = dp(118).toFloat()
            val n = items.size
            for (i in 0 until n) {
                val deg = 172.0 - i * (80.0 / (n - 1).coerceAtLeast(1)) // 172° -> 92°
                val a = Math.toRadians(deg)
                // stagger the radius a little, like the reference: alternating near / far
                val r = radius + if (i % 2 == 1) dp(26) else 0
                targets[i][0] = cx + (r * cos(a)).toFloat()
                targets[i][1] = cy + (r * sin(a)).toFloat()
            }
            apply()
        }

        private fun apply() {
            setBackgroundColor(((0xAA * progress.coerceIn(0f, 1f)).toInt() shl 24) or 0x0E0907)
            val n = itemViews.size
            itemViews.forEachIndexed { i, v ->
                // stagger: later items start a little later
                val local = ((progress - i * 0.08f) / (1f - (n - 1) * 0.08f)).coerceIn(0f, 1.15f)
                val circle = v.getChildAt(0)
                val tx = cx + (targets[i][0] - cx) * local
                val ty = cy + (targets[i][1] - cy) * local
                v.translationX = tx - v.width / 2f
                v.translationY = ty - circle.height / 2f
                val sc = local.coerceIn(0f, 1f)
                v.scaleX = 0.4f + 0.6f * sc
                v.scaleY = 0.4f + 0.6f * sc
                v.alpha = sc
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // fine lines from the button to each item
            itemViews.forEachIndexed { i, v ->
                if (v.alpha <= 0f) return@forEachIndexed
                linePaint.alpha = (v.alpha * 140).toInt()
                val circle = v.getChildAt(0)
                val ix = v.translationX + v.width / 2f
                val iy = v.translationY + circle.height / 2f
                canvas.drawLine(cx, cy, ix, iy, linePaint)
            }
            if (ringItem >= 0) {
                val v = itemViews[ringItem]
                val circle = v.getChildAt(0)
                val ix = v.translationX + v.width / 2f
                val iy = v.translationY + circle.height / 2f
                ringPaint.alpha = ((1f - ringProgress) * 255).toInt()
                canvas.drawCircle(ix, iy, circle.width / 2f * (1f + ringProgress * 0.9f), ringPaint)
            }
        }

        private fun itemView(item: Item): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val circle = FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(s.card)
                    setStroke(dp(1).coerceAtLeast(2), s.text)
                }
                elevation = dp(3).toFloat()
                addView(
                    ImageView(activity).apply {
                        setImageResource(item.icon)
                        imageTintList = ColorStateList.valueOf(s.text)
                    },
                    LayoutParams(dp(20), dp(20), Gravity.CENTER),
                )
            }
            addView(circle, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(
                TextView(activity).apply {
                    setText(item.label)
                    textSize = 11f
                    letterSpacing = 0.12f
                    typeface = s.serifBold
                    setTextColor(s.onBg)
                    setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
                    setPadding(0, dp(4), 0, 0)
                },
            )
            contentDescription = activity.getString(item.label)
            isClickable = true
            setOnClickListener { choose(item) }
        }

        private fun choose(item: Item) {
            if (animating) return
            ringItem = items.indexOf(item)
            // ring out, then fold everything back into the button, then act
            animate(0f, 1f, 220L, DecelerateInterpolator()) {
                ringProgress = it
                invalidate()
            }.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ringItem = -1
                    close(item.action)
                }
            })
        }
    }
}
