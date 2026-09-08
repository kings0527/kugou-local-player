package com.kugou.android.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SimpleAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.kugou.android.DirNode
import com.kugou.android.LibraryRepo
import com.kugou.android.PlayerHub
import com.kugou.android.R
import com.kugou.android.SettingsRepo
import com.kugou.android.databinding.FragmentFoldersBinding
import com.kugou.android.util.fmtTime
import kotlinx.coroutines.launch

class FoldersFragment : Fragment() {

    private var _b: FragmentFoldersBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: FolderAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentFoldersBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FolderAdapter { idx -> openDir(LibraryRepo.dirs.getOrNull(idx)) }
        b.folderList.layoutManager = LinearLayoutManager(requireContext())
        b.folderList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SettingsRepo.get(requireContext()).settings.collect {
                        LibraryRepo.applyFilter(requireContext()) { render() }
                    }
                }
            }
        }
        render()
    }

    private fun render() {
        adapter.dirs = LibraryRepo.dirs
        b.folderEmpty.visibility = if (LibraryRepo.dirs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDir(dir: DirNode?) {
        if (dir == null) return
        val ctx = requireContext()
        val songs = com.kugou.android.songsInDir(LibraryRepo.filtered, dir.dir)
        if (songs.isEmpty()) return

        val rows = songs.map {
            mapOf(
                "l1" to it.title,
                "l2" to "${it.artist} · ${fmtTime(it.durationMs)}",
            )
        }
        val listAdapter = SimpleAdapter(
            ctx, rows, android.R.layout.simple_list_item_2,
            arrayOf("l1", "l2"), intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(dir.dir)
            .setAdapter(listAdapter) { _, which ->
                PlayerHub.playQueue(songs, which)
            }
            .setPositiveButton("随机播放") { _, _ ->
                PlayerHub.playQueue(songs.shuffled(), 0, shuffle = true)
            }
            .setNegativeButton("忽略此目录") { _, _ ->
                ignoreDir(dir.dir)
            }
            .create()
        dialog.show()
    }

    private fun ignoreDir(dir: String) {
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle(R.string.folder_ignore)
            .setMessage(dir)
            .setPositiveButton(R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    SettingsRepo.get(requireContext()).addIgnoredDir(dir)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
