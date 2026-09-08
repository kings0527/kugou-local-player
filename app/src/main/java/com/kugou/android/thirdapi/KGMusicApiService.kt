package com.kugou.android.thirdapi

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.kugou.android.PlayerHub
import com.kugou.android.third.api.IKGMusicApi
import com.kugou.android.third.api.IKGMusicApiCallback
import com.kugou.android.third.api.IKGMusicApiEventListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 酷狗音乐「第三方控制」服务 —— 让 OPPO 小布(Breeno) 能语音控制本应用。
 *
 * 为什么用酷狗协议：小布内置 4 条音乐通路（网易云/OPPO音乐/酷狗/QQ音乐），
 * 其中网易云与 OPPO 音乐都需要服务端 token 签名（无法本地伪造），
 * 而酷狗通路 execute/executeAsync 无 token 校验，是唯一可本地完整实现的路径。
 *
 * 协议逆向自 com.heytap.speechassist 12.9.9 的 KuGouAIDLPlayer：
 *   bind action = com.kugou.android.third.api.KGMusicApiService (pkg com.kugou.android)
 *   命令: playMusic / pauseMusic / resumeMusic / next / previous /
 *         getCurrentPlayState(→Bundle.data) / getCurrentSong(→Bundle.data JSON) /
 *         changePlayMode / kgSemanticSlots(语音搜索)
 *   事件: API_EVENT_PLAY_SERVICE_INITIALIZED / API_EVENT_PLAY_STATE_CHANGED{playState} /
 *         API_EVENT_PLAY_SONG_CHANGED{data}
 *   状态码: 4 / 16 = 播放中
 */
class KGMusicApiService : Service() {

    companion object {
        private const val TAG = "KGMusicApi"
        const val ACTION = "com.kugou.android.third.api.KGMusicApiService"

        // 事件名（与小布 Events 常量一致）
        const val EV_SERVICE_INIT = "API_EVENT_PLAY_SERVICE_INITIALIZED"
        const val EV_STATE = "API_EVENT_PLAY_STATE_CHANGED"
        const val EV_SONG = "API_EVENT_PLAY_SONG_CHANGED"
        const val EV_LIST = "API_EVENT_PLAY_LIST_CHANGED"

        // 播放状态码（小布判定 4/16 为播放中）
        const val STATE_PLAYING = 4
        const val STATE_PAUSED = 1
        const val STATE_STOPPED = 0
    }

    private val listeners = CopyOnWriteArrayList<IKGMusicApiEventListener>()
    private var lastState = -1
    private var lastSongId = Long.MIN_VALUE

    /** ExoPlayer 必须在主线程访问；AIDL 回调在 Binder 线程 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private val binder = object : IKGMusicApi.Stub() {

        override fun execute(cmd: String?, params: Bundle?): Bundle {
            Log.i(TAG, "execute cmd=$cmd params=${dump(params)}")
            return run(cmd, params)
        }

        override fun executeAsync(cmd: String?, params: Bundle?, callback: IKGMusicApiCallback?) {
            Log.i(TAG, "executeAsync cmd=$cmd params=${dump(params)}")
            Thread {
                val r = run(cmd, params)
                try {
                    callback?.onResult(r)
                } catch (t: Throwable) {
                    Log.w(TAG, "callback failed", t)
                }
            }.start()
        }

        override fun registerEventListener(events: MutableList<String>?, listener: IKGMusicApiEventListener?): Bundle {
            Log.i(TAG, "registerEventListener events=$events")
            listener?.let { if (!listeners.contains(it)) listeners.add(it) }
            // 延迟发送“服务已就绪”：小布需先完成 register 回执，过早回调会丢事件
            listener?.let {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { it.onEvent(EV_SERVICE_INIT, Bundle()) }
                        .onFailure { e -> Log.w(TAG, "post SERVICE_INIT failed", e) }
                }, 200)
            }
            return ok()
        }

        override fun unregisterEventListener(events: MutableList<String>?, listener: IKGMusicApiEventListener?): Bundle {
            listener?.let { listeners.remove(it) }
            Log.i(TAG, "unregisterEventListener remain=${listeners.size}")
            return ok()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        PlayerHub.attachVoiceService(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind action=${intent?.action}")
        PlayerHub.attachVoiceService(this)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        listeners.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        PlayerHub.attachVoiceService(null)
        listeners.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 命令分发

    private fun run(cmd: String?, params: Bundle?): Bundle = when (cmd) {
        // ★ 握手验证：小布 onServiceConnected 后立即调用 verify，code=0 才认为酷狗可用
        //   参数：appid=3098, ver=versionCode, timestamp, sign=MD5("3098ik8UPL3orTXg7SzTgwUhDhxLrxBcNxrF"+ver+ts), client
        "verify" -> {
            Log.i(TAG, "verify params=${dump(params)}")
            ok()
        }

        // 播放：无队列时自动扫描媒体库并播放全部（小布"播放音乐"）
        "playMusic" -> { PlayerHub.voicePlay(); ok() }
        "resumeMusic" -> { PlayerHub.voiceResume(); ok() }
        "rePlayMusic" -> { PlayerHub.voicePlay(); ok() }
        "pauseMusic", "stopMusic" -> { PlayerHub.voicePause(); ok() }
        "skipToNext", "next" -> { onMain { PlayerHub.next() }; ok() }
        "skipToPrevious", "previous" -> { onMain { PlayerHub.previous() }; ok() }

        "getCurrentPlayState", "getPlaybackState" -> Bundle().apply {
            putInt("code", 0)
            putInt("data", currentState())
        }

        "getCurrentSong" -> Bundle().apply {
            putInt("code", 0)
            putString("data", currentSongJson())
        }

        "changePlayMode", "setPlayMode" -> {
            PlayerHub.voiceSetPlayMode(params?.getInt("playMode", 0) ?: 0)
            ok()
        }

        // 语音搜索播放（"播放周杰伦的歌"）：解析 query 后按标题/歌手匹配
        "kgSemanticSlots" -> {
            val slots = params?.getString("semanticslots")
            val intent = runCatching { JSONObject(slots ?: "{}").optString("intent") }.getOrDefault("")
            val rawQuery = params?.getString("query") ?: runCatching {
                JSONObject(slots ?: "{}").optString("query")
            }.getOrDefault("")
            // Android org.json 对 JSON null 返回 "null" 字符串，需过滤
            val query = rawQuery.takeIf { it.isNotBlank() && it != "null" } ?: ""
            Log.i(TAG, "kgSemanticSlots intent=$intent query=$query")
            when {
                intent.contains("next") -> onMain { PlayerHub.next() }
                intent.contains("prev") -> onMain { PlayerHub.previous() }
                intent.contains("pause") || intent.contains("stop") -> PlayerHub.voicePause()
                intent.contains("play") || intent.contains("resume") -> PlayerHub.voicePlay()
                intent.contains("mode") -> PlayerHub.voiceSetPlayMode(
                    runCatching { JSONObject(slots ?: "{}").optJSONObject("args")?.optInt("playMode", 0) ?: 0 }.getOrDefault(0)
                )
                query.isNotBlank() -> PlayerHub.voiceSearchAndPlay(query)
                else -> PlayerHub.voicePlay()
            }
            ok()
        }

        "hi" -> ok()

        else -> {
            Log.w(TAG, "unknown cmd=$cmd")
            Bundle().apply { putInt("code", -1); putString("msg", "unsupported cmd: $cmd") }
        }
    }

    private fun extractQuery(b: Bundle): String {
        // 小布可能传 JSON 字符串（含 query / slots）或直接键值
        val raw = b.getString("data") ?: b.getString("query") ?: ""
        if (raw.isBlank()) return ""
        return runCatching {
            val j = JSONObject(raw)
            j.optString("query").ifBlank {
                j.optString("semanticslots").ifBlank { j.toString() }
            }
        }.getOrDefault(raw)
    }

    private fun currentState(): Int {
        val st = PlayerHub.ui.value
        return when {
            st.current == null -> STATE_STOPPED
            st.isPlaying -> STATE_PLAYING
            else -> STATE_PAUSED
        }
    }

    private fun currentSongJson(): String {
        val s = PlayerHub.ui.value.current ?: return "{}"
        return JSONObject().apply {
            put("songName", s.title)
            put("singerName", s.artist)
            put("albumName", s.album)
            put("duration", s.durationMs)
            put("songId", s.id)
        }.toString()
    }

    // ------------------------------------------------------------------ 事件上报

    /** 由 PlayerHub 在播放状态/歌曲变化时调用 */
    fun postState() {
        val st = PlayerHub.ui.value
        val state = currentState()
        if (state != lastState) {
            lastState = state
            dispatch(EV_STATE, Bundle().apply { putInt("playState", state) })
        }
        val song = st.current
        if (song != null && song.id != lastSongId) {
            lastSongId = song.id
            dispatch(EV_SONG, Bundle().apply {
                putString("data", currentSongJson())
                putString("songName", song.title)
                putString("singerName", song.artist)
            })
        }
    }

    private fun dispatch(event: String, data: Bundle) {
        if (listeners.isEmpty()) return
        Log.i(TAG, "dispatch $event ${dump(data)}")
        for (l in listeners) {
            try {
                l.onEvent(event, data)
            } catch (t: Throwable) {
                Log.w(TAG, "dispatch failed", t)
                listeners.remove(l)
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun ok() = Bundle().apply { putInt("code", 0) }

    private fun dump(b: Bundle?): String = b?.let { bb ->
        bb.keySet().joinToString(", ") { k -> "$k=${runCatching { bb.get(k) }.getOrNull()}" }
    } ?: "null"
}
