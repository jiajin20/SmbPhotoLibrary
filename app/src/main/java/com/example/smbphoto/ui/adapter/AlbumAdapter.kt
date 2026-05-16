package com.example.smbphoto.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.smbphoto.R
import com.example.smbphoto.data.model.PhotoAlbum
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.databinding.ItemAlbumBinding
import java.io.File

/**
 * 相簿列表 RecyclerView 适配器
 *
 * 展示包含图片的子目录为卡片式网格。
 * 支持点击进入、长按弹出操作菜单。
 */
class AlbumAdapter(
    private val onItemClick: (PhotoAlbum) -> Unit,
    private val onItemLongClick: ((PhotoAlbum, android.view.View) -> Unit)? = null
) : ListAdapter<PhotoAlbum, AlbumAdapter.AlbumViewHolder>(DIFF_CALLBACK) {

    inner class AlbumViewHolder(
        private val binding: ItemAlbumBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: PhotoAlbum) {
            binding.tvAlbumName.text = album.name
            binding.tvAlbumPath.text = album.path
            binding.tvPhotoCount.text = "${album.photoCount} 张"

            // 加载封面图（取相簿第一张图作为封面缩略图）
            // 重要：SMB 远程文件不使用磁盘缓存（DiskCacheStrategy.NONE）
            if (!album.coverPath.isNullOrBlank()) {
                val coverImage = SmbImageFile(
                    name = album.coverPath.substringAfterLast("\\"),
                    remotePath = album.coverPath!!,
                    fileSize = 0L
                )
                Glide.with(binding.ivCover)
                    .load(coverImage)
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_placeholder)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .override(200, 160)
                    .centerCrop()
                    .into(binding.ivCover)
            } else {
                binding.ivCover.setImageResource(R.drawable.ic_image_placeholder)
            }

            binding.root.setOnClickListener { onItemClick(album) }

            // 长按弹出操作菜单，传入被长按的视图以便正确定位菜单
            onItemLongClick?.let { longClickCb ->
                binding.root.setOnLongClickListener { view ->
                    longClickCb(album, view)
                    true
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val binding = ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlbumViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PhotoAlbum>() {
            override fun areItemsTheSame(old: PhotoAlbum, new: PhotoAlbum) =
                old.path == new.path

            override fun areContentsTheSame(old: PhotoAlbum, new: PhotoAlbum) =
                old == new
        }
    }
}
