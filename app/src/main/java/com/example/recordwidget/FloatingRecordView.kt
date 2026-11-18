package com.example.recordwidget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The floating record. Drawn by the app itself, so unlike the widget it can turn the
 * thumbnail with the record and follow the finger.
 *
 * The disc is drawn once into a hardware layer and only its `rotation` changes per frame,
 * so turning costs a cheap GPU composite, not a redraw.
 *
 * Touch:
 *  - tap anywhere            -> [Listener.onTap] (play/pause)
 *  - drag from the center    -> moves the bubble ([Listener.onDragStart] / [Listener.onDrag] / [Listener.onDragEnd])
 *  - circle on the vinyl     -> scratch: clockwise = forward, counter-clockwise = back
 */
class FloatingRecordView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onTap()
        fun onDragStart()
        fun onDrag(totalDxPx: Float, totalDyPx: Float)
        fun onDragEnd()
        /** False when seeking is not possible (the record then just turns, no time shown). */
        fun onScratchStart(): Boolean
        fun onScratch(totalDeg: Float)
        fun onScratchEnd(totalDeg: Float, cancelled: Boolean)
    }

    var listener: Listener? = null

    private val discLayer = DiscLayer(context)
    private val topLayer = TopLayer(context)

    init {
        discLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        addView(discLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(topLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = true
    }

    fun setThumbnail(bmp: Bitmap?) {
        discLayer.thumb = bmp
        discLayer.invalidate() // re-rasters the layer once
    }

    fun setPlaying(playing: Boolean) {
        targetVelocity = if (playing) FULL_SPEED else 0f
        scheduleFrame()
    }

    /** Big time label over the record while scratching; null hides it. */
    fun setOverlayText(text: String?) {
        topLayer.text = text
        topLayer.invalidate()
    }

    // ---- motion: same feel as the widget (0 -> 200 deg/s in 1.2 s, back to 0 in 1.5 s) ----

    private var angle = 0f
    private var velocity = 0f // deg/s
    private var targetVelocity = 0f
    private var lastFrameNs = 0L
    private var frameScheduled = false

    private val frame = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled = false
        val dt = if (lastFrameNs == 0L) 0f else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        lastFrameNs = frameTimeNanos
        if (!scratching) {
            velocity = if (velocity < targetVelocity) {
                min(targetVelocity, velocity + ACCEL_UP * dt)
            } else {
                max(targetVelocity, velocity - ACCEL_DOWN * dt)
            }
            angle = (angle + velocity * dt) % 360f
            discLayer.rotation = angle
        }
        if (velocity > 0f || targetVelocity > 0f) scheduleFrame() else lastFrameNs = 0L
    }

    private fun scheduleFrame() {
        if (frameScheduled || !isAttachedToWindow) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNs = 0L
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    // ---- touch ---------------------------------------------------------------------------

    private enum class Mode { UNDECIDED_MOVE, UNDECIDED_SCRATCH, MOVE, SCRATCH, IGNORE }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var mode = Mode.UNDECIDED_MOVE
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var lastTouchAngle = 0f
    private var scratchTotal = 0f
    private var scratching = false
    private var seekable = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                downTime = e.eventTime
                // Inner 40% (the label) moves the bubble; the vinyl ring scratches.
                mode = if (hypot(e.x - cx, e.y - cy) < radius * 0.4f) Mode.UNDECIDED_MOVE else Mode.UNDECIDED_SCRATCH
                lastTouchAngle = touchAngle(e, cx, cy)
                scratchTotal = 0f
                return true
            }
            // A second finger: ignore the rest of this gesture (angles would jump).
            MotionEvent.ACTION_POINTER_DOWN -> {
                endGesture(cancelled = true)
                mode = Mode.IGNORE
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.IGNORE) return true
                val moved = hypot(e.rawX - downRawX, e.rawY - downRawY) > slop
                if (mode == Mode.UNDECIDED_MOVE && moved) {
                    mode = Mode.MOVE
                    listener?.onDragStart()
                }
                if (mode == Mode.UNDECIDED_SCRATCH && moved) {
                    mode = Mode.SCRATCH
                    scratching = true
                    velocity = 0f
                    lastTouchAngle = touchAngle(e, cx, cy)
                    seekable = listener?.onScratchStart() == true
                }
                when (mode) {
                    Mode.MOVE -> listener?.onDrag(e.rawX - downRawX, e.rawY - downRawY)
                    Mode.SCRATCH -> {
                        // Near the center the angle is unstable; wait for the finger to come out.
                        if (hypot(e.x - cx, e.y - cy) < radius * 0.25f) return true
                        val a = touchAngle(e, cx, cy)
                        var d = a - lastTouchAngle
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        lastTouchAngle = a
                        scratchTotal += d
                        angle = ((angle + d) % 360f + 360f) % 360f
                        discLayer.rotation = angle
                        if (seekable) listener?.onScratch(scratchTotal)
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tap = (mode == Mode.UNDECIDED_MOVE || mode == Mode.UNDECIDED_SCRATCH) &&
                    e.eventTime - downTime < TAP_MS
                endGesture(cancelled = false)
                if (tap) performClick()
                mode = Mode.UNDECIDED_MOVE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                endGesture(cancelled = true)
                mode = Mode.UNDECIDED_MOVE
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun endGesture(cancelled: Boolean) {
        when (mode) {
            Mode.MOVE -> listener?.onDragEnd()
            Mode.SCRATCH -> {
                scratching = false
                if (seekable) listener?.onScratchEnd(scratchTotal, cancelled)
                scheduleFrame()
            }
            else -> {}
        }
    }

    /** Taps and accessibility clicks (TalkBack double-tap) both play/pause. */
    override fun performClick(): Boolean {
        super.performClick()
        listener?.onTap()
        return true
    }

    /** Degrees, 0 = right, growing clockwise on screen (y points down). */
    private fun touchAngle(e: MotionEvent, cx: Float, cy: Float): Float =
        Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat()

    // ---- layers --------------------------------------------------------------------------

    /** record_disc: disc of radius 42 centered at (44,56) in a 100x100 viewport. */
    private fun discBounds(v: View, d: Drawable): Float {
        val cx = v.width / 2f
        val cy = v.height / 2f
        val s = min(v.width, v.height) / 2f / 42f
        val left = (cx - 44f * s).toInt()
        val top = (cy - 56f * s).toInt()
        val size = (100f * s).toInt()
        d.setBounds(left, top, left + size, top + size)
        return s
    }

    /** Vinyl + label + thumbnail; turned with View.rotation (pivot = center). */
    private inner class DiscLayer(ctx: Context) : View(ctx) {
        private val disc: Drawable = ContextCompat.getDrawable(ctx, R.drawable.record_disc)!!.mutate()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        var thumb: Bitmap? = null

        override fun onDraw(canvas: Canvas) {
            val s = discBounds(this, disc)
            disc.draw(canvas)
            thumb?.let {
                val cx = width / 2f
                val cy = height / 2f
                val tr = 11f * s
                rect.set(cx - tr, cy - tr, cx + tr, cy + tr)
                canvas.drawBitmap(it, null, rect, paint)
            }
        }
    }

    /** Still light reflection + the time label while scratching. */
    private inner class TopLayer(ctx: Context) : View(ctx) {
        private val sheen: Drawable = ContextCompat.getDrawable(ctx, R.drawable.record_sheen)!!.mutate()
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val pill = RectF()
        var text: String? = null

        override fun onDraw(canvas: Canvas) {
            discBounds(this, sheen)
            sheen.draw(canvas)
            val t = text ?: return
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f
            textPaint.textSize = r * 0.32f
            val w = textPaint.measureText(t) + r * 0.3f
            val h = textPaint.textSize * 1.5f
            pill.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            canvas.drawRoundRect(pill, h / 2, h / 2, pillPaint)
            canvas.drawText(t, cx, cy + textPaint.textSize * 0.36f, textPaint)
        }
    }

    companion object {
        const val FULL_SPEED = 200f // deg/s = 33 1/3 rpm
        private const val ACCEL_UP = FULL_SPEED / 1.2f
        private const val ACCEL_DOWN = FULL_SPEED / 1.5f
        private const val TAP_MS = 350L
    }
}
