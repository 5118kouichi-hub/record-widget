package com.example.recordwidget

import android.content.Context
import android.graphics.Typeface

/**
 * The app's one look: a retro listening room that matches the widget's portable player.
 * Dark walnut background, cream paper cards, the player's red, mustard captions, an old-style
 * Mincho display face.
 */
class UiStyle private constructor(ctx: Context) {
    /** Walnut background and text on it. */
    val bg = 0xFF1C130F.toInt()
    val bgGrain = 0x14FFE0B2
    val onBg = 0xFFF3E9D2.toInt()
    val onBgSub = 0xFFB9A58F.toInt()

    /** Cream paper cards and text on them. */
    val card = 0xFFF3E9D2.toInt()
    val text = 0xFF2A1F1A.toInt()
    val sub = 0xFF7A6A5C.toInt()
    val separator = 0xFFE0D2B6.toInt()

    val accent = 0xFFC62828.toInt() // the player's red
    val mustard = 0xFFE0A526.toInt()
    val danger = 0xFFB3261E.toInt()
    val switchOn = accent
    val pressed = 0x1F3E2723

    val cardRadiusDp = 16
    val rowHeightDp = 56
    val groupMarginDp = 16

    /** Kept for the list panel's shape choices (no iOS-like look any more). */
    val isIos = false

    /** The cards are light whatever the system mode. */
    val dark = false

    /**
     * Display face: Zen Old Mincho Bold (SIL OFL, bundled; license in assets/licenses).
     * Characters outside the bundled set fall back to the system font automatically.
     */
    val serifBold: Typeface = Fonts.load(ctx) ?: try {
        androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.zen_old_mincho_bold)
    } catch (e: Exception) {
        null
    } ?: Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    companion object {
        @Volatile private var instance: UiStyle? = null

        fun current(ctx: Context): UiStyle =
            instance ?: synchronized(this) { instance ?: UiStyle(ctx.applicationContext).also { instance = it } }

        /** After the font changed: the next [current] builds the style again. */
        fun reset() {
            instance = null
        }

        fun themeRes(@Suppress("UNUSED_PARAMETER") ctx: Context): Int = R.style.Theme_RecordWidget_Retro
    }
}
