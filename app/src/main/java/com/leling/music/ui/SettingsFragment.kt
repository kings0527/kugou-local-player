package com.leling.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.leling.music.EqPresets
import com.leling.music.LibraryRepo
import com.leling.music.R
import com.leling.music.SettingsRepo
import com.leling.music.SettingsRepo.Settings
import com.leling.music.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // EQ 预设 chips（布局已含 eq0..eq8）
        val eqNames = EqPresets.NAMES
        for (i in eqNames.indices) {
            val id = resources.getIdentifier("eq$i", "id", requireContext().packageName)
            val chip = b.eqGroup.findViewById<MaterialButton>(id)
            chip?.text = eqNames[i]
        }
        b.eqGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val name = b.eqGroup.findViewById<MaterialButton>(checkedId)?.text?.toString()
                if (name != null) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        SettingsRepo.get(requireContext()).setEqPreset(name)
                    }
                }
            }
        }
        b.eqSwitch.setOnCheckedChangeListener { _, on ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsRepo.get(requireContext()).setEqEnabled(on)
            }
        }
        b.whitelistSwitch.setOnCheckedChangeListener { _, on ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsRepo.get(requireContext()).setWhitelistMode(on)
            }
        }
        b.sysIgnoreSwitch.setOnCheckedChangeListener { _, on ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsRepo.get(requireContext()).setIgnoreSystemDirs(on)
            }
        }

        b.rowDefaultPlayer.setOnClickListener { openDefaultApps() }
        b.rowAbout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_about)
                .setMessage(R.string.about_text)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        b.rowRescan.setOnClickListener {
            LibraryRepo.rescan(requireContext()) {
                Toast.makeText(requireContext(), getString(R.string.rescan_done, LibraryRepo.filteredSize), Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SettingsRepo.get(requireContext()).settings.collect { s ->
                    renderSettings(s)
                    LibraryRepo.applyFilter(requireContext())
                }
            }
        }
    }

    private fun renderSettings(s: Settings) {
        // EQ
        b.eqSwitch.setOnCheckedChangeListener(null)
        b.eqSwitch.isChecked = s.eqEnabled
        b.eqSwitch.setOnCheckedChangeListener { _, on ->
            viewLifecycleOwner.lifecycleScope.launch { SettingsRepo.get(requireContext()).setEqEnabled(on) }
        }
        b.eqScroll.visibility = if (s.eqEnabled) View.VISIBLE else View.GONE
        if (s.eqEnabled) {
            val idx = EqPresets.NAMES.indexOf(s.eqPreset).coerceAtLeast(0)
            if (idx < b.eqGroup.childCount) b.eqGroup.check(b.eqGroup.getChildAt(idx).id)
        }

        // 只听
        b.whitelistSwitch.setOnCheckedChangeListener(null)
        b.whitelistSwitch.isChecked = s.whitelistMode
        b.whitelistSwitch.setOnCheckedChangeListener { _, on ->
            viewLifecycleOwner.lifecycleScope.launch { SettingsRepo.get(requireContext()).setWhitelistMode(on) }
        }
        renderDirRows(
            b.whitelistList, b.whitelistEmpty, topmost(s.whitelistDirs), s.whitelistMode
        ) { dir ->
            viewLifecycleOwner.lifecycleScope.launch { SettingsRepo.get(requireContext()).toggleWhitelistDir(dir) }
        }
        b.whitelistEmpty.visibility = if (s.whitelistDirs.isEmpty()) View.VISIBLE else View.GONE

        // 忽略
        renderDirRows(b.ignoredList, null, topmost(s.ignoredDirs), true) { dir ->
            viewLifecycleOwner.lifecycleScope.launch { SettingsRepo.get(requireContext()).removeIgnoredDir(dir) }
        }
        b.ignoredEmpty.visibility = if (s.ignoredDirs.isEmpty()) View.VISIBLE else View.GONE

        // 系统目录
        b.sysIgnoreSwitch.setOnCheckedChangeListener(null)
        b.sysIgnoreSwitch.isChecked = s.ignoreSystemDirs
        b.sysIgnoreSwitch.setOnCheckedChangeListener { _, on ->
            viewLifecycleOwner.lifecycleScope.launch { SettingsRepo.get(requireContext()).setIgnoreSystemDirs(on) }
        }
    }

    /** 去掉已被祖先目录覆盖的子目录，避免列表冗余 */
    private fun topmost(dirs: Set<String>): List<String> =
        dirs.sorted().filter { d -> dirs.none { it != d && d.startsWith("$it/") } }

    private fun renderDirRows(container: LinearLayout, emptyLabel: TextView?, dirs: List<String>, showRemove: Boolean, onRemove: (String) -> Unit) {
        container.removeAllViews()
        for (d in dirs) {
            val row = layoutInflater.inflate(R.layout.item_dir_row, container, false)
            row.findViewById<TextView>(R.id.dirName).text = d
            if (showRemove) {
                row.findViewById<ImageButton>(R.id.dirRemove).apply {
                    setOnClickListener { onRemove(d) }
                }
            }
            container.addView(row)
        }
        emptyLabel?.visibility = if (dirs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDefaultApps() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:${requireContext().packageName}")))
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.open_settings_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
