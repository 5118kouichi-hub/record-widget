package com.example.recordwidget

import android.content.Context

/**
 * The record player's look, shared by the home-screen widget and the in-app player.
 * Drawings come from tools/gen_widget_res.py (tools/skins.py); all share one geometry.
 */
enum class Skin(
    val key: String,
    val label: String,
    val body: Int,
    val armUp: Int,
    val armDown: Int,
    val armIn: Int,
    val armOut: Int,
    val titleColor: Int,
    val keyDark: Boolean,
    /** Jacket colours when a track has no picture. */
    val sleeve: Int,
    val sleeveInk: Int,
) {
    RETRO(
        "retro", "レトロ（赤とクリーム）", R.drawable.player_body_retro, R.drawable.tonearm_retro_up,
        R.drawable.tonearm_retro_down, R.id.arm_in_retro, R.id.arm_out_retro, 0xFFFFF3E0.toInt(), false,
        0xFF2E4A62.toInt(), 0xFFF3E9D2.toInt(),
    ),
    SUITCASE(
        "suitcase", "スーツケース型", R.drawable.player_body_suitcase, R.drawable.tonearm_suitcase_up,
        R.drawable.tonearm_suitcase_down, R.id.arm_in_suitcase, R.id.arm_out_suitcase, 0xFFFFE6E0.toInt(), true,
        0xFFE8D9B5.toInt(), 0xFF8E1B14.toInt(),
    ),
    GRAMOPHONE(
        "gramophone", "蓄音機（木と真鍮）", R.drawable.player_body_gramophone, R.drawable.tonearm_gramophone_up,
        R.drawable.tonearm_gramophone_down, R.id.arm_in_gramophone, R.id.arm_out_gramophone, 0xFFE9CF85.toInt(), false,
        0xFF5B2333.toInt(), 0xFFE9CF85.toInt(),
    ),
    COMPACT(
        "compact", "コンパクト（ベージュ）", R.drawable.player_body_compact, R.drawable.tonearm_compact_up,
        R.drawable.tonearm_compact_down, R.id.arm_in_compact, R.id.arm_out_compact, 0xFF4A4636.toInt(), false,
        0xFFF08A5D.toInt(), 0xFFFFFFFF.toInt(),
    ),
    DJ(
        "dj", "DJターンテーブル", R.drawable.player_body_dj, R.drawable.tonearm_dj_up,
        R.drawable.tonearm_dj_down, R.id.arm_in_dj, R.id.arm_out_dj, 0xFFE0E0E0.toInt(), true,
        0xFF101010.toInt(), 0xFF2F80FF.toInt(),
    ),
    ;

    companion object {
        private const val PREFS = "look"
        private const val KEY = "skin"

        fun current(ctx: Context): Skin {
            val k = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.key == k } ?: RETRO
        }

        fun set(ctx: Context, skin: Skin) {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, skin.key).apply()
        }
    }
}
