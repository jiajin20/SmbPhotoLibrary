package com.example.smbphoto.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.smbphoto.R
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.databinding.ItemTvPhotoBinding

/**
 * TV 专用相片适配器
 *
 * 特性：
 * - D-pad 焦点时放大 + 高亮边框
 * - 确认键触发点击
 * - 图片尺寸更大（TV 大屏幕适配）
 */
class TvPhotoAdapter(
    private val onItemClick: (SmbImageFile) -> Unit
) : ListAdapter<SmbImageFile, TvPhotoAdapter.TvPhotoViewHolder>(DIFF_CALLBACK) {

    inner class TvPhotoViewHolder(
        private val binding: ItemTvPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.isFocusable = true
            binding.root.isFocusableInTouchMode = true

            // 焦点变化：放大 + 高亮
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.08f else 1.0f)
                    .scaleY(if (hasFocus) 1.08f else 1.0f)
                    .setDuration(150)
                    .start()
                binding.focusBorder.visibility = if (hasFocus)
                    android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }

        fun bind(item: SmbImageFile) {
            Glide.with(binding.ivThumbnail)
                .load(item)
                .placeholder(R.drawable.ic_image_placeholder)
                .error(R.drawable.ic_image_broken)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(binding.ivThumbnail)

            binding.tvFileName.text = item.name

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TvPhotoViewHolder {
        val binding = ItemTvPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TvPhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TvPhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SmbImageFile>() {
            override fun areItemsTheSame(old: SmbImageFile, new: SmbImageFile) =
                old.remotePath == new.remotePath
            override fun areContentsTheSame(old: SmbImageFile, new: SmbImageFile) = old == new
        }
    }
}
