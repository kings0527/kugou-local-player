package com.kugou.android.ui

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
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kugou.android.EqPresets
import com.kugou.android.LibraryRepo
import com.kugou.android.R
import com.kugou.android.SettingsRepo
import com.kugou.android.SettingsRepo.Settings
import com.kugou.android.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // EQ 预设 chips（ChipGroup 自动换行）
        b.eqGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val name = b.eqGroup.findViewById<Chip>(checkedIds.first())?.text?.toString()
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
        b.eqGroup.visibility = if (s.eqEnabled) View.VISIBLE else View.GONE
        if (s.eqEnabled) {
            val idx = EqPresets.NAMES.indexOf(s.eqPreset).coerceAtLeast(0)
            val id = resources.getIdentifier("eq$idx", "id", requireContext().packageName)
            if (id != 0) b.eqGroup.check(id)
        }

        // 忽略目录
        renderDirRows(b.ignoredList, null, s.ignoredDirs.sorted()) { dir ->
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

    private fun renderDirRows(container: LinearLayout, emptyLabel: TextView?, dirs: List<String>, onRemove: (String) -> Unit) {
        container.removeAllViews()
        for (d in dirs) {
            val row = layoutInflater.inflate(R.layout.item_dir_row, container, false)
            row.findViewById<TextView>(R.id.dirName).text = d
            row.findViewById<ImageButton>(R.id.dirRemove).setOnClickListener { onRemove(d) }
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
