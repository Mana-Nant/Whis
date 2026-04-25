package com.example.whisperandroid.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.whisperandroid.R
import com.example.whisperandroid.data.model.WhisperModel
import com.example.whisperandroid.databinding.FragmentSettingsBinding
import com.example.whisperandroid.util.DeviceMemoryUtils
import com.example.whisperandroid.util.FileUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    private val pickModelFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val name = FileUtils.queryDisplayName(requireContext(), uri)
                viewModel.importModel(uri, name)
            }
        }

    private val pickExportDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setExportDir(uri)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ramGb = DeviceMemoryUtils.totalRamGB(requireContext())
        binding.tvDeviceInfo.text = getString(R.string.device_ram_fmt, ramGb)

        binding.btnDownload.setOnClickListener { showDownloadDialog() }
        binding.btnImport.setOnClickListener { pickModelFile.launch(arrayOf("*/*")) }
        binding.btnPickExportDir.setOnClickListener { pickExportDir.launch(null) }
        binding.switchAutoExport.setOnCheckedChangeListener { _, v -> viewModel.toggleAutoExport(v) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.installed.collect { renderInstalled(it) } }
                launch {
                    viewModel.currentModel.collect {
                        binding.tvCurrentModel.text = it ?: getString(R.string.no_model_selected)
                    }
                }
                launch { viewModel.autoExport.collect { binding.switchAutoExport.isChecked = it } }
                launch {
                    viewModel.exportDir.collect {
                        binding.tvExportDir.text = it ?: getString(R.string.not_set)
                    }
                }
                launch {
                    viewModel.downloading.collect { map ->
                        if (map.isEmpty()) {
                            binding.tvDownloadStatus.visibility = View.GONE
                        } else {
                            binding.tvDownloadStatus.visibility = View.VISIBLE
                            binding.tvDownloadStatus.text = map.entries.joinToString("\n") {
                                "${it.key}  ${it.value}%"
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); viewModel.refreshInstalled() }

    private fun renderInstalled(files: List<File>) {
        binding.containerInstalled.removeAllViews()
        if (files.isEmpty()) {
            val tv = android.widget.TextView(requireContext()).apply {
                setText(R.string.no_installed_models)
                setPadding(0, 8, 0, 8)
            }
            binding.containerInstalled.addView(tv)
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        files.forEach { file ->
            val row = com.example.whisperandroid.databinding.ItemModelBinding
                .inflate(inflater, binding.containerInstalled, false)
            row.tvName.text = file.name
            row.tvMeta.text = getString(R.string.size_mb_fmt, file.length() / (1024 * 1024))
            val isCurrent = file.name == viewModel.currentModel.value
            row.btnUse.isEnabled = !isCurrent
            row.btnUse.text = if (isCurrent) getString(R.string.in_use) else getString(R.string.use)
            row.btnUse.setOnClickListener { viewModel.setCurrentModel(file.name) }
            row.btnDelete.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.confirm_delete_model_fmt, file.name))
                    .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteModel(file) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            binding.containerInstalled.addView(row.root)
        }
    }

    private fun showDownloadDialog() {
        val models = WhisperModel.values().toList()
        val labels = models.map { m ->
            val risky = DeviceMemoryUtils.isRisky(requireContext(), m)
            val tag = if (risky) " ⚠" else ""
            "${m.displayName}  (~${m.approxSizeMB}MB)$tag"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_model)
            .setItems(labels) { _, which ->
                val m = models[which]
                if (DeviceMemoryUtils.isRisky(requireContext(), m)) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.warn)
                        .setMessage(getString(R.string.warn_ram_fmt, m.displayName, m.recommendedRamGB))
                        .setPositiveButton(R.string.proceed) { _, _ -> viewModel.startDownload(m) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    viewModel.startDownload(m)
                    Snackbar.make(binding.root, R.string.download_started, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
