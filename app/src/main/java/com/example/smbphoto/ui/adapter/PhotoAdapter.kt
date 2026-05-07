package com.example.smbphoto.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.smbphoto.R
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.databinding.ItemPhotoBinding
import com.example.smbphoto.glide.VideoThumbnailLoader

/**
 * 相片/视频列表 RecyclerView 适配器
 *
 * 使用 DiffUtil 实现高效局部刷新。
 * 缩略图使用 override(160, 160) 优化磁盘缓存，加速二次加载。
 * 视频文件显示播放图标覆盖层，缩略图通过 VideoThumbnailLoader 从 SMB 流提取帧。
 */
class PhotoAdapter(
    private val onItemClick: (SmbImageFile, Int) -> Unit,
    private val onLongClick: (SmbImageFile, View) -> Unit
) : ListAdapter<SmbImageFile, PhotoAdapter.PhotoViewHolder>(DIFF_CALLBACK) {

    inner class PhotoViewHolder(
        private val binding: ItemPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SmbImageFile, position: Int) {
            // 先重置状态（处理 RecyclerView 复用）
            binding.ivVideoIndicator.visibility = View.GONE
            binding.tvDuration.visibility = View.GONE

            if (item.isVideo) {
                // 视频：使用 VideoThumbnailLoader 从 SMB 流提取帧并缓存
                VideoThumbnailLoader.load(item, binding.ivThumbnail)
                binding.ivVideoIndicator.visibility = View.VISIBLE
                binding.tvDuration.visibility = View.VISIBLE
                binding.tvDuration.text = formatFileSize(item.fileSize)
            } else {
                // 图片：直接用 Glide 加载 SMB 图片
                Glide.with(binding.ivThumbnail)
                    .load(item)
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_broken)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(160, 160)
                    .centerCrop()
                    .into(binding.ivThumbnail)
            }

            binding.root.setOnClickListener {
                onItemClick(item, position)
            }
            binding.root.setOnLongClickListener { view ->
                onLongClick(item, view)
                true
            }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "${bytes}B"
            if (bytes < 1024 * 1024) return "${bytes / 1024}KB"
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024.0))
            return String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SmbImageFile>() {
            override fun areItemsTheSame(old: SmbImageFile, new: SmbImageFile) =
                old.remotePath == new.remotePath

            override fun areContentsTheSame(old: SmbImageFile, new: SmbImageFile) =
                old == new
        }
    }
}
