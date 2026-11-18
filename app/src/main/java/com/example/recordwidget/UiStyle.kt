package com.example.recordwidget

import android.content.Context
import android.content.res.Configuration

/**
 * The two looks the user can pick for the app screen and the floating record's list:
 * Galaxy One UI-like (default) and iPhone iOS-like. Both follow the system light/dark mode.
 *
 * Only colors, sizes and layout rhythm imitate those systems; no fonts, icons or other
 * assets are copied.
 */
enum class UiKind { ONE_UI, IOS }

class UiStyle private constructor(val kind: UiKind, val dark: Boolean) {
    val isIos get() = kind == UiKind.IOS

    val bg: Int
    val card: Int
    val text: Int
    val sub: Int
    val accent: Int
    val separator: Int
    val danger: Int
    val switchOn: Int
    val pressed: Int

    /** Card corner radius, row height, side margin of groups (dp). */
    val cardRadiusDp: Int
    val rowHeightDp: Int
    val groupMarginDp: Int

    init {
        if (kind == UiKind.IOS) {
            bg = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
            card = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
            text = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            sub = if (dark) 0xFF8D8D93.toInt() else 0xFF8A8A8E.toInt()
            accent = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
            separator = if (dark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
            danger = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
            switchOn = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
            cardRadiusDp = 10
            rowHeightDp = 50
            groupMarginDp = 16
        } else {
            bg = if (dark) 0xFF000000.toInt() else 0xFFF6F6F6.toInt()
            card = if (dark) 0xFF171717.toInt() else 0xFFFFFFFF.toInt()
            text = if (dark) 0xFFFAFAFA.toInt() else 0xFF0B0B0B.toInt()
            sub = if (dark) 0xFF9E9E9E.toInt() else 0xFF737373.toInt()
            accent = if (dark) 0xFF5B9DFF.toInt() else 0xFF2F7BF5.toInt()
            separator = if (dark) 0xFF2A2A2A.toInt() else 0xFFEDEDED.toInt()
            danger = if (dark) 0xFFFF6B6B.toInt() else 0xFFE53935.toInt()
            switchOn = accent
            cardRadiusDp = 26
            rowHeightDp = 60
            groupMarginDp = 12
        }
        pressed = if (dark) 0x33FFFFFF else 0x1F000000
    }

    companion object {
        private const val PREFS = "ui"
        private const val KEY = "style"

        fun kind(ctx: Context): UiKind =
            when (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)) {
                "ios" -> UiKind.IOS
                else -> UiKind.ONE_UI // default
            }

        fun setKind(ctx: Context, kind: UiKind) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, if (kind == UiKind.IOS) "ios" else "oneui").apply()
        }

        fun current(ctx: Context): UiStyle {
            val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return UiStyle(kind(ctx), night == Configuration.UI_MODE_NIGHT_YES)
        }

        fun themeRes(ctx: Context): Int =
            if (kind(ctx) == UiKind.IOS) R.style.Theme_RecordWidget_IOS else R.style.Theme_RecordWidget_OneUI
    }
}
