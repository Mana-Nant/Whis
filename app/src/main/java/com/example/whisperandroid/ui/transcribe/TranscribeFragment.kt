package com.example.whisperandroid.ui.transcribe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.whisperandroid.R
import com.example.whisperandroid.databinding.FragmentTranscribeBinding
import com.example.whisperandroid.service.TranscriptionService
import com.example.whisperandroid.util.DeviceMemoryUtils
import com.example.whisperandroid.util.FileUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class TranscribeFragment : Fragment() {

    private var _binding: FragmentTranscribeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TranscribeViewModel by viewModels()

    /** 音声ファイル選択（SAF） */
    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val name = FileUtils.queryDisplayName(requireContext(), uri)
                viewModel.setInput(uri, name)
                binding.tvSelectedFile.text = name
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTranscribeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPickFile.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        binding.btnStart.setOnClickListener { start() }
        binding.btnCancel.setOnClickListener { cancel() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentModelName.collect { n ->
                        binding.tvCurrentModel.text =
                            if (n == null) getString(R.string.no_model_selected)
                            else getString(R.string.current_model_fmt, n)
                    }
                }
                launch {
                    TranscriptionService.progress.collect {
                        binding.progress.progress = it
                        binding.tvProgress.text = "$it%"
                    }
                }
                launch {
                    TranscriptionService.state.collect { st -> applyState(st) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshCurrentModel()
    }

    private fun start() {
        val ctx = requireContext()
        val uri = viewModel.selectedUri.value
        if (uri == null) {
            Snackbar.make(binding.root, R.string.err_no_file, Snackbar.LENGTH_SHORT).show()
            return
        }
        val modelPath = viewModel.currentModelPath()
        if (modelPath == null) {
            Snackbar.make(binding.root, R.string.err_no_model, Snackbar.LENGTH_LONG).show()
            return
        }

        // 低メモリ端末での警告
        viewModel.currentModel()?.let { m ->
            if (DeviceMemoryUtils.isRisky(ctx, m)) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.warn_model_heavy, m.displayName),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        val intent = Intent(ctx, TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_START
            putExtra(TranscriptionService.EXTRA_INPUT_URI, uri.toString())
            putExtra(TranscriptionService.EXTRA_INPUT_NAME, viewModel.selectedName.value)
            putExtra(TranscriptionService.EXTRA_MODEL_PATH, modelPath)
        }
        ContextCompat.startForegroundService(ctx, intent)
        binding.btnStart.isEnabled = false
        binding.btnCancel.isEnabled = true
        binding.progress.isIndeterminate = true
    }

    private fun cancel() {
        val intent = Intent(requireContext(), TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_CANCEL
        }
        requireContext().startService(intent)
    }

    private fun applyState(state: TranscriptionService.ServiceState) {
        when (state) {
            is TranscriptionService.ServiceState.Idle -> {
                binding.tvStatus.setText(R.string.status_idle)
                binding.btnStart.isEnabled = true
                binding.btnCancel.isEnabled = false
                binding.progress.isIndeterminate = false
            }
            is TranscriptionService.ServiceState.Converting -> {
                binding.tvStatus.setText(R.string.status_converting)
                binding.progress.isIndeterminate = true
                binding.btnStart.isEnabled = false
                binding.btnCancel.isEnabled = true
            }
            is TranscriptionService.ServiceState.Transcribing -> {
                binding.tvStatus.setText(R.string.status_transcribing)
                binding.progress.isIndeterminate = false
                binding.btnStart.isEnabled = false
                binding.btnCancel.isEnabled = true
            }
            is TranscriptionService.ServiceState.Completed -> {
                binding.tvStatus.setText(R.string.status_done)
                binding.btnStart.isEnabled = true
                binding.btnCancel.isEnabled = false
                binding.progress.isIndeterminate = false
                binding.progress.progress = 100
                Snackbar.make(binding.root, R.string.status_saved_history, Snackbar.LENGTH_LONG).show()
            }
            is TranscriptionService.ServiceState.Failed -> {
                binding.tvStatus.text = getString(R.string.status_failed_fmt, state.message)
                binding.btnStart.isEnabled = true
                binding.btnCancel.isEnabled = false
                binding.progress.isIndeterminate = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
