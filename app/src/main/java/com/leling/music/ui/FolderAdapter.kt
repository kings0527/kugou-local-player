package com.leling.music.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.leling.music.DirNode
import com.leling.music.databinding.ItemFolderBinding

class FolderAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    var dirs: List<DirNode> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 当前高亮的目录集合（只听模式下的勾选） */
    var checked: Set<String> = emptySet()

    inner class VH(val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.root.setOnClickListener { (it.tag as? Int)?.let(onClick) }
        return VH(b)
    }

    override fun getItemCount() = dirs.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = dirs[position]
        val b = holder.b
        holder.b.root.tag = position
        b.folderName.text = d.name
        b.folderPath.text = d.dir
        b.folderCount.text = holder.itemView.context.getString(
            com.leling.music.R.string.folder_songs_count, d.total
        )
        val isChecked = checked.any { rule -> d.dir == rule || d.dir.startsWith("$rule/") }
        b.folderModeBadge.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
    }
}
