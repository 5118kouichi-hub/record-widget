package com.example.recordwidget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Time slider for choosing a video frame. Drag = whole video. Keep the finger still for a
 * moment = zoom: the full width of the bar then covers only [FINE_SPAN_US] around that point,
 * so a single scene is easy to hit. Lifting the finger leaves the zoom.
 */
class FineScrubber(context: Context) : View(context) {
    var durationUs = 0L
        set(v) {
            field = v
            invalidate()
        }
    var positionUs = 0L
        private set

    /** Called while moving; [done] when the finger is lifted. */
    var onChange: ((us: Long, done: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var fine = false
    private var anchorX = 0f
    private var anchorUs = 0L
    private var downX = 0f
    private var lastX = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val enterFine = Runnable {
        if (durationUs <= FINE_SPAN_US) return@Runnable // short video: already fine
        fine = true
        anchorX = lastX
        anchorUs = positionUs
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val done = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0A526.toInt(); strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND }
    private val window = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0A526.toInt(); strokeWidth = dp(12f); strokeCap = Paint.Cap.ROUND; alpha = 120 }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3E9D2.toInt() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); strokeWidth = dp(1f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3E9D2.toInt(); textSize = dp(12f); textAlign = Paint.Align.CENTER }

    fun setPosition(us: Long) {
        positionUs = us.coerceIn(0L, durationUs.coerceAtLeast(0L))
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(64f).toInt())
    }

    private val padL get() = dp(14f)
    private val padR get() = width - dp(14f)

    override fun onDraw(c: Canvas) {
        val y = height * 0.62f
        val w = padR - padL
        if (!fine) {
            val x = padL + w * (if (durationUs > 0) positionUs.toFloat() / durationUs else 0f)
            c.drawLine(padL, y, padR, y, track)
            c.drawLine(padL, y, x, y, done)
            c.drawCircle(x, y, dp(10f), knob)
            c.drawText("指を止めると拡大", width / 2f, dp(14f), label.apply { alpha = 150 })
        } else {
            // Zoomed: ticks every second slide under a fixed knob.
            c.drawLine(padL, y, padR, y, track)
            val usPerPx = FINE_SPAN_US / w
            val startUs = positionUs - ((width / 2f - padL) * usPerPx).toLong()
            var t = (startUs / 1_000_000L) * 1_000_000L
            while (t < startUs + FINE_SPAN_US + 1_000_000L) {
                val x = padL + (t - startUs) / usPerPx
                if (x in padL..padR && t in 0..durationUs) c.drawLine(x, y - dp(8f), x, y + dp(8f), tick)
                t += 1_000_000L
            }
            c.drawLine(width / 2f - dp(30f), y, width / 2f + dp(30f), y, window)
            c.drawCircle(width / 2f, y, dp(10f), knob)
            c.drawText("拡大中：ゆっくり動かして場面を合わせる", width / 2f, dp(14f), label.apply { alpha = 255 })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durationUs <= 0) return false
        val w = (padR - padL).coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x
                lastX = e.x
                fine = false
                setPosition((((e.x - padL) / w).coerceIn(0f, 1f) * durationUs).toLong())
                onChange?.invoke(positionUs, false)
                handler.postDelayed(enterFine, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - lastX) > dp(3f)) {
                    // still moving: postpone the zoom until the finger rests again
                    handler.removeCallbacks(enterFine)
                    handler.postDelayed(enterFine, HOLD_MS)
                }
                lastX = e.x
                val us = if (fine) {
                    // the bar now spans FINE_SPAN_US: finger and picture move slowly
                    anchorUs + ((e.x - anchorX) / w * FINE_SPAN_US).toLong()
                } else {
                    (((e.x - padL) / w).coerceIn(0f, 1f) * durationUs).toLong()
                }
                setPosition(us)
                if (fine) {
                    // keep zooming from where it is, so the zoom window follows the finger
                    anchorX = e.x
                    anchorUs = positionUs
                }
                onChange?.invoke(positionUs, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(enterFine)
                fine = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onChange?.invoke(positionUs, true)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(enterFine)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val HOLD_MS = 450L
        const val FINE_SPAN_US = 10_000_000f // 10 s across the whole bar while zoomed
    }
}

/**
 * Choose the square part of a picture that becomes the jacket: drag the frame to move it,
 * pinch to make it bigger or smaller. Everything outside is dimmed.
 */
class SquareCropView(context: Context, private val src: Bitmap) : View(context) {
    private val density = resources.displayMetrics.density
    private val image = RectF() // where the picture is drawn
    private val crop = RectF() // in view coordinates, always a square inside [image]
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFF3E9D2.toInt()
        strokeWidth = 2f * density
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = density }
    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val maxSide = min(image.width(), image.height())
            val side = (crop.width() * d.scaleFactor).coerceIn(48 * density, maxSide)
            val cx = crop.centerX()
            val cy = crop.centerY()
            crop.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            keepInside()
            invalidate()
            return true
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = min((w * src.height.toFloat() / src.width).toInt(), (resources.displayMetrics.heightPixels * 0.55f).toInt())
        setMeasuredDimension(w, max(h, (120 * density).toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val scale = min(w.toFloat() / src.width, h.toFloat() / src.height)
        val iw = src.width * scale
        val ih = src.height * scale
        image.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
        val side = min(iw, ih)
        crop.set(image.centerX() - side / 2, image.centerY() - side / 2, image.centerX() + side / 2, image.centerY() + side / 2)
    }

    private fun keepInside() {
        val dx = when {
            crop.left < image.left -> image.left - crop.left
            crop.right > image.right -> image.right - crop.right
            else -> 0f
        }
        val dy = when {
            crop.top < image.top -> image.top - crop.top
            crop.bottom > image.bottom -> image.bottom - crop.bottom
            else -> 0f
        }
        crop.offset(dx, dy)
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(src, null, image, paint)
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(image, Path.Direction.CW)
            addRect(crop, Path.Direction.CW)
        }
        c.drawPath(outside, dim)
        for (i in 1..2) {
            val x = crop.left + crop.width() * i / 3
            val y = crop.top + crop.height() * i / 3
            c.drawLine(x, crop.top, x, crop.bottom, grid)
            c.drawLine(crop.left, y, crop.right, y, grid)
        }
        c.drawRect(crop, frame)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                crop.offset(e.x - lastX, e.y - lastY)
                keepInside()
                lastX = e.x
                lastY = e.y
                invalidate()
            }
        }
        return true
    }

    /** The chosen square cut out of the original picture. */
    fun cropped(): Bitmap {
        val scale = src.width / image.width()
        val l = ((crop.left - image.left) * scale).toInt().coerceIn(0, src.width - 1)
        val t = ((crop.top - image.top) * scale).toInt().coerceIn(0, src.height - 1)
        val side = (crop.width() * scale).toInt().coerceAtMost(min(src.width - l, src.height - t)).coerceAtLeast(1)
        return Bitmap.createBitmap(src, l, t, side, side, Matrix(), true)
    }
}
