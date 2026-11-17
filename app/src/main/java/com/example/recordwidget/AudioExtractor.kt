package com.example.recordwidget

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

sealed class ExtractResult {
    data class Ok(val file: File) : ExtractResult()
    object NoAudio : ExtractResult()
    data class Unsupported(val mime: String) : ExtractResult()
    data class Failed(val msg: String) : ExtractResult()
}

private const val AAC_MIME = "audio/mp4a-latm"
private const val DEFAULT_BUFFER_SIZE = 1024 * 1024
private const val MAX_BUFFER_SIZE = 64 * 1024 * 1024

/**
 * Extracts the first audio track of [src] into [out] (.m4a) without re-encoding.
 * Only AAC ("audio/mp4a-latm") is supported.
 *
 * Blocking I/O: must be called off the main thread.
 * On any result other than [ExtractResult.Ok], or on exception, [out] is deleted.
 */
fun extractAudio(ctx: Context, src: Uri, out: File): ExtractResult {
    val result: ExtractResult = try {
        doExtract(ctx, src, out)
    } catch (e: Exception) {
        ExtractResult.Failed(e.message ?: e.javaClass.simpleName)
    }
    if (result !is ExtractResult.Ok) out.delete()
    return result
}

private fun doExtract(ctx: Context, src: Uri, out: File): ExtractResult {
    out.parentFile?.mkdirs()
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var muxerStarted = false
    try {
        extractor.setDataSource(ctx, src, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) return ExtractResult.NoAudio
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime != AAC_MIME) return ExtractResult.Unsupported(mime)

        extractor.selectTrack(trackIndex)
        muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val dstTrack = muxer.addTrack(format)
        muxer.start()
        muxerStarted = true

        var buf = ByteBuffer.allocate(
            maxOf(
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
                DEFAULT_BUFFER_SIZE
            )
        )
        val info = MediaCodec.BufferInfo()
        var written = 0
        while (true) {
            var size = -1
            while (true) {
                buf.clear()
                size = try {
                    extractor.readSampleData(buf, 0)
                } catch (e: IllegalArgumentException) {
                    -2 // buffer too small
                }
                // size < 0 with a valid sampleTime means the buffer was too small, not EOS.
                if (size >= 0 || (size == -1 && extractor.sampleTime < 0)) break
                if (buf.capacity() >= MAX_BUFFER_SIZE) {
                    return ExtractResult.Failed("sample too large")
                }
                buf = ByteBuffer.allocate(minOf(buf.capacity() * 2, MAX_BUFFER_SIZE))
            }
            if (size < 0) break
            val time = extractor.sampleTime
            if (time < 0) {
                if (!extractor.advance()) break
                continue
            }
            info.set(
                0, size, time,
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            )
            muxer.writeSampleData(dstTrack, buf, info)
            written++
            if (!extractor.advance()) break
        }
        if (written == 0) return ExtractResult.Failed("no audio samples")
        // Stop on the success path so a finalize failure surfaces as Failed.
        muxerStarted = false
        muxer.stop()
        return ExtractResult.Ok(out)
    } finally {
        try { extractor.release() } catch (ignored: Exception) {}
        if (muxer != null) {
            try { if (muxerStarted) muxer.stop() } catch (ignored: Exception) {}
            try { muxer.release() } catch (ignored: Exception) {}
        }
    }
}
