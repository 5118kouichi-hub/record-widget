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

    /**
     * From a web video, on its own thread so the import queue never waits for it.
     * MediaMetadataRetriever cannot be cancelled; if the server is slow we simply give up
     * waiting after a while and the record shows no thumbnail.
     */
    fun fromUrlInBackground(ctx: Context, url: String, out: File) {
        val app = ctx.applicationContext
        val worker = Thread {
            if (grab(out) { it.setDataSource(url, HashMap()) }) RecordWidget.refresh(app)
        }
        worker.isDaemon = true
        worker.start()
    }

    private fun grab(out: File, open: (MediaMetadataRetriever) -> Unit): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            open(r)
            val frame = r.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime()
                ?: r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?: return false
            save(frame, out)
            frame.recycle()
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

    /**
     * Writes the round label picture to [roundOut] and the square jacket picture next to it
     * (`art/<id>.jpg`, see [artFor]). Used for imports and for a frame/photo the user picked.
     */
    fun save(frame: Bitmap, roundOut: File) {
        val round = circle(frame)
        roundOut.parentFile?.mkdirs()
        FileOutputStream(roundOut).use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
        round.recycle()
        val art = artFor(roundOut)
        art.parentFile?.mkdirs()
        val sq = square(frame, ART_SIZE)
        FileOutputStream(art).use { sq.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        sq.recycle()
    }

    /** `thumbs/<id>.png` -> `art/<id>.jpg` (same root). */
    fun artFor(thumb: File): File =
        File(thumb.parentFile?.parentFile, "art/" + thumb.nameWithoutExtension + ".jpg")

    /** A video frame at [atUs], scaled down for previews. Off the main thread. */
    fun frameAt(ctx: Context, src: Uri, atUs: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, src)
            val f = if (android.os.Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
            } else {
                r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            f
        } catch (e: Exception) {
            Log.w(TAG, "no frame", e)
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    fun durationUs(ctx: Context, src: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, src)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    private const val ART_SIZE = 360

    private fun square(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = size.toFloat() / minOf(src.width, src.height).coerceAtLeast(1)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
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
