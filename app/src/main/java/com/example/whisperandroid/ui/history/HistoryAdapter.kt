package com.example.whisperandroid.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.whisperandroid.data.db.TranscriptionEntity
import com.example.whisperandroid.databinding.ItemTranscriptionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onClick: (TranscriptionEntity) -> Unit,
    private val onShare: (TranscriptionEntity) -> Unit,
    private val onExport: (TranscriptionEntity) -> Unit,
    private val onDelete: (TranscriptionEntity) -> Unit
) : ListAdapter<TranscriptionEntity, HistoryAdapter.VH>(Diff) {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    inner class VH(val b: ItemTranscriptionBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTranscriptionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        with(holder.b) {
            tvTitle.text = item.sourceName
            tvMeta.text = "${fmt.format(Date(item.createdAt))}  ·  ${item.modelName}"
            tvPreview.text = item.text.take(140)
            root.setOnClickListener { onClick(item) }
            btnShare.setOnClickListener { onShare(item) }
            btnExport.setOnClickListener { onExport(item) }
            btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    object Diff : DiffUtil.ItemCallback<TranscriptionEntity>() {
        override fun areItemsTheSame(a: TranscriptionEntity, b: TranscriptionEntity) = a.id == b.id
        override fun areContentsTheSame(a: TranscriptionEntity, b: TranscriptionEntity) = a == b
    }
}
