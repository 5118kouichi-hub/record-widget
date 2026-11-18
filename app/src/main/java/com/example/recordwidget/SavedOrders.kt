package com.example.recordwidget

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** A named track order the user saved from the floating record's list. */
data class SavedOrder(val id: String, val name: String, val trackIds: List<String>)

/** Saved orders, newest first. Small, so kept as one JSON string in SharedPreferences. */
object SavedOrders {
    private const val PREFS = "saved_orders"
    private const val KEY = "orders"
    private const val TAG = "SavedOrders"

    fun all(ctx: Context): List<SavedOrder> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("ids")
                SavedOrder(o.getString("id"), o.getString("name"), (0 until ids.length()).map { ids.getString(it) })
            }
        } catch (e: Exception) {
            Log.w(TAG, "corrupt saved orders", e)
            emptyList()
        }
    }

    fun add(ctx: Context, order: SavedOrder) = write(ctx, listOf(order) + all(ctx))

    fun delete(ctx: Context, id: String) = write(ctx, all(ctx).filter { it.id != id })

    private fun write(ctx: Context, list: List<SavedOrder>) {
        val arr = JSONArray()
        list.forEach { o ->
            arr.put(
                JSONObject()
                    .put("id", o.id)
                    .put("name", o.name)
                    .put("ids", JSONArray(o.trackIds)),
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
