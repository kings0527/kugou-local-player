package com.kugou.android

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 播放服务：ExoPlayer + MediaSessionService（前台媒体服务）。
 *
 * 系统集成能力（=“注册为默认播放器”）：
 *  - MediaSession：通知栏媒体卡片、锁屏控制、蓝牙/耳机线控、系统媒体中心
 *  - 可被设为系统默认“音乐与音频”应用（设置页有一键入口）
 *  - 语音助手（OPPO Breeno / Google）：说“播放音乐/播放XX/随机播放”等，
 *    系统把 MEDIA_PLAY_FROM_SEARCH 等 intent 发到 MainActivity → PlayerHub.playSearch
 *
 * 省电设计：
 *  - 仅在播放期间作为前台服务 + 启用 EQ；暂停即释放音频硬件（ExoPlayer 自动）
 *  - 拔耳机自动暂停（AudioBecomingNoisy）；音频焦点让位给电话/导航
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            setHandleAudioBecomingNoisy(true)
        }
        PlayerHub.bind(player, this)

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity())
            .setCallback(object : MediaSession.Callback {
                // 诊断：记录所有控制端连接与命令（确认语音助手是否真的连到我们）
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    android.util.Log.i(
                        "PlayerService",
                        "onConnect from pkg=${controller.packageName} uid=${controller.uid}"
                    )
                    return super.onConnect(session, controller)
                }

                override fun onPostConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ) {
                    android.util.Log.i(
                        "PlayerService",
                        "onPostConnect pkg=${controller.packageName}"
                    )
                    super.onPostConnect(session, controller)
                }

                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int {
                    android.util.Log.i(
                        "PlayerService",
                        "onPlayerCommandRequest pkg=${controller.packageName} cmd=$playerCommand"
                    )
                    return super.onPlayerCommandRequest(session, controller, playerCommand)
                }

                // 语音助手/系统媒体键：无队列时收到 PLAY 就自动播全部（“播放音乐”兜底）
                override fun onMediaButtonEvent(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val ev = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    android.util.Log.i(
                        "PlayerService",
                        "onMediaButtonEvent pkg=${controller.packageName} key=${ev?.keyCode} action=${ev?.action}"
                    )
                    val handled = super.onMediaButtonEvent(mediaSession, controller, intent)
                    if (!handled && !player.isPlaying && player.mediaItemCount == 0) {
                        val code = ev?.keyCode ?: -1
                        if (code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
                            code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                            code == android.view.KeyEvent.KEYCODE_HEADSETHOOK
                        ) {
                            PlayerHub.playAll(random = false)
                            return true
                        }
                    }
                    return handled
                }
            })
            .build()
        // 首次进入即应用已保存的 EQ/循环/随机设置
        PlayerHub.applyStoredSettingsToPlayer()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        PlayerHub.unbind()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
