package com.example.smbphoto.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.smbphoto.R
import com.example.smbphoto.databinding.ItemDirectoryEntryBinding
import com.example.smbphoto.smb.SmbEntry

/**
 * SMB 目录浏览器适配器
 *
 * 用于配置页面的"浏览目录"对话框，显示远程目录列表。
 */
class DirectoryPickerAdapter(
    private val onEntryClick: (SmbEntry) -> Unit,
) : ListAdapter<SmbEntry, DirectoryPickerAdapter.EntryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val binding = ItemDirectoryEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EntryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EntryViewHolder(
        private val binding: ItemDirectoryEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onEntryClick(getItem(position))
                }
            }
        }

        fun bind(entry: SmbEntry) {
            if (entry.isDirectory) {
                binding.tvIcon.text = "📁"
                binding.tvArrow.visibility = android.view.View.VISIBLE
                binding.tvArrow.text = "›"
                binding.tvEntryInfo.visibility = android.view.View.GONE
            } else {
                // 判断是否是图片文件
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                binding.tvIcon.text = if (isImage) "🖼️" else "📄"
                binding.tvArrow.visibility = android.view.View.INVISIBLE
                // 显示文件大小
                binding.tvEntryInfo.visibility = android.view.View.VISIBLE
                binding.tvEntryInfo.text = formatFileSize(entry.size)
            }

            binding.tvEntryName.text = entry.name
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024))
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<SmbEntry>() {
        override fun areItemsTheSame(old: SmbEntry, new: SmbEntry): Boolean =
            old.path == new.path

        override fun areContentsTheSame(old: SmbEntry, new: SmbEntry): Boolean =
            old == new
    }
}
