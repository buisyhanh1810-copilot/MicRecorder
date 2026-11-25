package vn.micrecorder.app

import android.annotation.SuppressLint
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import java.text.DateFormat
import java.util.Date

class RecordingsAdapter(
    private val onClick: (Recording) -> Unit,
    private val onMore: (Recording, View) -> Unit,
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    var items: List<Recording> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) { field = value; notifyDataSetChanged() }

    var selectedPath: String? = null
        @SuppressLint("NotifyDataSetChanged")
        set(value) { field = value; notifyDataSetChanged() }

    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val details: TextView = v.findViewById(R.id.details)
        val more: ImageButton = v.findViewById(R.id.more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        val ctx = h.itemView.context
        h.name.text = r.file.nameWithoutExtension
        h.details.text = buildString {
            append(dateFormat.format(Date(r.lastModified)))
            append("  •  ")
            append(formatDuration(r.durationMs))
            append("  •  ")
            append(Formatter.formatShortFileSize(ctx, r.sizeBytes))
        }
        val selected = r.file.absolutePath == selectedPath
        val color = if (selected)
            MaterialColors.getColor(h.itemView, androidx.appcompat.R.attr.colorPrimary)
        else MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorOnSurface)
        h.name.setTextColor(color)
        h.icon.setColorFilter(color)
        h.itemView.setOnClickListener { onClick(r) }
        h.more.setOnClickListener { onMore(r, it) }
    }
}
