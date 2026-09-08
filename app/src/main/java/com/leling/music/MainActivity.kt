package com.leling.music

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
import com.leling.music.databinding.ActivityMainBinding
import com.leling.music.ui.FoldersFragment
import com.leling.music.ui.SettingsFragment
import com.leling.music.ui.SongsFragment
import com.leling.music.ui.audioPermissionArray
import com.leling.music.ui.hasAudioPermission
import com.leling.music.util.artLetter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val songsFrag = SongsFragment()
    private val foldersFrag = FoldersFragment()
    private val settingsFrag = SettingsFragment()
    private var current: Fragment = songsFrag

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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- 语音助手

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = extractQuery(intent)
                if (query.isNotEmpty()) {
                    PlayerHub.playSearch(query)
                    // 同时切到歌曲页展示播放状态
                    show(songsFrag)
                }
            }
            "android.media.action.MEDIA_PLAY_FROM_URI" -> {
                intent.data?.let { PlayerHub.playUri(it) }
            }
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
        b.miniPlayer.root.isVisible = show
        if (!show) return
        val song = st.current!!
        b.miniPlayer.mpTitle.text = song.title
        b.miniPlayer.mpArtist.text = if (song.artist.isBlank()) "未知歌手" else song.artist
        b.miniPlayer.mpArt.text = artLetter(song.title)
        b.miniPlayer.mpPlay.setImageResource(if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun openPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
