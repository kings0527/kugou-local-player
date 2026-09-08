package com.kugou.android

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kugou.android.databinding.ActivityPlayerBinding
import com.kugou.android.util.CoverCache
import com.kugou.android.util.artLetter
import com.kugou.android.util.fmtTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var dragging = false
    private var coverJob: Job? = null
    private var lastAlbumId: Long = -1L
    private lateinit var discAnimator: ObjectAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // 唱片旋转：匀速无限旋转，播放时转、暂停时停
        discAnimator = ObjectAnimator.ofFloat(b.pvDisc, View.ROTATION, 0f, 360f).apply {
            duration = 16000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
            pause()
        }

        // 唱片上下滑动：上滑下一曲，下滑上一曲
        val flingDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (Math.abs(velocityY) > 800 && Math.abs(velocityY) > Math.abs(velocityX)) {
                    if (velocityY < 0) PlayerHub.next() else PlayerHub.previous()
                    return true
                }
                return false
            }
        })
        b.pvDiscWrap.setOnTouchListener { _, event ->
            flingDetector.onTouchEvent(event)
            // 不拦截点击，让子控件可点
            if (event.actionMasked == MotionEvent.ACTION_UP) false else true
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
            b.pvArtLetter.text = "♪"
            b.pvArt.visibility = View.GONE
            b.pvArtLetter.visibility = View.VISIBLE
            b.pvTitle.text = getString(R.string.player_placeholder_title)
            b.pvArtist.text = getString(R.string.player_placeholder_sub)
            b.pvSeek.progress = 0
            b.pvCur.text = "0:00"
            b.pvTotal.text = "0:00"
            b.pvPlay.setImageResource(R.drawable.ic_play)
            discAnimator.pause()
            return
        }

        b.pvTitle.text = song.title
        b.pvArtist.text = if (song.artist.isBlank()) "未知歌手" else song.artist

        // 封面：只在歌曲 id 变化时加载，避免每秒 ticker 重复加载导致闪烁
        if (lastAlbumId != song.albumId) {
            lastAlbumId = song.albumId
            coverJob?.cancel()
            coverJob = lifecycleScope.launch {
                val bmp = CoverCache.load(applicationContext, song.albumId)
                if (bmp != null && PlayerHub.ui.value.current?.albumId == song.albumId) {
                    b.pvArt.setImageBitmap(bmp)
                    b.pvArt.visibility = View.VISIBLE
                    b.pvArtLetter.visibility = View.GONE
                } else {
                    b.pvArtLetter.text = artLetter(song.title)
                    b.pvArt.visibility = View.GONE
                    b.pvArtLetter.visibility = View.VISIBLE
                }
            }
        }

        // 进度
        val dur = if (st.durationMs > 0) st.durationMs else 1
        if (!dragging) {
            b.pvSeek.progress = ((st.posMs * 1000) / dur).toInt().coerceIn(0, 1000)
            b.pvCur.text = fmtTime(st.posMs)
        }
        b.pvTotal.text = fmtTime(dur)

        // 播放键 + 唱片
        if (st.isPlaying) {
            b.pvPlay.setImageResource(R.drawable.ic_pause)
            discAnimator.resume()
        } else {
            b.pvPlay.setImageResource(R.drawable.ic_play)
            discAnimator.pause()
        }

        // 随机
        b.pvShuffle.alpha = if (st.shuffle) 1f else 0.35f

        // 播放模式
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

    override fun onDestroy() {
        coverJob?.cancel()
        discAnimator.cancel()
        super.onDestroy()
    }
}
