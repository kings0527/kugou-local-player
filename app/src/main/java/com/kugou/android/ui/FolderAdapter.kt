package com.kugou.android.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kugou.android.DirNode
import com.kugou.android.databinding.ItemFolderBinding

class FolderAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    var dirs: List<DirNode> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

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
            com.kugou.android.R.string.folder_songs_count, d.total
        )
    }
}
