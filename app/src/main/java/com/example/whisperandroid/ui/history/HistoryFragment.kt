package com.example.whisperandroid.ui.history

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.whisperandroid.R
import com.example.whisperandroid.data.db.TranscriptionEntity
import com.example.whisperandroid.databinding.FragmentHistoryBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HistoryViewModel by viewModels()

    private var pendingExportTarget: TranscriptionEntity? = null

    private val pickExportDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val target = pendingExportTarget
            pendingExportTarget = null
            if (uri != null && target != null) {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.exportToTree(target, uri) { result ->
                    val msg = if (result != null) R.string.export_done else R.string.export_failed
                    _binding?.root?.let { Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show() }
                }
            }
        }

    private val adapter = HistoryAdapter(
        onClick = { showDetail(it) },
        onShare = { share(it) },
        onExport = { export(it) },
        onDelete = { confirmDelete(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.items.collect { list ->
                    adapter.submitList(list)
                    binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showDetail(item: TranscriptionEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.sourceName)
            .setMessage(item.text)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.share) { _, _ -> share(item) }
            .show()
    }

    private fun share(item: TranscriptionEntity) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, item.sourceName)
            putExtra(Intent.EXTRA_TEXT, item.text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun export(item: TranscriptionEntity) {
        pendingExportTarget = item
        pickExportDir.launch(null)
    }

    private fun confirmDelete(item: TranscriptionEntity) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.delete(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
