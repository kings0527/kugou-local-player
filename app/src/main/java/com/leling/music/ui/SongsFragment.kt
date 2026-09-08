package com.leling.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.leling.music.LibraryRepo
import com.leling.music.PlayerHub
import com.leling.music.R
import com.leling.music.SettingsRepo
import com.leling.music.databinding.FragmentSongsBinding
import kotlinx.coroutines.launch

class SongsFragment : Fragment() {

    private var _b: FragmentSongsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: SongAdapter

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionUi()
            if (hasAudioPermission(requireContext())) rescan() else {
                Toast.makeText(requireContext(), R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSongsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SongAdapter { idx -> playAt(idx) }
        b.songList.layoutManager = LinearLayoutManager(requireContext())
        b.songList.adapter = adapter

        b.btnShuffleAll.setOnClickListener {
            val q = LibraryRepo.filtered
            if (q.isNotEmpty()) PlayerHub.playQueue(q.shuffled(), 0, shuffle = true)
        }
        b.btnRescan.setOnClickListener { rescan() }
        b.permissionBanner.setOnClickListener { requestPermission() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PlayerHub.ui.collect { st ->
                        adapter.playingId = st.current?.id
                        adapter.isPlaying = st.isPlaying
                        adapter.notifyDataSetChanged()
                        renderMeta(st)
                    }
                }
                launch {
                    SettingsRepo.get(requireContext()).settings.collect {
                        LibraryRepo.applyFilter(requireContext()) { render() }
                    }
                }
            }
        }
        refreshPermissionUi()
        render()
    }

    private fun renderMeta(st: PlayerHub.UiState) {
        if (b.root.isAttachedToWindow || isResumed) {
            // 无 mini player 的标签页不需要做额外处理
        }
    }

    private fun playAt(index: Int) {
        val q = LibraryRepo.filtered
        if (index in q.indices) PlayerHub.playQueue(q, index)
    }

    private fun requestPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        permLauncher.launch(arrayOf(perm))
    }

    private fun refreshPermissionUi() {
        val granted = hasAudioPermission(requireContext())
        b.permissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun rescan() {
        if (!hasAudioPermission(requireContext())) {
            requestPermission()
            return
        }
        LibraryRepo.rescan(requireContext()) {
            render()
            Toast.makeText(requireContext(), getString(R.string.rescan_done, LibraryRepo.filteredSize), Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        adapter.songs = LibraryRepo.filtered
        b.songsCount.text = getString(R.string.folder_songs_count, LibraryRepo.filteredSize)
        b.emptyView.visibility = if (LibraryRepo.filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
