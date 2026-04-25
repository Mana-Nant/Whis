package com.example.whisperandroid.ui.transcribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.whisperandroid.R
import com.example.whisperandroid.audio.AudioRecorder
import com.example.whisperandroid.databinding.FragmentTranscribeBinding
import com.example.whisperandroid.service.TranscriptionService
import com.example.whisperandroid.util.DeviceMemoryUtils
import com.example.whisperandroid.util.FileUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class TranscribeFragment : Fragment() {

    private var _binding: FragmentTranscribeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TranscribeViewModel by viewModels()
    private val recorder = AudioRecorder()

    private var recordedFile: File? = null
    private var recordStartElapsed: Long = 0L
    private var timerJob: Job? = null
    private var lastShownCompletedId: Long = -1L
    private var lastShownFailedMessage: String? = null

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

    private val requestMicPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else
                Snackbar.make(binding.root, R.string.err_mic_permission, Snackbar.LENGTH_LONG).show()
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTranscribeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPickFile.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        binding.btnRecord.setOnClickListener { toggleRecording() }
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

    // -------- 録音 --------
    private fun toggleRecording() {
        if (recorder.isRecording) {
            stopRecordingAndStart()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startRecording() else requestMicPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        val ctx = requireContext()
        val outFile = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.wav")
        recordedFile = outFile

        try {
            recorder.start(outFile)
        } catch (t: Throwable) {
            Snackbar.make(binding.root, "録音開始に失敗: ${t.message}", Snackbar.LENGTH_LONG).show()
            return
        }

        recordStartElapsed = SystemClock.elapsedRealtime()
        binding.btnRecord.setText(R.string.record_stop)
        binding.tvRecording.visibility = View.VISIBLE
        binding.btnPickFile.isEnabled = false
        binding.btnStart.isEnabled = false

        timerJob?.cancel()
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (recorder.isRecording) {
                val sec = (SystemClock.elapsedRealtime() - recordStartElapsed) / 1000
                val m = sec / 60; val s = sec % 60
                binding.tvRecording.text = getString(
                    R.string.recording_fmt, "%d:%02d".format(m, s)
                )
                delay(500)
            }
        }
    }

    private fun stopRecordingAndStart() {
        recorder.stop()
        binding.btnRecord.setText(R.string.record_start)
        binding.tvRecording.visibility = View.GONE
        binding.btnPickFile.isEnabled = true
        binding.btnStart.isEnabled = true
        timerJob?.cancel()

        // ファイル書き出しが完了するまで少し待つ
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            val f = recordedFile ?: return@launch
            if (!f.exists() || f.length() < 1024) {
                Snackbar.make(binding.root, "録音が短すぎます", Snackbar.LENGTH_LONG).show()
                return@launch
            }
            // ローカルファイルもURIにラップしてサービスに渡す
            val uri = Uri.fromFile(f)
            val displayName = "録音 ${java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date())}"
            viewModel.setInput(uri, displayName)
            binding.tvSelectedFile.text = displayName
            // そのまま文字起こし起動
            start()
        }
    }

    // -------- 文字起こし --------
    private fun start() {
        val ctx = requireContext()
        val uri = viewModel.selectedUri.value
        if (uri == null) {
            Snackbar.make(binding.root, R.string.err_no_file, Snackbar.LENGTH_SHORT).show(); return
        }
        val modelPath = viewModel.currentModelPath()
        if (modelPath == null) {
            Snackbar.make(binding.root, R.string.err_no_model, Snackbar.LENGTH_LONG).show(); return
        }
        viewModel.currentModel()?.let { m ->
            if (DeviceMemoryUtils.isRisky(ctx, m)) {
                Snackbar.make(
                    binding.root, getString(R.string.warn_model_heavy, m.displayName),
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
                // 同じ完了イベントで何度もSnackbarが出ないように一度だけ表示
                if (state.transcriptionId != lastShownCompletedId) {
                    lastShownCompletedId = state.transcriptionId
                    Snackbar.make(binding.root, R.string.status_saved_history, Snackbar.LENGTH_LONG).show()
                }
            }
            is TranscriptionService.ServiceState.Failed -> {
                binding.tvStatus.text = getString(R.string.status_failed_fmt, state.message)
                binding.btnStart.isEnabled = true
                binding.btnCancel.isEnabled = false
                binding.progress.isIndeterminate = false
                // 同じ失敗イベントで何度もSnackbarが出ないように一度だけ表示
                if (state.message != lastShownFailedMessage) {
                    lastShownFailedMessage = state.message
                    Snackbar.make(
                        binding.root,
                        getString(R.string.status_failed_fmt, state.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        timerJob?.cancel()
        if (recorder.isRecording) recorder.cancel()
        super.onDestroyView()
        _binding = null
    }
}
