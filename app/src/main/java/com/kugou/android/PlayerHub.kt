package com.kugou.android

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
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
        registerFocusWatcher(s)
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
        unregisterFocusWatcher()
        releaseEq()
        player = null
        service = null
    }

    /**
     * 注册“其他应用停止播放后自动恢复”监听。
     * Android 在别的应用释放音频焦点时不会回调我们（我们不是焦点持有者），
     * 所以用 AudioManager 的播放活动回调来感知。
     */
    private fun registerFocusWatcher(ctx: Context) {
        if (focusWatcher != null) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                tryAutoResume()
            }
        }
        am.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper()))
        focusWatcher = cb
    }

    /**
     * 尝试自动恢复。仅在“非用户暂停”且当前无音乐播放时恢复。
     */
    private fun tryAutoResume() {
        val p = player ?: return
        if (userPaused || p.isPlaying || p.mediaItemCount == 0) return
        if (p.playbackState == Player.STATE_IDLE) return
        val am = AppCtx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (am.isMusicActive) return // 仍有其他音乐在放，不抢
        Log.i("PlayerHub", "其他应用已停止，自动恢复播放")
        p.play()
    }

    /** 被外部打断后定时重试恢复（部分场景如 force-stop 不触发 AudioPlaybackCallback） */
    private var resumeRetries = 0
    private val resumeRunnable = object : Runnable {
        override fun run() {
            if (userPaused || resumeRetries >= 10) {
                resumeRetries = 0
                return
            }
            val p = player
            if (p != null && !p.isPlaying && p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE) {
                val am = AppCtx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null && !am.isMusicActive) {
                    Log.i("PlayerHub", "重试恢复播放（第 ${resumeRetries + 1} 次）")
                    p.play()
                    resumeRetries = 0
                    return
                }
                resumeRetries++
                ticker.postDelayed(this, 3000)
            } else {
                resumeRetries = 0
            }
        }
    }

    private fun scheduleAutoResumeRetry() {
        if (userPaused) {
            ticker.removeCallbacks(resumeRunnable)
            resumeRetries = 0
            return
        }
        resumeRetries = 0
        ticker.removeCallbacks(resumeRunnable)
        ticker.postDelayed(resumeRunnable, 2500)
    }

    private fun unregisterFocusWatcher() {
        val cb = focusWatcher ?: return
        val am = AppCtx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching { am?.unregisterAudioPlaybackCallback(cb) }
        focusWatcher = null
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

    /** 连续播放失败计数（用于跳过不可播文件，避免无限循环） */
    private var failStreak = 0
    private const val MAX_FAIL_SKIP = 8

    /**
     * 用户主动暂停标记。
     * 音频焦点丢失（来电/其他 app 播放）导致的暂停不置位，
     * 这样其他 app 停止后可以自动恢复；用户手动暂停则不自动恢复。
     */
    private var userPaused = false
    private var focusWatcher: AudioManager.AudioPlaybackCallback? = null

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
            notifyCMApi()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) failStreak = 0 // 真正开始播放才重置失败计数
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else stopTicker()
            refreshEqIfNeeded()
            notifyCMApi()
            // 被外部（来电/其他 app）打断而暂停时，安排重试恢复
            if (!isPlaying) scheduleAutoResumeRetry()
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

        /**
         * 播放失败（如酷狗加密格式 .kgm/.kgg、文件损坏、格式不支持）→ 自动跳下一首。
         * 用 failStreak 防止整个列表都不可播时无限循环。
         */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.w("PlayerHub", "playback error: ${error.errorCodeName} ${error.message}")
            val p = player ?: return
            if (failStreak >= MAX_FAIL_SKIP) {
                android.util.Log.w("PlayerHub", "连续 $failStreak 首播放失败，暂停并重置")
                failStreak = 0
                p.pause()
                return
            }
            failStreak++
            // 跳到下一首（到尾则回到开头）
            if (p.hasNextMediaItem()) p.seekToNext() else p.seekTo(0, 0L)
            p.prepare()
            p.play()
        }
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
        userPaused = false
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
        if (p.isPlaying) {
            userPaused = true // 用户主动暂停 → 不自动恢复
            ticker.removeCallbacks(resumeRunnable)
            resumeRetries = 0
            p.pause()
        } else {
            userPaused = false
            p.play()
        }
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

    /**
     * 语音/文字搜索播放。泛化指令处理：
     * - 空 / “音乐” / “播放音乐” / “播放本地音乐” → 播放全部（随机起始）
     * - 含“随机” → 随机播放全部
     * - “播放X X” → 按标题/歌手搜索
     */
    fun playSearch(query: String, ctx: Context = AppCtx.app) {
        val q = query.trim().lowercase()
        // 泛化词：不匹配具体歌名，直接播全部
        val generic = q.isEmpty() ||
            q.contains("播放音乐") || q.contains("播放本地音乐") || q == "音乐" ||
            q.contains("随机播放") || q.contains("随机") ||
            q.contains("全部") || q.contains("来点音乐")
        if (generic) {
            val all = library
            if (all.isEmpty()) return
            val random = q.contains("随机") || q.isEmpty()
            playQueue(all, (0 until all.size).random(), random, ctx)
            return
        }
        // 具体搜索：去掉“播放”前缀后匹配标题/歌手
        val needle = q.removePrefix("播放").removePrefix("播放").trim()
        val hits = library.filter {
            needle.isEmpty() ||
                it.title.lowercase().contains(needle) ||
                it.artist.lowercase().contains(needle)
        }
        if (hits.isEmpty()) {
            // 没搜到具体歌曲 → 兜底播全部（避免语音“播放XX”没反应）
            if (library.isNotEmpty()) playQueue(library, 0, false, ctx)
            return
        }
        playQueue(hits, 0, false, ctx)
    }

    /** 播放全部（顺序或随机），供语音“播放音乐/随机播放”使用 */
    fun playAll(random: Boolean, ctx: Context = AppCtx.app) {
        val all = library
        if (all.isEmpty()) return
        val start = if (random) (0 until all.size).random() else 0
        playQueue(all, start, random, ctx)
    }

    // ---------------------------------------------------------------- 语音助手（小布 Breeno）

    /** 主线程 Handler：ExoPlayer 必须在主线程访问，AIDL 回调在 Binder 线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 将语音命令切回主线程执行（ExoPlayer 线程约束） */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** CMApiService 实例，用于上报播放状态事件 */
    @Volatile private var cmService: Any? = null

    /** 绑定语音服务（网易云 CMApiService / 酷狗 KGMusicApiService 二选一或并存） */
    fun attachVoiceService(svc: Any?) { cmService = svc }

    private fun notifyCMApi() {
        when (val s = cmService) {
            is com.kugou.android.thirdapi.CMApiService -> s.postPlayStatus()
            is com.kugou.android.thirdapi.KGMusicApiService -> s.postState()
            is com.kugou.android.thirdapi.KGMusicUnityService -> s.postState()
        }
    }

    /**
     * 语音点歌。优先用小布解析好的结构化 slots：
     *   query="播放周杰伦的惊叹号" singer="周杰伦" song="惊叹号"
     * 匹配优先级：song+singer → song → singer → query 关键词。
     */
    fun voiceSearchAndPlay(
        query: String,
        singer: String? = null,
        song: String? = null,
        ctx: Context = AppCtx.app,
    ) {
        onMain { voiceSearchAndPlayInternal(query, singer, song, ctx) }
    }

    private fun voiceSearchAndPlayInternal(query: String, singer: String?, song: String?, ctx: Context) {
        if (library.isEmpty()) {
            LibraryRepo.rescan(ctx) { voiceSearchAndPlayInternal(query, singer, song, ctx) }
            return
        }
        val s = song?.trim().orEmpty()
        val a = singer?.trim().orEmpty()

        var hits: List<Song> = library
        if (s.isNotEmpty()) hits = hits.filter { it.title.contains(s, ignoreCase = true) }
        if (a.isNotEmpty()) hits = hits.filter { it.artist.contains(a, ignoreCase = true) }
        // 严格匹配无果 → 逐步放宽
        if (hits.isEmpty() && s.isNotEmpty()) {
            hits = library.filter { it.title.contains(s, ignoreCase = true) }
        }
        if (hits.isEmpty() && a.isNotEmpty()) {
            hits = library.filter { it.artist.contains(a, ignoreCase = true) }
        }
        if (hits.isEmpty()) {
            // 回退：从 query 剔除“播放/我想听/的歌/的”等词后做关键词匹配
            val needle = query.trim()
                .removePrefix("播放").removePrefix("我想听").removePrefix("来一首")
                .removeSuffix("的歌").removeSuffix("的歌曲").removeSuffix("的")
                .trim()
            if (needle.isNotEmpty()) {
                hits = library.filter {
                    it.title.contains(needle, ignoreCase = true) ||
                        it.artist.contains(needle, ignoreCase = true)
                }
            }
            // 仍无果：按“的”拆词分别匹配（“周杰伦的惊叹号”）
            if (hits.isEmpty() && needle.contains("的")) {
                val parts = needle.split("的").map { it.trim() }.filter { it.isNotEmpty() }
                hits = library.filter { songItem ->
                    parts.any { p ->
                        songItem.title.contains(p, ignoreCase = true) ||
                            songItem.artist.contains(p, ignoreCase = true)
                    }
                }
            }
        }

        Log.i("VoiceCmd", "search query=$query singer=$a song=$s → hits=${hits.size}")
        if (hits.isNotEmpty()) {
            playQueue(hits, 0, false, ctx)
        } else {
            // 本地真的没有这首歌：播放全部并提示（无法联网下载）
            Log.w("VoiceCmd", "no match for query=$query, fallback playAll")
            voicePlayInternal(ctx)
            return
        }
        notifyCMApi()
    }

    /**
     * 语音“播放/播放音乐”：无队列时自动扫描媒体库并播放全部。
     * 小布可能在应用未启动时调用，因此这里兜底触发扫描。
     */
    fun voicePlay(ctx: Context = AppCtx.app) {
        onMain { voicePlayInternal(ctx) }
    }

    private fun voicePlayInternal(ctx: Context) {
        val p = player
        userPaused = false
        if (p != null && p.mediaItemCount > 0) {
            if (!p.isPlaying) p.play()
            notifyCMApi()
            return
        }
        if (library.isNotEmpty()) {
            playAll(random = false, ctx = ctx)
            notifyCMApi()
            return
        }
        // 媒体库尚未加载：扫描完成后自动播放
        LibraryRepo.rescan(ctx) {
            if (library.isNotEmpty()) playAll(random = false, ctx = ctx)
            notifyCMApi()
        }
    }

    /** 语音“继续播放” */
    fun voiceResume(ctx: Context = AppCtx.app) = voicePlay(ctx)

    /** 语音“暂停/停止” */
    fun voicePause() {
        Log.i("PlayerHub", "voicePause called thread=${Thread.currentThread().name}")
        onMain {
            userPaused = true // 用户语音暂停 → 不自动恢复
            ticker.removeCallbacks(resumeRunnable)
            resumeRetries = 0
            val p = player
            Log.i("PlayerHub", "voicePause exec: player=${p != null} playing=${p?.isPlaying}")
            p?.pause()
            notifyCMApi()
        }
    }

    /** 语音“随机播放”：随机开启并播放全部 */
    fun voiceRandomPlay(ctx: Context = AppCtx.app) {
        onMain {
            setShuffle(true)
            if (library.isEmpty()) {
                LibraryRepo.rescan(ctx) {
                    if (library.isNotEmpty()) playAll(random = true, ctx = ctx)
                    notifyCMApi()
                }
            } else {
                playAll(random = true, ctx = ctx)
                notifyCMApi()
            }
        }
    }

    /**
     * 语音切换播放模式。
     * 兼容两种编号：
     *  - 酷狗 PlayMode 枚举：0=CYCLE(列表循环) 1=SINGLE(单曲) 2=RANDOM(随机)
     *  - 小布旧通路：0=顺序 1=列表循环 2=单曲循环
     */
    fun voiceSetPlayMode(mode: Int) {
        onMain {
            when (mode) {
                1 -> {
                    // 可能是 SINGLE（酷狗）或 列表循环（小布旧）——以单曲为准（酷狗枚举优先）
                    setRepeatMode(MODE_SINGLE)
                    setShuffle(false)
                }
                2 -> {
                    // RANDOM：开启随机 + 列表循环
                    setShuffle(true)
                    setRepeatMode(MODE_LIST_LOOP)
                }
                else -> {
                    setShuffle(false)
                    setRepeatMode(MODE_LIST_LOOP)
                }
            }
            notifyCMApi()
        }
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
