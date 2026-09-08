package com.kugou.android.thirdapi

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.kugou.android.PlayerHub
import com.netease.cloudmusic.third.api.contract.ICMAip
import com.netease.cloudmusic.third.api.contract.ICMAipCallback
import com.netease.cloudmusic.third.api.contract.ICMAipEventListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 网易云音乐「第三方音乐控制」服务。
 *
 * 让 OPPO 小布(Breeno) 能用语音控制本应用：
 *   小布 bindService(action=com.netease.cloudmusic.third.api.CMApiService)
 *   → getToken → 下发 CMAPI_PLAY_CONTROLLER(play/pause/next/previous/resume/changeMode)
 *   → 通过 ICMAipEventListener 回传 EVENT_PLAY_STATUS / EVENT_PLAY_INFO
 *
 * 协议细节逆向自 com.heytap.speechassist (NetEasePlayer / CMApiCrypto)。
 */
class CMApiService : Service() {

    companion object {
        private const val TAG = "CMApiService"
        const val ACTION = "com.netease.cloudmusic.third.api.CMApiService"
    }

    private val listeners = CopyOnWriteArrayList<ICMAipEventListener>()
    private var lastStateEvent = -1
    private var lastSongId = Long.MIN_VALUE

    private val binder = object : ICMAip.Stub() {

        override fun execute(method: String?, params: Bundle?): Bundle {
            Log.i(TAG, "execute: $method params=${params?.keySet()?.joinToString()}")
            return when (method) {
                "CMAPI_GET_TOKEN" -> getToken(params)
                "CMAPI_SYNC_GET_INFOS" -> syncGetInfos()
                "CMAPI_PLAY_CONTROLLER" -> playController(params)
                else -> {
                    Log.w(TAG, "execute unknown method=$method")
                    bundleOf(code = 400, msg = "unsupported method: $method")
                }
            }
        }

        override fun executeAsync(
            method: String?,
            callbackId: String?,
            params: Bundle?,
            callback: ICMAipCallback?,
        ) {
            Log.i(TAG, "executeAsync: $method id=$callbackId params=${dumpBundle(params)}")
            Thread {
                val result = when (method) {
                    "CMAPI_PLAY_CONTROLLER" -> playController(params)
                    "CMAPI_GET_TOKEN" -> getToken(params)
                    "CMAPI_SYNC_GET_INFOS" -> syncGetInfos()
                    else -> {
                        Log.w(TAG, "executeAsync unknown method=$method")
                        bundleOf(code = 400, msg = "unsupported method: $method")
                    }
                }
                Log.i(TAG, "executeAsync -> $method result=${dumpBundle(result)}")
                try {
                    callback?.onReturn(result)
                } catch (t: Throwable) {
                    Log.w(TAG, "callback failed", t)
                }
            }.start()
        }

        override fun registerEventListener(listener: ICMAipEventListener?): Bundle {
            listener?.let {
                if (!listeners.contains(it)) listeners.add(it)
                Log.i(TAG, "registerEventListener events=${runCatching { it.events() }.getOrNull()}")
            }
            return bundleOf(code = 200, msg = "success")
        }

        override fun unregisterEventListener(listener: ICMAipEventListener?): Bundle {
            listener?.let { listeners.remove(it) }
            Log.i(TAG, "unregisterEventListener remain=${listeners.size}")
            return bundleOf(code = 200, msg = "success")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind action=${intent?.action}")
        PlayerHub.attachVoiceService(this)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        PlayerHub.attachVoiceService(this)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        PlayerHub.attachVoiceService(null)
        listeners.clear()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        listeners.clear()
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------ 命令实现

    /** 小布请求 token；返回加密的 {sign, ts, token, expireTime} */
    private fun getToken(params: Bundle?): Bundle {
        val encRequest = runCatching {
            JSONObject(params?.getString("data") ?: "{}").optString("encRequest")
        }.getOrNull()
        if (!encRequest.isNullOrEmpty()) {
            // 可选：解密请求以校验（失败不影响响应）
            runCatching { Log.i(TAG, "encRequest decrypt=${CMApiCrypto.decryptPrivate(encRequest)}") }
                .onFailure { Log.w(TAG, "encRequest decrypt failed: ${it.message}") }
        }

        val ts = System.currentTimeMillis().toString()
        val sign = CMApiCrypto.sign(ts)
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        val expireTime = (System.currentTimeMillis() + 24 * 3600_000L).toString()
        val json = JSONObject()
            .put("sign", sign)
            .put("ts", ts)
            .put("token", token)
            .put("expireTime", expireTime)
            .toString()
        val encResult = runCatching { CMApiCrypto.encryptPublic(json) }.getOrNull()
        if (encResult == null) {
            Log.e(TAG, "encrypt token failed")
            return bundleOf(code = 500, msg = "encrypt failed")
        }
        return bundleOf(code = 200, msg = "success").apply { putString("encResult", encResult) }
    }

    /** 同步查询播放状态：status 1=播放中 0=未播放 */
    private fun syncGetInfos(): Bundle {
        val playing = PlayerHub.ui.value.isPlaying
        return Bundle().apply {
            putInt("status", if (playing) 1 else 0)
            putInt("code", 200)
            putString("msg", "success")
        }
    }

    /**
     * 播放控制。params.data = {"action":"play|pause|resume|next|previous|changeMode","playMode":N}
     */
    private fun playController(params: Bundle?): Bundle {
        val data = runCatching {
            JSONObject(params?.getString("data") ?: "{}")
        }.getOrElse { JSONObject() }
        val action = data.optString("action")
        Log.i(TAG, "playController action=$action data=$data")

        when (action) {
            "play" -> PlayerHub.voicePlay()
            "resume" -> PlayerHub.voiceResume()
            "pause" -> PlayerHub.voicePause()
            "next" -> PlayerHub.next()
            "previous" -> PlayerHub.previous()
            "changeMode" -> {
                val mode = data.optInt("playMode", 0)
                PlayerHub.voiceSetPlayMode(mode)
            }
            "subscribe" -> Unit // 收藏：本地播放器无此能力，忽略
            else -> {
                Log.w(TAG, "unknown action=$action")
                return bundleOf(code = 400, msg = "unknown action: $action")
            }
        }
        // 广播状态，让小布立即更新界面
        postPlayStatus()
        return bundleOf(code = 200, msg = "success")
    }

    // ------------------------------------------------------------------ 事件上报

    /** 供 PlayerHub 在播放状态/歌曲变化时调用 */
    fun postPlayStatus() {
        val st = PlayerHub.ui.value
        val state = if (st.isPlaying) 1 else 0
        if (state != lastStateEvent) {
            lastStateEvent = state
            dispatch("EVENT_PLAY_STATUS", Bundle().apply { putInt("status", state) })
        }
        val song = st.current
        if (song != null && song.id != lastSongId) {
            lastSongId = song.id
            dispatch(
                "EVENT_PLAY_INFO",
                Bundle().apply {
                    putString("name", song.title)
                    putString("artist", song.artist)
                    putString("album", song.album)
                    putInt("duration", (song.durationMs / 1000).toInt())
                }
            )
        }
    }

    private fun dispatch(event: String, data: Bundle) {
        if (listeners.isEmpty()) return
        Log.i(TAG, "dispatch $event ${dumpBundle(data)}")
        for (l in listeners) {
            try {
                val wanted = runCatching { l.events() }.getOrNull()
                if (wanted == null || wanted.isEmpty() || wanted.contains(event)) {
                    l.onEvent(event, data)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "dispatch to listener failed", t)
                listeners.remove(l)
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun bundleOf(code: Int, msg: String) = Bundle().apply {
        putInt("code", code)
        putString("msg", msg)
    }

    private fun dumpBundle(b: Bundle?): String =
        b?.let { bb ->
            bb.keySet().joinToString(", ") { k ->
                val v = runCatching { bb.get(k) }.getOrNull()
                "$k=${if (v is Bundle) dumpBundle(v) else v}"
            }
        } ?: "null"
}
