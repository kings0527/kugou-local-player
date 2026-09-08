package com.kugou.android.thirdapi

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.kugou.android.PlayerHub
import com.kugou.android.thirdmap.IKGMapApi
import com.kugou.android.thirdmap.IKGMapApiCallback
import com.kugou.android.thirdmap.IKGMapApiEventListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 酷狗「音乐统一服务」—— OPPO 小布(Breeno) 新版语音控制通路。
 *
 * 触发条件：小布的 AIChatMusicController.w(pkg) 中，仅当
 *   包名 == com.kugou.android 且 versionCode >= 20169（KuGouHelper.b）
 * 才返回 "newkugoumusic"，否则返回 null → 提示「该应用暂不支持此操作」。
 *
 * 协议（逆向自 com.heytap.speechassist 的 com.kugou.kgmusicaidlcop.KGEngine）：
 *   bind action = com.kugou.android.thirdmap.KGMusicUnityService (pkg com.kugou.android)
 *   intent extra: sdk_channel = 调用方包名
 *   AIDL = com.kugou.android.thirdmap.IKGMapApi
 *   授权：v2_check_authority {openAppId, secretKey, packageName}
 *   查询：v1_getCurrentSong / v1_query_play_mode / v1_getCurrTime / v1_getTotalTime /
 *         v1_get_curr_real_playing_data / v1_is_queue_reloaded / v1_action_play_index
 *   其它：v1_refresh_token / v1_collect_song / v1_query_vip_services / v1_get_vip_services
 *   事件：API_EVENT_PLAY_SERVICE_INITIALIZED / PLAY_STATE_CHANGED{playState} /
 *         PLAY_SONG_CHANGED / PLAY_LIST_CHANGED
 */
class KGMusicUnityService : Service() {

    companion object {
        private const val TAG = "KGMusicUnity"
        const val ACTION = "com.kugou.android.thirdmap.KGMusicUnityService"

        const val EV_SERVICE_INIT = "API_EVENT_PLAY_SERVICE_INITIALIZED"
        const val EV_STATE = "API_EVENT_PLAY_STATE_CHANGED"
        const val EV_SONG = "API_EVENT_PLAY_SONG_CHANGED"
        const val EV_LIST = "API_EVENT_PLAY_LIST_CHANGED"

        // 小布判定播放中的状态码
        const val STATE_PLAYING = 4
        const val STATE_PAUSED = 1
        const val STATE_STOPPED = 0

        // 小布使用的 openAppId / secretKey（见 AIChatNewKuGouPlayer 静态字段）
        const val OPEN_APP_ID = "10272"
        const val SECRET_KEY = "uvrMziPtvkZueI1F6smPOa9HWxYQNwHG"
    }

    private val listeners = CopyOnWriteArrayList<IKGMapApiEventListener>()
    private var lastState = -1
    private var lastSongId = Long.MIN_VALUE

    private val binder = object : IKGMapApi.Stub() {

        override fun execute(cmd: String?, params: Bundle?): Bundle {
            Log.i(TAG, "execute cmd=$cmd params=${dump(params)}")
            return run(cmd, params)
        }

        override fun executeAsync(cmd: String?, params: Bundle?, callback: IKGMapApiCallback?) {
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

        override fun registerEventListener(events: MutableList<String>?, listener: IKGMapApiEventListener?): Bundle {
            Log.i(TAG, "registerEventListener events=$events")
            listener?.let { if (!listeners.contains(it)) listeners.add(it) }
            listener?.let {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { it.onEvent(EV_SERVICE_INIT, Bundle()) }
                        .onFailure { e -> Log.w(TAG, "post SERVICE_INIT failed", e) }
                }, 200)
            }
            return ok()
        }

        override fun unregisterEventListener(events: MutableList<String>?, listener: IKGMapApiEventListener?): Bundle {
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
        Log.i(TAG, "onBind action=${intent?.action} channel=${intent?.getStringExtra("sdk_channel")}")
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

    /** ExoPlayer 必须在主线程访问；AIDL 回调在 Binder 线程，故统一切主线程 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun run(cmd: String?, params: Bundle?): Bundle {
        Log.i(TAG, "run cmd=$cmd")
        return when (cmd) {
            // 授权校验：小布 init 时调用，code=0 表示授权通过
            "v2_check_authority" -> {
                Log.i(TAG, "authority openAppId=${params?.getString("openAppId")} pkg=${params?.getString("packageName")}")
                ok()
            }
            "token_refresh", "v1_refresh_token" -> ok()

            // 播放状态：playState 4=播放中
            "v1_getCurrentSong", "v1_get_curr_real_playing_data" -> Bundle().apply {
                putInt("code", 0)
                putString("data", currentSongJson())
                putString("current_song_name", PlayerHub.ui.value.current?.title ?: "")
                putLong("current_hash", PlayerHub.ui.value.current?.id ?: 0L)
            }
            "v1_query_play_mode" -> Bundle().apply {
                putInt("code", 0)
                putInt("playMode", PlayerHub.ui.value.repeatMode)
            }
            "v1_getCurrTime" -> Bundle().apply {
                putInt("code", 0)
                putLong("currentTime", PlayerHub.ui.value.posMs / 1000)
            }
            "v1_getTotalTime" -> Bundle().apply {
                putInt("code", 0)
                putLong("totalTime", (PlayerHub.ui.value.durationMs / 1000))
            }
            "v1_is_queue_reloaded" -> Bundle().apply {
                putInt("code", 0)
                putBoolean("data", PlayerHub.ui.value.queue.isNotEmpty())
            }
            // 按队列下标播放
            "v1_action_play_index" -> {
                val idx = params?.getInt("pagePosition", params.getInt("index", -1)) ?: -1
                onMain { if (idx >= 0) PlayerHub.playSongAt(idx) else PlayerHub.voicePlay() }
                ok()
            }
            "v1_collect_song" -> ok()
            "v1_query_vip_services", "v1_get_vip_services" -> Bundle().apply {
                putInt("code", 0)
                putString("data", "[]")
            }
            "v2_cur_page_high_limit" -> Bundle().apply {
                putInt("code", 0)
                putInt("data", 0)
            }
            // 兼容旧命令名（小布可能混用）
            "playMusic", "resumeMusic", "rePlayMusic" -> { PlayerHub.voicePlay(); ok() }
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
            "kgSemanticSlots" -> {
                // 小布把真实意图放在 semanticslots JSON 里：
                //   控制类：{"intent":"next"} / {"intent":"pause"} / {"intent":"play"}（query=null）
                //   搜索类：{"intent":"play","query":"播放周杰伦的惊叹号",
                //           "slots":[{"name":"singer","values":[{"text":"周杰伦"}]},
                //                     {"name":"song","values":[{"text":"惊叹号"}]}]}
                val slots = params?.getString("semanticslots")
                val topQuery = params?.getString("query")
                val json = runCatching { JSONObject(slots ?: "{}") }.getOrDefault(JSONObject())
                val intent = json.optString("intent")
                val query = topQuery?.takeIf { it.isNotBlank() }
                    ?: json.optString("query").takeIf { it.isNotBlank() }
                // 解析结构化 slots（singer / song）
                var singer: String? = null
                var song: String? = null
                val slotsArr = json.optJSONArray("slots")
                if (slotsArr != null) {
                    for (i in 0 until slotsArr.length()) {
                        val slot = slotsArr.optJSONObject(i) ?: continue
                        val name = slot.optString("name")
                        val text = slot.optJSONArray("values")?.optJSONObject(0)?.optString("text")
                            ?.takeIf { it.isNotBlank() } ?: continue
                        when {
                            name.contains("singer") || name.contains("artist") -> singer = text
                            name.contains("song") || name.contains("title") -> song = text
                        }
                    }
                }
                Log.i(TAG, "kgSemanticSlots intent=$intent query=$query singer=$singer song=$song")

                val hasSearch = !query.isNullOrBlank() || !singer.isNullOrBlank() || !song.isNullOrBlank()
                when {
                    // 搜索优先级最高（有具体歌名/歌手时不播全部）
                    hasSearch -> PlayerHub.voiceSearchAndPlay(query ?: "", singer, song)
                    intent.contains("next") -> onMain { PlayerHub.next() }
                    intent.contains("prev") -> onMain { PlayerHub.previous() }
                    intent.contains("pause") || intent.contains("stop") -> PlayerHub.voicePause()
                    intent.contains("mode") -> {
                        val mode = json.optJSONObject("args")?.optInt("playMode", 0) ?: 0
                        PlayerHub.voiceSetPlayMode(mode)
                    }
                    intent.contains("collect") || intent.contains("favorite") -> Unit
                    intent.contains("play") || intent.contains("resume") -> PlayerHub.voicePlay()
                    else -> PlayerHub.voicePlay()
                }
                ok()
            }
            "verify", "hi" -> ok()
            else -> {
                Log.w(TAG, "unknown cmd=$cmd")
                Bundle().apply { putInt("code", -1); putString("msg", "unsupported: $cmd") }
            }
        }
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
        Log.i(TAG, "dispatch $event")
        for (l in listeners) {
            try {
                l.onEvent(event, data)
            } catch (t: Throwable) {
                Log.w(TAG, "dispatch failed", t)
                listeners.remove(l)
            }
        }
    }

    private fun ok() = Bundle().apply { putInt("code", 0) }

    private fun dump(b: Bundle?): String = b?.let { bb ->
        bb.keySet().joinToString(", ") { k -> "$k=${runCatching { bb.get(k) }.getOrNull()}" }
    } ?: "null"
}
