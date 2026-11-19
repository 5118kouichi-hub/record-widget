package com.example.recordwidget

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import java.io.File

/**
 * The app's display font. Bundled ones are only those whose license allows putting them in
 * an app (SIL OFL; licenses in assets/licenses). Any other font the user downloads from its
 * official site and loads from a file: it stays on that phone and is never passed on.
 * The home-screen widget cannot use custom fonts (Android limit), only the app screens.
 */
object Fonts {
    data class Choice(val key: String, val label: String)

    private val BUNDLED = linkedMapOf(
        "zen" to ("Zen Old Mincho（標準）" to R.font.zen_old_mincho_bold),
        "kiwi" to ("Kiwi Maru" to R.font.kiwi_maru_medium),
        "hachimaru" to ("はちまるポップ" to R.font.hachi_maru_pop),
        "nicomoji" to ("ニコモジ（かな・英字）" to R.font.nico_moji),
    )

    private const val PREFS = "look"
    private const val KEY = "font"

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "fonts")

    fun choices(ctx: Context): List<Choice> =
        BUNDLED.map { (k, v) -> Choice(k, v.first) } +
            (dir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                ?.map { Choice("file:" + it.name, it.nameWithoutExtension + "（読み込み）") } ?: emptyList())

    fun currentKey(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "zen") ?: "zen"

    fun select(ctx: Context, key: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key).commit()
    }

    /** The chosen face, or null to fall back (missing file, broken font). */
    fun load(ctx: Context): Typeface? {
        val key = currentKey(ctx)
        return try {
            if (key.startsWith("file:")) {
                val f = File(dir(ctx), key.removePrefix("file:"))
                if (f.isFile) Typeface.createFromFile(f) else null
            } else {
                ResourcesCompat.getFont(ctx, (BUNDLED[key] ?: BUNDLED.getValue("zen")).second)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Copies a .ttf/.otf the user picked into the app. Returns its key, or null if not a font. Off the main thread. */
    fun import(ctx: Context, uri: Uri, displayName: String?): String? {
        val d = dir(ctx).apply { mkdirs() }
        val base = (displayName ?: "font").substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _\\-+]"), "_").take(40).ifBlank { "font" }
        val ext = displayName?.substringAfterLast('.', "ttf")?.lowercase()?.takeIf { it == "otf" || it == "ttf" || it == "ttc" } ?: "ttf"
        val out = File(d, "$base.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
        val ok = try {
            Typeface.createFromFile(out) != Typeface.DEFAULT
        } catch (e: Exception) {
            false
        }
        if (!ok || out.length() < 1000) {
            out.delete()
            return null
        }
        return "file:" + out.name
    }

    fun remove(ctx: Context, key: String) {
        if (!key.startsWith("file:")) return
        File(dir(ctx), key.removePrefix("file:")).delete()
        if (currentKey(ctx) == key) select(ctx, "zen")
    }
}
