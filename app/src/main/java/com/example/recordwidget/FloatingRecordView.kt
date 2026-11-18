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
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The floating record. Drawn by the app itself, so unlike the widget it can turn the
 * thumbnail with the record and follow the finger.
 *
 * Touch:
 *  - tap anywhere            -> [Listener.onTap] (play/pause)
 *  - drag from the center    -> moves the bubble ([Listener.onDrag], [Listener.onDragEnd])
 *  - circle on the vinyl     -> scratch: clockwise = forward, counter-clockwise = back
 */
class FloatingRecordView(context: Context) : View(context) {

    interface Listener {
        fun onTap()
        fun onDrag(dxPx: Float, dyPx: Float)
        fun onDragEnd(rawX: Float, rawY: Float)
        fun onScratchStart()
        fun onScratch(totalDeg: Float)
        fun onScratchEnd(totalDeg: Float)
    }

    var listener: Listener? = null

    private val disc: Drawable = ContextCompat.getDrawable(context, R.drawable.record_disc)!!.mutate()
    private val sheen: Drawable = ContextCompat.getDrawable(context, R.drawable.record_sheen)!!.mutate()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val thumbRect = RectF()
    private val pillRect = RectF()

    private var thumb: Bitmap? = null
    private var overlayText: String? = null

    // Motion: same feel as the widget (0 -> 200 deg/s in 1.2 s, back to 0 in 1.5 s).
    private var angle = 0f
    private var velocity = 0f // deg/s
    private var targetVelocity = 0f
    private var lastFrameNs = 0L
    private var frameScheduled = false

    fun setThumbnail(bmp: Bitmap?) {
        thumb = bmp
        invalidate()
    }

    fun setPlaying(playing: Boolean) {
        targetVelocity = if (playing) FULL_SPEED else 0f
        scheduleFrame()
    }

    /** Big time label over the record while scratching; null hides it. */
    fun setOverlayText(text: String?) {
        overlayText = text
        invalidate()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
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
                invalidate()
            }
            if (velocity > 0f || targetVelocity > 0f) scheduleFrame() else lastFrameNs = 0L
        }
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

    // ---- drawing -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        // record_disc: disc of radius 42 centered at (44,56) in a 100x100 viewport.
        val s = r / 42f
        val left = (cx - 44f * s).toInt()
        val top = (cy - 56f * s).toInt()
        val size = (100f * s).toInt()
        disc.setBounds(left, top, left + size, top + size)
        sheen.setBounds(left, top, left + size, top + size)

        canvas.save()
        canvas.rotate(angle, cx, cy)
        disc.draw(canvas)
        thumb?.let {
            val tr = 11f * s
            thumbRect.set(cx - tr, cy - tr, cx + tr, cy + tr)
            canvas.drawBitmap(it, null, thumbRect, bmpPaint)
        }
        canvas.restore()
        sheen.draw(canvas)

        overlayText?.let { text ->
            textPaint.textSize = r * 0.32f
            val w = textPaint.measureText(text) + r * 0.3f
            val h = textPaint.textSize * 1.5f
            pillRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            canvas.drawRoundRect(pillRect, h / 2, h / 2, pillPaint)
            canvas.drawText(text, cx, cy + textPaint.textSize * 0.36f, textPaint)
        }
    }

    // ---- touch ---------------------------------------------------------------------------

    private enum class Mode { UNDECIDED_MOVE, UNDECIDED_SCRATCH, MOVE, SCRATCH }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var mode = Mode.UNDECIDED_MOVE
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downTime = 0L
    private var lastTouchAngle = 0f
    private var scratchTotal = 0f
    private var scratching = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                lastRawX = e.rawX
                lastRawY = e.rawY
                downTime = e.eventTime
                val dist = hypot(e.x - cx, e.y - cy)
                // Inner 40% (the label) moves the bubble; the vinyl ring scratches.
                mode = if (dist < min(width, height) / 2f * 0.4f) Mode.UNDECIDED_MOVE else Mode.UNDECIDED_SCRATCH
                lastTouchAngle = touchAngle(e, cx, cy)
                scratchTotal = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = hypot(e.rawX - downRawX, e.rawY - downRawY) > slop
                if (mode == Mode.UNDECIDED_MOVE && moved) mode = Mode.MOVE
                if (mode == Mode.UNDECIDED_SCRATCH && moved) {
                    mode = Mode.SCRATCH
                    scratching = true
                    velocity = 0f
                    lastTouchAngle = touchAngle(e, cx, cy)
                    listener?.onScratchStart()
                }
                when (mode) {
                    Mode.MOVE -> {
                        listener?.onDrag(e.rawX - lastRawX, e.rawY - lastRawY)
                        lastRawX = e.rawX
                        lastRawY = e.rawY
                    }
                    Mode.SCRATCH -> {
                        val a = touchAngle(e, cx, cy)
                        var d = a - lastTouchAngle
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        lastTouchAngle = a
                        scratchTotal += d
                        angle = ((angle + d) % 360f + 360f) % 360f
                        invalidate()
                        listener?.onScratch(scratchTotal)
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    Mode.MOVE -> listener?.onDragEnd(e.rawX, e.rawY)
                    Mode.SCRATCH -> {
                        scratching = false
                        listener?.onScratchEnd(scratchTotal)
                        scheduleFrame()
                    }
                    else -> if (up && e.eventTime - downTime < TAP_MS) {
                        performClick()
                        listener?.onTap()
                    }
                }
                mode = Mode.UNDECIDED_MOVE
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    /** Degrees, 0 = right, growing clockwise on screen (y points down). */
    private fun touchAngle(e: MotionEvent, cx: Float, cy: Float): Float =
        Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat()

    companion object {
        const val FULL_SPEED = 200f // deg/s = 33 1/3 rpm
        private const val ACCEL_UP = FULL_SPEED / 1.2f
        private const val ACCEL_DOWN = FULL_SPEED / 1.5f
        private const val TAP_MS = 350L
    }
}
