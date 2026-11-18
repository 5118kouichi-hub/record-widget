package com.example.recordwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Round video thumbnails for the middle of the widget's record. Call off the main thread. */
object Thumbnails {
    private const val TAG = "Thumbnails"
    private const val SIZE = 128 // px; small so widget updates stay light
    private const val FRAME_AT_US = 1_000_000L // 1 s in: skips black first frames

    /** From a picked/shared video. Returns true when [out] was written. */
    fun fromVideo(ctx: Context, src: Uri, out: File): Boolean =
        grab(out) { it.setDataSource(ctx, src) }

    /** From a web video/audio URL (downloads only what the frame needs). */
    fun fromUrl(url: String, out: File): Boolean =
        grab(out) { it.setDataSource(url, HashMap()) }

    private fun grab(out: File, open: (MediaMetadataRetriever) -> Unit): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            open(r)
            val frame = r.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime()
                ?: r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?: return false
            val round = circle(frame)
            frame.recycle()
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
            round.recycle()
            true
        } catch (e: Exception) {
            Log.w(TAG, "no thumbnail", e)
            out.delete()
            false
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "release failed", e)
            }
        }
    }

    /** Center-crops [src] to a [SIZE] px circle with a transparent outside. */
    private fun circle(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val scale = SIZE.toFloat() / minOf(src.width, src.height).coerceAtLeast(1)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((SIZE - src.width * scale) / 2f, (SIZE - src.height * scale) / 2f)
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val r = max(SIZE / 2f - 0.5f, 1f)
        Canvas(out).drawCircle(SIZE / 2f, SIZE / 2f, r, paint)
        return out
    }
}
