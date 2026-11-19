package com.example.recordwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.File

/**
 * The record jacket that lies on the player's deck: the track's picture printed on a
 * cardboard sleeve (rounded corners, worn edge, a little gloss). Without a picture, a plain
 * sleeve in the skin's colours with a printed ring. Call off the main thread.
 */
object Jackets {
    const val SIZE = 200

    fun make(ctx: Context, trackId: String?, title: String?, skin: Skin): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = SIZE.toFloat()
        val rect = RectF(0f, 0f, s, s)
        val radius = s * 0.025f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val root = ctx.applicationContext.filesDir
        val art = trackId?.let { File(root, "art/$it.jpg") }?.takeIf { it.isFile }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        val round = if (art == null) trackId?.let { File(root, "thumbs/$it.png") }?.takeIf { it.isFile }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() } else null

        if (art != null) {
            val m = Matrix().apply { setScale(s / art.width, s / art.height) }
            p.shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            c.drawRoundRect(rect, radius, radius, p)
            p.shader = null
            art.recycle()
        } else if (round != null) {
            // Only the round label picture exists (tracks added before jackets): enlarge it
            // until the circle covers the whole sleeve, so the picture fills the jacket.
            val d = s * 1.45f
            c.save()
            c.clipPath(android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) })
            c.drawBitmap(round, null, RectF((s - d) / 2, (s - d) / 2, (s + d) / 2, (s + d) / 2), p)
            c.restore()
            round.recycle()
        } else {
            p.color = skin.sleeve
            c.drawRoundRect(rect, radius, radius, p)
            // printed design: rings like a record and the title
            p.style = Paint.Style.STROKE
            p.color = skin.sleeveInk
            p.alpha = 90
            for (i in 1..4) {
                p.strokeWidth = s * 0.012f
                c.drawCircle(s / 2, s * 0.46f, s * (0.12f + 0.07f * i), p)
            }
            p.style = Paint.Style.FILL
            p.alpha = 255
            c.drawCircle(s / 2, s * 0.46f, s * 0.11f, p)
            title?.takeIf { it.isNotBlank() }?.let {
                val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = skin.sleeveInk
                    textSize = s * 0.085f
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                }
                val t = android.text.TextUtils.ellipsize(it, tp, s * 0.86f, android.text.TextUtils.TruncateAt.END).toString()
                c.drawText(t, s / 2, s * 0.9f, tp)
            }
        }

        // gloss from the top-left, darker toward the bottom-right
        p.shader = LinearGradient(0f, 0f, s, s, intArrayOf(0x33FFFFFF, 0x00FFFFFF, 0x26000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, p)
        // slightly worn cardboard: lighter at the corners
        p.shader = RadialGradient(s / 2, s / 2, s * 0.75f, intArrayOf(0x00000000, 0x00000000, 0x30FFFFFF), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, p)
        p.shader = null
        // the opening of the sleeve (top edge) and a thin outline
        p.color = 0x40000000
        c.drawRect(s * 0.06f, 0f, s * 0.94f, s * 0.012f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.008f
        p.color = 0x33000000
        c.drawRoundRect(RectF(1f, 1f, s - 1f, s - 1f), radius, radius, p)
        return out
    }
}
