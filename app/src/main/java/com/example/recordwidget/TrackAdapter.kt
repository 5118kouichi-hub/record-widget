package com.example.recordwidget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val onRename: (Track) -> Unit,
    private val onDelete: (Track) -> Unit,
    private val onMove: (from: Int, to: Int) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    private var items: List<Track> = emptyList()

    fun submit(list: List<Track>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val up: Button = v.findViewById(R.id.upButton)
        val down: Button = v.findViewById(R.id.downButton)
        val delete: Button = v.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val t = items[position]
        h.title.text = t.title
        h.up.isEnabled = position > 0
        h.down.isEnabled = position < items.size - 1
        h.title.setOnClickListener { onRename(t) }
        h.delete.setOnClickListener { onDelete(t) }
        h.up.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p > 0) onMove(p, p - 1)
        }
        h.down.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && p < items.size - 1) onMove(p, p + 1)
        }
    }
}
