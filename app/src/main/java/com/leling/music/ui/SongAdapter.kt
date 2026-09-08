package com.leling.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.leling.music.Song
import com.leling.music.databinding.ItemSongBinding
import com.leling.music.util.artLetter
import com.leling.music.util.fmtTime

class SongAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<SongAdapter.VH>() {

    var songs: List<Song> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 当前播放的歌曲 id（高亮），-1 表示没有 */
    var playingId: Long? = null
    var isPlaying: Boolean = false

    inner class VH(val b: ItemSongBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.root.setOnClickListener { (it.tag as? Int)?.let(onClick) }
        return VH(b)
    }

    override fun getItemCount() = songs.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = songs[position]
        val b = holder.b
        holder.b.root.tag = position
        val playing = s.id == playingId && s.id >= 0
        b.songArt.text = artLetter(s.title)
        b.songTitle.text = s.title
        b.songSub.text = formatSongSub(s)
        b.songDur.text = fmtTime(s.durationMs)
        b.songPlaying.visibility = if (playing && isPlaying) View.VISIBLE else View.GONE
        b.songTitle.setTextColor(themeColor(holder.itemView, if (playing) primaryAttr else null))
    }

    private val primaryAttr = intArrayOf(com.google.android.material.R.attr.colorPrimary)

    private fun themeColor(view: View, attrs: IntArray?): Int =
        if (attrs == null) {
            val ta = view.context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            try { ta.getColor(0, 0xFF000000.toInt()) } finally { ta.recycle() }
        } else {
            val ta = view.context.theme.obtainStyledAttributes(attrs)
            try { ta.getColor(0, 0xFF6C4DF6.toInt()) } finally { ta.recycle() }
        }
}
