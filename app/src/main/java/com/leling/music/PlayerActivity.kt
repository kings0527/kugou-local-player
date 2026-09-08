package com.leling.music

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.leling.music.databinding.ActivityPlayerBinding
import com.leling.music.util.artLetter
import com.leling.music.util.fmtTime
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        b.pvBack.setOnClickListener { finish() }

        b.pvPlay.setOnClickListener { PlayerHub.toggle() }
        b.pvPrev.setOnClickListener { PlayerHub.previous() }
        b.pvNext.setOnClickListener { PlayerHub.next() }

        b.pvShuffle.setOnClickListener { PlayerHub.setShuffle(!PlayerHub.ui.value.shuffle) }
        b.pvRepeat.setOnClickListener {
            val cur = PlayerHub.ui.value.repeatMode
            PlayerHub.setRepeatMode(
                when (cur) {
                    PlayerHub.MODE_SEQUENTIAL -> PlayerHub.MODE_LIST_LOOP
                    PlayerHub.MODE_LIST_LOOP -> PlayerHub.MODE_SINGLE
                    else -> PlayerHub.MODE_SEQUENTIAL
                }
            )
        }

        b.pvSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = PlayerHub.ui.value.durationMs
                    b.pvCur.text = fmtTime(dur * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) { dragging = true }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {
                dragging = false
                PlayerHub.seekTo(PlayerHub.ui.value.durationMs * seekBar.progress / 1000)
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlayerHub.ui.collect { st -> render(st) }
            }
        }
    }

    private fun render(st: PlayerHub.UiState) {
        val song = st.current
        if (song == null) {
            b.pvArt.text = "♪"
            b.pvTitle.text = getString(R.string.player_placeholder_title)
            b.pvArtist.text = getString(R.string.player_placeholder_sub)
            b.pvSeek.progress = 0
            b.pvCur.text = "0:00"
            b.pvTotal.text = "0:00"
            b.pvPlay.setImageResource(R.drawable.ic_play)
            return
        }
        b.pvArt.text = artLetter(song.title)
        b.pvTitle.text = song.title
        b.pvArtist.text = if (song.artist.isBlank()) "未知歌手" else song.artist

        // 进度
        val dur = if (st.durationMs > 0) st.durationMs else 1
        if (!dragging) {
            b.pvSeek.progress = ((st.posMs * 1000) / dur).toInt().coerceIn(0, 1000)
            b.pvCur.text = fmtTime(st.posMs)
        }
        b.pvTotal.text = fmtTime(dur)

        // 播放键
        b.pvPlay.setImageResource(if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        // 随机
        b.pvShuffle.alpha = if (st.shuffle) 1f else 0.35f

        // 播放模式图标：0 顺序(暗) 1 列表循环 2 单曲循环
        when (st.repeatMode) {
            PlayerHub.MODE_SINGLE -> {
                b.pvRepeat.setImageResource(R.drawable.ic_repeat_one)
                b.pvRepeat.alpha = 1f
            }
            PlayerHub.MODE_LIST_LOOP -> {
                b.pvRepeat.setImageResource(R.drawable.ic_repeat)
                b.pvRepeat.alpha = 1f
            }
            else -> {
                b.pvRepeat.setImageResource(R.drawable.ic_repeat)
                b.pvRepeat.alpha = 0.35f
            }
        }
    }

    /** 播放页深色背景，状态栏/导航栏同色，浅色图标 */
    override fun onResume() {
        super.onResume()
        val c = androidx.core.content.ContextCompat.getColor(this, R.color.brand_dark)
        window.statusBarColor = c
        if (android.os.Build.VERSION.SDK_INT >= 26) window.navigationBarColor = c
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }
}
