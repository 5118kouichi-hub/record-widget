package com.example.recordwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min

/**
 * The big record player at the top of the app screen: the same portable player as the
 * home-screen widget (same drawings, same layout), drawn by the app so the record and the
 * thumbnail turn together.
 *
 * Tap the record = play/pause; the two cream keys = previous / next.
 */
class HeroPlayerView(context: Context) : View(context) {

    interface Listener {
        fun onToggle()
        fun onPrevious()
        fun onNext()

        /** Finger started turning the record. False = this track cannot be moved in. */
        fun onScratchStart(): Boolean = false
        /** [totalDeg] turned so far (clockwise = forward). Returns the time to show. */
        fun onScratch(totalDeg: Float): String? = null
        fun onScratchEnd(totalDeg: Float, cancelled: Boolean) {}
        /** Long press on the jacket; [onScreen] = where it is drawn, in screen pixels. */
        fun onJacketLongPress(onScreen: RectF) {}
    }

    var listener: Listener? = null

    /** Face for the title on the red front (the app's display font). */
    var titleTypeface: android.graphics.Typeface? = null
        set(v) {
            field = v
            titlePaint.typeface = v
            invalidate()
        }

    /** Which player is drawn (same choice as the widget). */
    var skin: Skin = Skin.RETRO
        set(v) {
            field = v
            body = drawable(v.body)
            armUp = drawable(v.armUp)
            val ink = if (v.keyDark) 0xFFEDEDED.toInt() else 0xFF3A3A3A.toInt()
            keyPrev.setTint(ink)
            keyNext.setTint(ink)
            keyFace.color = if (v.keyDark) 0xFF3A3A3E.toInt() else 0xFFFBF5E6.toInt()
            keySide.color = if (v.keyDark) 0xFF050505.toInt() else 0xFFC9BB9C.toInt()
            titlePaint.color = v.titleColor
            invalidate()
        }

    private var body: Drawable = drawable(R.drawable.player_body_retro)
    private val disc: Drawable = drawable(R.drawable.record_disc)
    private val sheen: Drawable = drawable(R.drawable.record_sheen)
    private var armUp: Drawable = drawable(R.drawable.tonearm_retro_up)
    private val keyPrev: Drawable = drawable(R.drawable.ic_prev).apply { setTint(0xFF3A3A3A.toInt()) }
    private val keyNext: Drawable = drawable(R.drawable.ic_next).apply { setTint(0xFF3A3A3A.toInt()) }

    private fun drawable(id: Int) = ContextCompat.getDrawable(context, id)!!.mutate()

    private val keyFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFBF5E6.toInt() }
    private val keySide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9BB9C.toInt() }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFF3E0.toInt()
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private var title: String = ""
    private var thumb: Bitmap? = null
    private var jacket: Bitmap? = null
    private var turning = false

    // Tonearm: swings onto the record shortly after it starts turning, rides it with a tiny
    // wobble (a real record is never perfectly flat), lifts back when stopped.
    private var armAngle = 0f
    private var playingFor = 0f

    // Same feel as the widget: 0 -> 200 deg/s in 1.2 s, back to 0 in 1.5 s.
    private var angle = 0f
    private var velocity = 0f
    private var lastNs = 0L
    private var scheduled = false

    fun show(title: String, thumb: Bitmap?, turning: Boolean, jacket: Bitmap? = this.jacket) {
        this.title = title
        this.thumb = thumb
        this.jacket = jacket
        this.turning = turning
        contentDescription = context.getString(if (turning) R.string.cd_disc_playing else R.string.cd_disc_stopped) + " " + title
        schedule()
        invalidate()
    }

    // ---- geometry (viewport of player_body: 300 x 220) -------------------------------------

    private val k get() = width / 300f
    private val discBox = RectF()
    private val prevKey = RectF()
    private val nextKey = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 220f / 300f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val k = w / 300f
        discBox.set(105 * k, 10 * k, (105 + 185) * k, (10 + 185) * k)
        val key = 26 * k
        val top = 196 * k - key - 3 * k
        prevKey.set(18 * k, top, 18 * k + key, top + key)
        nextKey.set(prevKey.right + 6 * k, top, prevKey.right + 6 * k + key, top + key)
    }

    private fun Drawable.place(r: RectF) = setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())

    override fun onDraw(canvas: Canvas) {
        body.setBounds(0, 0, width, height)
        body.draw(canvas)

        // record + thumbnail turn about the disc center (44%, 56% of the box)
        val cx = discBox.left + 0.44f * discBox.width()
        val cy = discBox.top + 0.56f * discBox.height()
        canvas.save()
        canvas.rotate(angle, cx, cy)
        disc.place(discBox)
        disc.draw(canvas)
        thumb?.let {
            val r = 0.11f * discBox.width()
            canvas.drawBitmap(it, null, RectF(cx - r, cy - r, cx + r, cy + r), bmpPaint)
        }
        canvas.restore()
        sheen.place(discBox)
        sheen.draw(canvas)
        jacket?.let {
            val l = 14 * k
            val t = 66 * k
            canvas.drawBitmap(it, null, RectF(l, t, l + 80 * k, t + 80 * k), bmpPaint)
        }
        canvas.save()
        canvas.rotate(armAngle, discBox.left + 0.88f * discBox.width(), discBox.top + 0.12f * discBox.height())
        armUp.place(discBox)
        armUp.draw(canvas)
        canvas.restore()
        overlayText?.let {
            overlayPaint.textSize = 26 * k
            canvas.drawText(it, recordCx(), recordCy() + 9 * k, overlayPaint)
        }

        drawKey(canvas, prevKey, keyPrev)
        drawKey(canvas, nextKey, keyNext)

        // title on the red front of the case
        titlePaint.textSize = 12 * k // fits the 24-unit red front
        val t = TextUtils.ellipsize(title, titlePaint, width - 24 * k, TextUtils.TruncateAt.END).toString()
        val lipCenter = (196 + 12) * k
        canvas.drawText(t, width / 2f, lipCenter + titlePaint.textSize * 0.35f, titlePaint)
    }

    private fun drawKey(canvas: Canvas, r: RectF, icon: Drawable) {
        val radius = 5 * k
        canvas.drawRoundRect(r.left, r.top + 1.5f * k, r.right, r.bottom, radius, radius, keySide)
        canvas.drawRoundRect(r.left, r.top, r.right, r.bottom - 1.5f * k, radius, radius, keyFace)
        val p = r.width() * 0.22f
        icon.setBounds((r.left + p).toInt(), (r.top + p).toInt(), (r.right - p).toInt(), (r.bottom - p).toInt())
        icon.draw(canvas)
    }

    // ---- motion --------------------------------------------------------------------------

    private val frame = Choreographer.FrameCallback { now ->
        scheduled = false
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.1f)
        lastNs = now
        val target = if (turning) FloatingRecordView.FULL_SPEED else 0f
        velocity = if (velocity < target) min(target, velocity + FloatingRecordView.FULL_SPEED / 1.2f * dt)
        else max(target, velocity - FloatingRecordView.FULL_SPEED / 1.5f * dt)
        angle = (angle + velocity * dt) % 360f
        playingFor = if (turning) playingFor + dt else 0f
        val armTarget = when {
            !turning -> 0f
            playingFor < 0.25f -> armAngle // the platter starts first, then the arm moves
            else -> ARM_DOWN + 0.3f * sin(playingFor * 3.5f).toFloat() // warp wobble
        }
        val step = ARM_SPEED * dt
        armAngle = if (abs(armTarget - armAngle) <= step) armTarget else armAngle + step * sign(armTarget - armAngle)
        invalidate()
        if (velocity > 0f || turning || armAngle != 0f) schedule() else lastNs = 0L
    }

    private fun schedule() {
        if (scheduled || !isAttachedToWindow) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNs = 0L
        schedule()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        scheduled = false
        super.onDetachedFromWindow()
    }

    // ---- touch ---------------------------------------------------------------------------

    private var downX = 0f
    private var downY = 0f
    private var onRecord = false
    private var scratching = false
    private var scratchable = false
    private var lastDeg = 0f
    private var scratchTotal = 0f
    private var overlayText: String? = null
    private val overlayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
    }

    /** Where the jacket is drawn (view pixels). */
    private fun jacketRect() = RectF(14 * k, 66 * k, 94 * k, 146 * k)

    private var jacketHeld = false
    private val jacketLongPress = Runnable {
        jacketHeld = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val r = jacketRect().apply { offset(loc[0].toFloat(), loc[1].toFloat()) }
        listener?.onJacketLongPress(r)
    }

    private fun recordCx() = discBox.left + 0.44f * discBox.width()
    private fun recordCy() = discBox.top + 0.56f * discBox.height()
    private fun degAt(e: MotionEvent) =
        Math.toDegrees(kotlin.math.atan2((e.y - recordCy()).toDouble(), (e.x - recordCx()).toDouble())).toFloat()

    private fun endScratch(cancelled: Boolean) {
        if (!scratching) return
        scratching = false
        overlayText = null
        if (scratchable) listener?.onScratchEnd(scratchTotal, cancelled)
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                val dist = hypot(e.x - recordCx(), e.y - recordCy())
                onRecord = dist < 0.48f * discBox.width() && dist > 0.12f * discBox.width()
                scratching = false
                jacketHeld = false
                if (onRecord) parent?.requestDisallowInterceptTouchEvent(true) // don't scroll the page
                if (jacket != null && jacketRect().contains(e.x, e.y)) postDelayed(jacketLongPress, 450L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > 10 * resources.displayMetrics.density) removeCallbacks(jacketLongPress)
                if (!onRecord) return true
                if (!scratching) {
                    if (hypot(e.x - downX, e.y - downY) < 10 * resources.displayMetrics.density) return true
                    scratching = true
                    scratchTotal = 0f
                    lastDeg = degAt(e)
                    scratchable = listener?.onScratchStart() == true
                    return true
                }
                val now = degAt(e)
                var d = now - lastDeg
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                lastDeg = now
                scratchTotal += d
                angle = ((angle + d) % 360f + 360f) % 360f // the record follows the finger
                if (scratchable) overlayText = listener?.onScratch(scratchTotal)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(jacketLongPress)
                endScratch(cancelled = true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(jacketLongPress)
                if (jacketHeld) return true
                if (scratching) {
                    endScratch(cancelled = false)
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                if (hypot(e.x - downX, e.y - downY) > 24 * resources.displayMetrics.density) return true
                val pad = 6 * k
                when {
                    hit(prevKey, e, pad) -> listener?.onPrevious()
                    hit(nextKey, e, pad) -> listener?.onNext()
                    hypot(e.x - (discBox.left + 0.44f * discBox.width()), e.y - (discBox.top + 0.56f * discBox.height())) <
                        0.46f * discBox.width() -> performClick()
                }
                return true
            }
        }
        return true
    }

    private fun hit(r: RectF, e: MotionEvent, pad: Float) =
        e.x >= r.left - pad && e.x <= r.right + pad && e.y >= r.top - pad && e.y <= r.bottom + pad

    private companion object {
        const val ARM_DOWN = 31.5f
        const val ARM_SPEED = 32f // degrees per second, about a second to swing in
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onToggle()
        return true
    }
}
