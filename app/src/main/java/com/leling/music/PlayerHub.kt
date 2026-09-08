package com.leling.music

import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 全局播放中枢（进程单例）。
 * UI 只与它交互；PlayerService 持有 ExoPlayer 并在此绑定。
 * 播放状态以 StateFlow 暴露；EQ 只在播放中启用（省电），随 settings 实时切换。
 */
object PlayerHub {
    // 播放模式（与设置持久化一致）
    const val MODE_SEQUENTIAL = 0   // 顺序，播完停止
    const val MODE_LIST_LOOP = 1    // 列表循环（自动连播，默认）
    const val MODE_SINGLE = 2       // 单曲循环

    data class UiState(
        val current: Song? = null,
        val isPlaying: Boolean = false,
        val posMs: Long = 0L,
        val durationMs: Long = 0L,
        val queue: List<Song> = emptyList(),
        val repeatMode: Int = MODE_LIST_LOOP,
        val shuffle: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var player: ExoPlayer? = null
    private var service: PlayerService? = null
    private var eq: Equalizer? = null
    private var eqSessionId: Int = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET
    private var library: List<Song> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ticker = Handler(Looper.getMainLooper())
    private var settingsJob: Job? = null

    // ---------------------------------------------------------------- 绑定

    /** 由 PlayerService.onCreate 调用 */
    fun bind(p: ExoPlayer, s: PlayerService, currentLibrary: List<Song> = emptyList()) {
        library = currentLibrary
        player = p
        service = s
        p.addListener(playerListener)
        observeSettings()
        pendingQueue?.let { (q, i, sh) ->
            pendingQueue = null
            playQueue(q, i, sh)
        }
        updateStateFromPlayer()
    }

    fun unbind() {
        player?.removeListener(playerListener)
        settingsJob?.cancel()
        ticker.removeCallbacksAndMessages(null)
        releaseEq()
        player = null
        service = null
    }

    /** 服务创建后立即把持久化设置应用到播放器（循环/随机/EQ） */
    fun applyStoredSettingsToPlayer() {
        scope.launch {
            val s = SettingsRepo.get(AppCtx.app).snapshot()
            player?.apply {
                repeatMode = when (s.repeatMode) {
                    MODE_SINGLE -> Player.REPEAT_MODE_ONE
                    MODE_LIST_LOOP -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                shuffleModeEnabled = s.shuffle
            }
            _ui.update { it.copy(repeatMode = s.repeatMode, shuffle = s.shuffle) }
            refreshEqIfNeeded()
        }
    }

    private var pendingQueue: Triple<List<Song>, Int, Boolean>? = null
    private var bindingRequested = false

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {}
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            bindingRequested = false
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateStateFromPlayer()
            refreshEqIfNeeded()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else stopTicker()
            refreshEqIfNeeded()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _ui.update { it.copy(posMs = 0) }
                // 顺序模式播完自然停在结尾；其余模式由 ExoPlayer 自动续播
            }
            updateStateFromPlayer()
        }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) { updateStateFromPlayer() }
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            eqSessionId = audioSessionId
            refreshEqIfNeeded()
        }
    }

    private fun updateStateFromPlayer() {
        val p = player ?: return
        val queue = _ui.value.queue
        val idx = p.currentMediaItemIndex
        _ui.update {
            it.copy(
                current = queue.getOrNull(idx),
                isPlaying = p.isPlaying,
                posMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d > 0 } ?: it.durationMs,
            )
        }
    }

    // ---------------------------------------------------------------- 设置联动

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            SettingsRepo.get(AppCtx.app).settings.collect { s ->
                player?.apply {
                    repeatMode = when (s.repeatMode) {
                        MODE_SINGLE -> Player.REPEAT_MODE_ONE
                        MODE_LIST_LOOP -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                    shuffleModeEnabled = s.shuffle
                }
                _ui.update { it.copy(repeatMode = s.repeatMode, shuffle = s.shuffle) }
                refreshEqIfNeeded()
            }
        }
    }

    // ---------------------------------------------------------------- EQ（仅播放时启用，省电）

    private fun refreshEqIfNeeded() = scope.launch {
        val s = SettingsRepo.get(AppCtx.app).snapshot()
        val p = player ?: return@launch
        val playing = p.isPlaying && p.playbackState != Player.STATE_IDLE
        val sessionId = eqSessionId
        if (!s.eqEnabled || !playing || sessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
            releaseEq()
            return@launch
        }
        if (eq == null) {
            eq = try {
                Equalizer(0, sessionId)
            } catch (_: Throwable) { null }
        }
        eq?.let { EqPresets.apply(it, s.eqPreset) }
    }

    private fun releaseEq() {
        try { eq?.enabled = false } catch (_: Throwable) {}
        try { eq?.release() } catch (_: Throwable) {}
        eq = null
    }

    // ---------------------------------------------------------------- 播放操作

    /**
     * 启动播放服务。
     * 用 bindService 拉起（避免 startForegroundService 5 秒内必须 startForeground 的
     * 限制）；Media3 在真正开始播放时会自动把服务转为前台并发布媒体通知。
     */
    private fun ensureService(ctx: Context) {
        if (service != null || player != null || bindingRequested) return
        bindingRequested = true
        try {
            ctx.bindService(
                Intent(ctx, PlayerService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (_: Throwable) {
            bindingRequested = false
        }
    }

    fun playQueue(songs: List<Song>, index: Int = 0, shuffle: Boolean = false, ctx: Context = AppCtx.app) {
        if (songs.isEmpty()) return
        ensureService(ctx)
        val p = player
        if (p == null) {
            pendingQueue = Triple(songs, index.coerceIn(0, songs.size - 1), shuffle)
            return
        }
        val items = songs.map { toMediaItem(it) }
        p.setMediaItems(items, index.coerceIn(0, songs.size - 1), 0)
        if (shuffle) p.shuffleModeEnabled = true
        p.prepare()
        p.play()
        _ui.update { it.copy(queue = songs, current = songs.getOrNull(index.coerceIn(0, songs.size - 1))) }
    }

    fun playSongAt(index: Int) {
        val p = player ?: return
        if (p.currentMediaItemIndex == index && p.playbackState != Player.STATE_ENDED) { toggle(); return }
        p.seekTo(index, 0)
        p.prepare()
        p.play()
    }

    fun toggle() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next() {
        val p = player ?: return
        p.seekToNext()
        if (!p.isPlaying) p.play()
    }
    fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3000) {
            p.seekTo(0)
        } else {
            p.seekToPrevious()
            if (!p.isPlaying) p.play()
        }
    }
    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        _ui.update { it.copy(posMs = ms) }
    }

    /** 播放模式：0 顺序 1 列表循环 2 单曲循环（持久化+即时生效） */
    fun setRepeatMode(mode: Int) {
        player?.let { p ->
            p.repeatMode = when (mode) {
                MODE_SINGLE -> Player.REPEAT_MODE_ONE
                MODE_LIST_LOOP -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
        }
        _ui.update { it.copy(repeatMode = mode) }
        scope.launch { SettingsRepo.get(AppCtx.app).setRepeatMode(mode) }
    }

    fun setShuffle(on: Boolean) {
        player?.shuffleModeEnabled = on
        _ui.update { it.copy(shuffle = on) }
        scope.launch { SettingsRepo.get(AppCtx.app).setShuffle(on) }
    }

    /** 播放任意 uri（语音“播放这个”兜底）：库内则入队播放，库外单曲播放 */
    fun playUri(uri: android.net.Uri, ctx: Context = AppCtx.app) {
        val idx = library.indexOfFirst { it.uri == uri }
        if (idx >= 0) {
            playQueue(library, idx, false, ctx)
            return
        }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "未知曲目" } ?: "未知曲目"
        val fake = Song(-1, name, "未知歌手", "未知专辑", 0, 0, 0, "", uri)
        val q = library + fake
        playQueue(q, library.size, false, ctx)
    }

    /** 语音/文字搜索播放：query 含"随机"或匹配全部时随机播放 */
    fun playSearch(query: String, ctx: Context = AppCtx.app) {
        val q = query.trim().lowercase()
        val hits = library.filter {
            q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
        if (hits.isEmpty()) return
        val random = q.isEmpty() || q.contains("随机") || hits.size >= library.size
        val start = if (random) (0 until hits.size).random() else 0
        playQueue(hits, start, random, ctx)
    }

    /** 在 settings 里把当前播放列表设为新的过滤结果后同步队列 */
    fun replaceQueue(songs: List<Song>, keepCurrent: Boolean = true) {
        val p = player ?: return
        val curId = p.currentMediaItem?.mediaId
        val idx = if (keepCurrent) songs.indexOfFirst { "audio:${it.id}" == curId }.coerceAtLeast(0) else 0
        playQueue(songs, if (curId != null) idx else 0, p.shuffleModeEnabled)
    }

    fun refreshLibrary(songs: List<Song>) {
        library = songs
    }

    fun releaseAll() {
        unbind()
        scope.cancel()
    }

    private fun toMediaItem(s: Song): MediaItem =
        MediaItem.Builder()
            .setMediaId("audio:${s.id}")
            .setUri(s.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist(s.artist)
                    .setAlbumTitle(s.album)
                    .setArtworkUri(albumArtUri(s.albumId))
                    .build()
            )
            .build()

    fun albumArtUri(albumId: Long): Uri =
        Uri.parse("content://media/external/audio/albumart/$albumId")

    private fun startTicker() {
        ticker.removeCallbacksAndMessages(null)
        ticker.post(object : Runnable {
            override fun run() {
                val p = player ?: return
                if (p.isPlaying) {
                    _ui.update { it.copy(posMs = p.currentPosition.coerceAtLeast(0)) }
                    ticker.postDelayed(this, 1000)
                }
            }
        })
    }

    private fun stopTicker() {
        ticker.removeCallbacksAndMessages(null)
        _ui.update { it.copy(posMs = player?.currentPosition ?: 0) }
    }
}
