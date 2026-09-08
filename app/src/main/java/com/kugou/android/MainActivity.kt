package com.kugou.android

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kugou.android.databinding.ActivityMainBinding
import com.kugou.android.ui.FoldersFragment
import com.kugou.android.ui.SettingsFragment
import com.kugou.android.ui.SongsFragment
import com.kugou.android.ui.audioPermissionArray
import com.kugou.android.ui.hasAudioPermission
import com.kugou.android.util.CoverCache
import com.kugou.android.util.artLetter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val songsFrag = SongsFragment()
    private val foldersFrag = FoldersFragment()
    private val settingsFrag = SettingsFragment()
    private var current: Fragment = songsFrag
    private var miniAlbumId: Long = -1L

    companion object {
        const val REQ_PERMISSION = 41
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Android 15+ 强制 edge-to-edge：手动给根布局加系统栏内边距，避免内容被状态栏/任务栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(b.mainRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentHost, songsFrag, "songs")
                .add(R.id.fragmentHost, foldersFrag, "folders").hide(foldersFrag)
                .add(R.id.fragmentHost, settingsFrag, "settings").hide(settingsFrag)
                .commit()
            current = songsFrag
        }

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_songs -> show(songsFrag)
                R.id.nav_folders -> show(foldersFrag)
                R.id.nav_settings -> show(settingsFrag)
            }
            true
        }
        b.miniPlayer.root.setOnClickListener { openPlayer() }
        b.miniPlayer.mpPlay.setOnClickListener { PlayerHub.toggle() }
        b.miniPlayer.mpNext.setOnClickListener { PlayerHub.next() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlayerHub.ui.collect { st ->
                    renderMini(st)
                }
            }
        }

        handleIntent(intent)
        maybeFirstScan()
        maybeSelfTest(intent)
    }

    /**
     * 自测：模拟小布绑定酷狗服务并下发命令，验证语音播放链路。
     * 用法：adb shell am start -n com.kugou.android/.MainActivity --es selftest play
     */
    private fun maybeSelfTest(intent: Intent) {
        val cmd = intent.getStringExtra("selftest") ?: return
        android.util.Log.i("SelfTest", "selftest cmd=$cmd")
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                try {
                    val api = com.kugou.android.thirdmap.IKGMapApi.Stub.asInterface(binder)
                    // 1) 授权握手
                    val ap = android.os.Bundle().apply {
                        putString("openAppId", "10272")
                        putString("secretKey", "uvrMziPtvkZueI1F6smPOa9HWxYQNwHG")
                        putString("packageName", "com.heytap.speechassist")
                    }
                    val ar = api.execute("v2_check_authority", ap)
                    android.util.Log.i("SelfTest", "v2_check_authority -> code=${ar?.getInt("code")}")
                    // 2) 注册事件
                    val l = object : com.kugou.android.thirdmap.IKGMapApiEventListener.Stub() {
                        override fun onEvent(e: String?, d: android.os.Bundle?) {
                            android.util.Log.i("SelfTest", "EVENT $e")
                        }
                    }
                    api.registerEventListener(arrayListOf("API_EVENT_PLAY_STATE_CHANGED", "API_EVENT_PLAY_SONG_CHANGED"), l)
                    // 3) 模拟小布 kgSemanticSlots（intent 在 JSON 里）
                    val slotsJson = if (cmd == "search") {
                        // 真实点歌 payload：query + slots(singer/song)
                        "{\"domain\":\"music\",\"intent\":\"play\",\"query\":\"播放周杰伦的惊叹号\"," +
                            "\"slots\":[{\"name\":\"singer\",\"slot_struct\":1,\"values\":[{\"original_text\":\"周杰伦\",\"text\":\"周杰伦\"}]}," +
                            "{\"name\":\"song\",\"slot_struct\":1,\"values\":[{\"original_text\":\"惊叹号\",\"text\":\"惊叹号\"}]}],\"type\":1}"
                    } else {
                        "{\"args\":null,\"domain\":\"music\",\"intent\":\"$cmd\",\"query\":null,\"slots\":null,\"type\":2}"
                    }
                    val slots = android.os.Bundle().apply {
                        putString("semanticslots", slotsJson)
                        putString("appid", "3098")
                    }
                    api.executeAsync("kgSemanticSlots", slots, object : com.kugou.android.thirdmap.IKGMapApiCallback.Stub() {
                        override fun onResult(b: android.os.Bundle?) {
                            android.util.Log.i("SelfTest", "kgSemanticSlots($cmd) -> code=${b?.getInt("code")}")
                        }
                    })
                    // 4) 状态查询
                    Thread.sleep(1500)
                    val st = api.execute("v1_getCurrentSong", null)
                    android.util.Log.i("SelfTest", "v1_getCurrentSong -> ${st?.getString("data")}")
                } catch (t: Throwable) {
                    android.util.Log.e("SelfTest", "selftest failed", t)
                }
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {}
        }
        bindService(
            Intent(this, com.kugou.android.thirdapi.KGMusicUnityService::class.java),
            conn,
            BIND_AUTO_CREATE
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        maybeSelfTest(intent)
    }

    // ---------------------------------------------------------------- 语音助手

    private fun handleIntent(intent: Intent) {
        // ★ OPPO 小布(Breeno) 酷狗 deeplink 语音控制
        //   kugou://m.kugou.com/voicehelper?query=下一首&appid=3098&...
        val data = intent.data
        if (data != null && "kugou" == data.scheme && data.host == "m.kugou.com") {
            val query = data.getQueryParameter("query")
                ?: data.getQueryParameter("q")
                ?: intent.getStringExtra("query")
                ?: ""
            android.util.Log.i("VoiceCmd", "deeplink query=$query full=$data")
            handleVoiceCommand(query)
            show(songsFrag)
            return
        }

        when (intent.action) {
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH,
            "android.intent.action.MEDIA_PLAY_FROM_SEARCH",
            Intent.ACTION_SEARCH,
            Intent.ACTION_VOICE_COMMAND -> {
                val query = extractQuery(intent)
                PlayerHub.playSearch(query)
                show(songsFrag)
            }
            "android.media.action.MEDIA_PLAY_FROM_URI",
            "android.intent.action.MEDIA_PLAY_FROM_URI",
            Intent.ACTION_VIEW -> {
                intent.data?.let { PlayerHub.playUri(it) }
                show(songsFrag)
            }
        }
    }

    /**
     * 执行小布语音命令。命令词来自 Breeno 资源（multimedia_media_control_*）：
     * 播放 / 暂停 / 下一首 / 上一首 / 收藏 / 随机播放 / 单曲循环 / 顺序播放 / 或歌曲搜索词
     */
    private fun handleVoiceCommand(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            PlayerHub.voicePlay()
            return
        }
        when {
            q.contains("暂停") || q.contains("停止") -> PlayerHub.voicePause()
            q.contains("下一首") || q.contains("下一曲") -> PlayerHub.next()
            q.contains("上一首") || q.contains("上一曲") -> PlayerHub.previous()
            q.contains("随机") -> PlayerHub.voiceRandomPlay()
            q.contains("单曲循环") -> PlayerHub.voiceSetPlayMode(2)
            q.contains("顺序") -> PlayerHub.voiceSetPlayMode(0)
            q.contains("循环") -> PlayerHub.voiceSetPlayMode(1)
            q.contains("收藏") -> Unit // 本地播放器无收藏能力
            q == "播放" || q.contains("播放音乐") || q.contains("继续") -> PlayerHub.voicePlay()
            else -> PlayerHub.voiceSearchAndPlay(q) // “播放周杰伦” 等搜索播放
        }
    }

    private fun extractQuery(intent: Intent): String {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { if (it.isNotBlank()) return it }
        intent.getStringExtra(SearchManager.QUERY)?.let { if (it.isNotBlank()) return it }
        intent.getStringExtra("query")?.let { if (it.isNotBlank()) return it }
        intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS)?.let { if (it.isNotBlank()) return it }
        val data = intent.data
        if (data != null) {
            data.getQueryParameter("search")?.let { if (it.isNotBlank()) return it }
            data.getQueryParameter("q")?.let { if (it.isNotBlank()) return it }
            data.getQueryParameter("query")?.let { if (it.isNotBlank()) return it }
        }
        return ""
    }

    // ---------------------------------------------------------------- 导航

    private fun show(f: Fragment) {
        if (current === f) return
        supportFragmentManager.beginTransaction().hide(current).show(f).commit()
        current = f
    }

    // ---------------------------------------------------------------- 权限 & 首次扫描

    private fun maybeFirstScan() {
        val need = mutableListOf<String>()
        if (!hasAudioPermission(this)) {
            need += if (android.os.Build.VERSION.SDK_INT >= 33)
                android.Manifest.permission.READ_MEDIA_AUDIO
            else
                android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // Android 13+：通知权限独立于音频权限，未授予时一并申请（媒体通知卡片必需）
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            need += android.Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERMISSION)
        } else if (LibraryRepo.raw.isEmpty()) {
            LibraryRepo.rescan(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            if (hasAudioPermission(this) && LibraryRepo.raw.isEmpty()) {
                LibraryRepo.rescan(this)
            }
        }
    }

    private fun renderMini(st: PlayerHub.UiState) {
        val show = st.current != null
        if (show && b.miniPlayer.root.visibility != View.VISIBLE) {
            // 悬浮条淡入 + 上滑入场
            b.miniPlayer.root.alpha = 0f
            b.miniPlayer.root.translationY = 30f
            b.miniPlayer.root.visibility = View.VISIBLE
            b.miniPlayer.root.animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else if (!show && b.miniPlayer.root.visibility != View.GONE) {
            b.miniPlayer.root.animate().alpha(0f).translationY(30f).setDuration(150)
                .withEndAction { b.miniPlayer.root.visibility = View.GONE }
                .start()
            return
        }
        if (!show) return
        val song = st.current!!
        b.miniPlayer.mpTitle.text = song.title
        b.miniPlayer.mpArtist.text = if (song.artist.isBlank()) "未知歌手" else song.artist
        // 封面：仅歌曲变化时加载
        if (miniAlbumId != song.albumId) {
            miniAlbumId = song.albumId
            b.miniPlayer.mpArt.text = artLetter(song.title)
            b.miniPlayer.mpArtImg.visibility = View.GONE
            b.miniPlayer.mpArt.visibility = View.VISIBLE
            lifecycleScope.launch {
                val bmp = CoverCache.load(applicationContext, song.albumId)
                if (bmp != null && PlayerHub.ui.value.current?.albumId == song.albumId) {
                    b.miniPlayer.mpArtImg.setImageBitmap(bmp)
                    b.miniPlayer.mpArtImg.visibility = View.VISIBLE
                    b.miniPlayer.mpArt.visibility = View.GONE
                }
            }
        }
        b.miniPlayer.mpPlay.setImageResource(if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun openPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
