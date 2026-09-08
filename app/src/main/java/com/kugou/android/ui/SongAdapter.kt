package com.kugou.android.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kugou.android.R
import com.kugou.android.Song
import com.kugou.android.databinding.ItemSongBinding
import com.kugou.android.util.CoverCache
import com.kugou.android.util.artLetter
import com.kugou.android.util.fmtTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
        set(value) {
            val old = field
            field = value
            // 只刷新受影响的旧/新位置，避免全量 notifyDataSetChanged 造成闪烁
            if (old != value && old != null && value != null) {
                songs.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
                songs.indexOfFirst { it.id == value }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            }
        }
    var isPlaying: Boolean = false
        set(value) {
            val old = field
            field = value
            if (old != value) {
                playingId?.let { pid ->
                    songs.indexOfFirst { it.id == pid }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
                }
            }
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        b.songTitle.text = s.title
        b.songSub.text = formatSongSub(s)
        b.songDur.text = fmtTime(s.durationMs)
        b.songPlaying.visibility = if (playing && isPlaying) View.VISIBLE else View.GONE
        b.songTitle.setTextColor(if (playing) primaryColor(holder.itemView) else defaultTextColor(holder.itemView))

        // 封面：命中缓存则同步显示，未命中先字母后异步加载（不闪烁优先）
        val albumId = s.albumId
        val ctx = holder.itemView.context
        val cached = CoverCache.peek(albumId)
        if (cached != null) {
            b.songArtImg.setImageBitmap(cached)
            b.songArtImg.visibility = View.VISIBLE
            b.songArt.visibility = View.GONE
        } else {
            b.songArtImg.visibility = View.GONE
            b.songArt.text = artLetter(s.title)
            b.songArt.visibility = View.VISIBLE
            scope.launch {
                val bmp = CoverCache.load(ctx, albumId)
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    b.songArtImg.setImageBitmap(bmp)
                    b.songArtImg.visibility = View.VISIBLE
                    b.songArt.visibility = View.GONE
                }
            }
        }
    }

    private fun primaryColor(v: View): Int {
        val ta = v.context.theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorPrimary))
        return try { ta.getColor(0, 0xFF2F6BFF.toInt()) } finally { ta.recycle() }
    }

    private fun defaultTextColor(v: View): Int {
        val ta = v.context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try { ta.getColor(0, 0xFF000000.toInt()) } finally { ta.recycle() }
    }

    fun release() {
        scope.cancel()
    }
}
