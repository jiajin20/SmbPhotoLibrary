package com.example.smbphoto.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.smbphoto.R
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.databinding.ActivityPhotoDetailBinding
import com.example.smbphoto.glide.VideoThumbnailLoader
import com.example.smbphoto.smb.SmbManager
import com.example.smbphoto.streaming.StreamingVideoPlayerActivity
import com.github.chrisbanes.photoview.PhotoView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 照片/视频查看器：ViewPager2 + PhotoView 全屏浏览
 *
 * 功能：
 * - 图片：双指缩放 / 双击放大 / 平移 / 左右滑动切换
 * - 视频：显示缩略图 + 点击播放按钮调用系统播放器
 * - 分享到其他应用
 * - 保存到本地相册
 * - 沉浸式全屏，单击切换 UI 显隐
 */
@AndroidEntryPoint
class PhotoDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_PATH = "extra_remote_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_FILE_SIZE = "extra_file_size"
        /** 传入完整媒体列表（可选） */
        const val EXTRA_IMAGE_LIST = "extra_image_list"
    }

    private lateinit var binding: ActivityPhotoDetailBinding

    @Inject lateinit var smbManager: SmbManager

    private var imageList: MutableList<SmbImageFile> = mutableListOf()
    private var currentPosition: Int = 0
    private var isUiVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化视频缩略图加载器
        VideoThumbnailLoader.init(this, smbManager)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // 点击标题区域显示当前文件信息
        binding.toolbar.setOnClickListener {
            val file = imageList.getOrNull(currentPosition)
            if (file != null) {
                AlertDialog.Builder(this)
                    .setTitle(file.name)
                    .setMessage(buildString {
                        appendLine("类型：${if (file.isImage) "图片" else "视频"}")
                        if (file.fileSize > 0) {
                            appendLine("大小：${formatFileSize(file.fileSize)}")
                        }
                        appendLine("路径：${file.remotePath}")
                    })
                    .setPositiveButton("确定", null)
                    .show()
            }
        }

        // 解析参数
        val remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH) ?: run { finish(); return }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: remotePath.substringAfterLast("/")
        val position = intent.getIntExtra(EXTRA_POSITION, 0)
        val fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)

        // 获取完整图片列表（如果有传入）
        @Suppress("UNCHECKED_CAST")
        val extraList: List<SmbImageFile>? = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                (intent.getSerializableExtra(EXTRA_IMAGE_LIST) as? List<*>)
                    ?.filterIsInstance<SmbImageFile>()
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_IMAGE_LIST) as? List<SmbImageFile>
            }
        } catch (e: Exception) {
            android.util.Log.w("PhotoDetail", "Failed to parse image list", e)
            null
        }
        if (!extraList.isNullOrEmpty()) {
            imageList.addAll(extraList)
        } else {
            // 单张模式：构造只有一个元素的列表
            imageList.add(SmbImageFile(fileName, remotePath, fileSize))
        }

        currentPosition = position.coerceIn(0, imageList.size - 1)

        setupViewPager()
        setupButtons()
        setupImmersive()
        updatePageIndicator()

        // 返回键：UI 隐藏时先显示 UI，否则退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isUiVisible) {
                    showUi()
                } else {
                    finish()
                }
            }
        })
    }

    // ============================ ViewPager2 ============================

    private fun setupViewPager() {
        binding.viewPager.adapter = PhotoPagerAdapter(imageList) { position ->
            onMediaClicked(position)
        }
        binding.viewPager.setCurrentItem(currentPosition, false)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                updateTitle()
                updatePageIndicator()
            }
        })
    }

    /**
     * 媒体项被点击时的回调
     * - 视频：启动流式播放器（边加载边播，播完自动清理缓存）
     * - 图片：切换 UI 显隐
     */
    private fun onMediaClicked(position: Int) {
        val item = imageList.getOrNull(position) ?: return
        if (item.isVideo) {
            // 使用流式播放器：边加载边播，拖哪播哪，播完自动清缓存
            startActivity(StreamingVideoPlayerActivity.createIntent(this, item))
        } else {
            toggleUi()
        }
    }

    // ============================ 内部 Adapter ============================

    inner class PhotoPagerAdapter(
        private val items: MutableList<SmbImageFile>,
        private val onItemClicked: (Int) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

        inner class PhotoViewHolder(itemView: View) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {

            val photoView: PhotoView? = itemView as? PhotoView
            val videoContainer: View? = if (itemView is PhotoView) null else itemView.findViewById(R.id.videoContainer)
            val videoThumbnail: android.widget.ImageView? = itemView.findViewById(R.id.videoThumbnail)
            val playButton: android.widget.ImageView? = itemView.findViewById(R.id.btnVideoPlay)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PhotoViewHolder {
            return when (viewType) {
                0 -> { // TYPE_IMAGE
                    val photoView = PhotoView(this@PhotoDetailActivity).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setScaleLevels(1f, 3f, 6f)
                        isZoomable = true
                        maximumScale = 6f
                        mediumScale = 3f
                    }
                    PhotoViewHolder(photoView)
                }
                else -> {
                    // 视频类型：使用布局 inflate
                    val videoLayout = try {
                        layoutInflater.inflate(
                            R.layout.item_video_viewer, parent, false
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("PhotoDetail", "Failed to inflate video viewer", e)
                        // 兜底：返回一个空 FrameLayout
                        android.widget.FrameLayout(this@PhotoDetailActivity).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    }
                    PhotoViewHolder(videoLayout)
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return try {
                if (items.getOrNull(position)?.isImage == true) 0 else 1
            } catch (e: Exception) {
                1 // 异常时默认为视频类型
            }
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            val item = items.getOrNull(position) ?: return

            if (item.isImage && holder.photoView != null) {
                // ====== 图片：加载到 PhotoView（支持双指缩放/双击放大） ======
                holder.photoView.setImageDrawable(null)
                holder.photoView.setOnClickListener { onItemClicked(position) }

                Glide.with(this@PhotoDetailActivity)
                    .load(item)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .skipMemoryCache(false)
                    .error(R.drawable.ic_image_broken)
                    .into(holder.photoView)

            } else if (!item.isImage) {
                // ====== 视频：显示缩略图 + 播放按钮 ======
                holder.videoThumbnail?.setImageDrawable(null)
                holder.playButton?.setOnClickListener { onItemClicked(position) }
                holder.videoContainer?.setOnClickListener { onItemClicked(position) }

                // 优先使用内存缓存的缩略图路径
                val cachedPath = VideoThumbnailLoader.getCachedThumbnail(item)
                if (cachedPath != null) {
                    Glide.with(this@PhotoDetailActivity)
                        .load(File(cachedPath))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_broken)
                        .into(holder.videoThumbnail!!)
                } else {
                    holder.videoThumbnail?.setImageResource(R.drawable.ic_image_placeholder)
                    // 异步从 SMB 流提取视频帧
                    VideoThumbnailLoader.load(item, holder.videoThumbnail!!)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // ============================ 视频播放 ============================

    /**
     * 调用系统播放器播放远程视频
     *
     * 先将视频流下载到缓存目录，再用 FileProvider + ACTION_VIEW 打开。
     * 大文件播放：先启动后台下载，显示进度弹窗，下载完成后自动打开播放器。
     */
    private fun playVideoWithSystemPlayer(videoFile: SmbImageFile) {
        // 判断文件大小，超过 50MB 显示进度弹窗
        val isLargeFile = videoFile.fileSize > 50 * 1024 * 1024

        val downloadDialog = if (isLargeFile) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.video_loading))
                .setMessage("正在准备视频（${formatFileSize(videoFile.fileSize)}）...")
                .setCancelable(false)
                .create()
                .also { it.show() }
        } else {
            showToast(getString(R.string.video_loading))
            null
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val cacheFile = downloadToCache(videoFile)
                downloadDialog?.dismiss()

                if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
                    val uri = FileProvider.getUriForFile(
                        this@PhotoDetailActivity,
                        "${packageName}.provider",
                        cacheFile
                    )
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, getMimeType(videoFile.name))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivity(this)
                        } catch (e: Exception) {
                            showToast("没有可用的视频播放器，请安装 VLC、MX Player 等")
                        }
                    }
                } else {
                    showToast("视频加载失败，请检查网络连接后重试")
                }
            } catch (e: SecurityException) {
                downloadDialog?.dismiss()
                showToast("权限不足，无法播放视频")
            } catch (e: Exception) {
                downloadDialog?.dismiss()
                showToast("播放失败：${e.message}")
            }
        }
    }

    private fun getMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".m4v") -> "video/mp4"
            else -> "video/*"
        }
    }

    // ============================ 底部操作按钮 ============================

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            saveCurrentMedia()
        }
        binding.btnShare.setOnClickListener {
            shareCurrentMedia()
        }
        binding.btnDelete.setOnClickListener {
            deleteCurrentMedia()
        }
    }

    // ============================ 分享 ============================

    private fun shareCurrentMedia() {
        val mediaFile = imageList.getOrNull(currentPosition) ?: return
        showToast(getString(R.string.sharing))

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val cacheFile = downloadToCache(mediaFile) ?: run {
                    showToast(getString(R.string.share_failed))
                    return@launch
                }
                val uri = FileProvider.getUriForFile(
                    this@PhotoDetailActivity,
                    "${packageName}.provider",
                    cacheFile
                )

                Intent(Intent.ACTION_SEND).apply {
                    type = if (mediaFile.isImage) "image/*" else "video/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(this, getString(R.string.share_photo)))
                }
            } catch (e: Exception) {
                showToast("${getString(R.string.share_failed)}: ${e.message}")
            }
        }
    }

    // ============================ 保存到相册/本地 ============================

    private fun saveCurrentMedia() {
        val mediaFile = imageList.getOrNull(currentPosition) ?: return
        showToast(getString(R.string.saving))

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val sourceFile = downloadToCache(mediaFile) ?: run {
                    showToast(getString(R.string.save_failed))
                    return@launch
                }

                val targetDir = if (mediaFile.isVideo) {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                } else {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                }
                val appDir = File(targetDir, "SmbPhotoLibrary")
                if (!appDir.exists() && !appDir.mkdirs()) {
                    showToast("无法创建保存目录：${appDir.absolutePath}")
                    return@launch
                }

                val safeName = mediaFile.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val destFile = File(appDir, safeName)
                sourceFile.copyTo(destFile, overwrite = true)

                // 通知媒体扫描器刷新
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).also { scanIntent ->
                            scanIntent.data = Uri.fromFile(destFile)
                            sendBroadcast(scanIntent)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PhotoDetail", "Media scanner notification failed", e)
                }

                showToast(getString(R.string.saved_to_gallery, appDir.absolutePath))
            } catch (e: SecurityException) {
                showToast("没有存储权限，请检查授权设置")
            } catch (e: Exception) {
                showToast("${getString(R.string.save_failed)}: ${e.message}")
            }
        }
    }

    // ============================ 删除当前媒体 ============================

    private fun deleteCurrentMedia() {
        val mediaFile = imageList.getOrNull(currentPosition) ?: return

        AlertDialog.Builder(this@PhotoDetailActivity)
            .setTitle("删除文件")
            .setMessage("确定要删除「${mediaFile.name}」吗？此操作不可恢复！")
            .setPositiveButton("删除") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        showToast("正在删除…")
                        // 从当前列表移除
                        if (imageList.size > 1) {
                            val removePos = currentPosition.coerceIn(0, imageList.size - 1)
                            if (removePos < imageList.size) {
                                imageList.removeAt(removePos)
                            }
                            // 安全更新 ViewPager
                            try {
                                binding.viewPager.adapter?.notifyItemRemoved(removePos)
                            } catch (e: Exception) {
                                android.util.Log.w("PhotoDetail", "notifyItemRemoved failed, refreshing adapter", e)
                                // 兜底：重新设置 adapter
                                setupViewPager()
                            }
                            // 安全调整当前位置
                            if (imageList.isEmpty()) {
                                finish()
                                return@launch
                            }
                            currentPosition = currentPosition.coerceIn(0, imageList.size - 1)
                            binding.viewPager.setCurrentItem(currentPosition, false)
                            updateTitle()
                            updatePageIndicator()
                            showToast("已删除：${mediaFile.name}")
                        } else {
                            // 最后一张，直接返回
                            setResult(RESULT_OK, Intent().apply { putExtra("deleted_path", mediaFile.remotePath) })
                            showToast("已删除：${mediaFile.name}")
                            finish()
                        }
                    } catch (e: IndexOutOfBoundsException) {
                        android.util.Log.e("PhotoDetail", "Index error during delete, finishing", e)
                        showToast("已删除，返回中…")
                        finish()
                    } catch (e: Exception) {
                        showToast("删除失败：${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 将远程媒体文件下载到缓存目录
     *
     * 直接使用 SMB InputStream 流式下载，避免 Glide 处理大文件的稳定性问题。
     * 超时时间：大文件 300s，小文件 60s。
     */
    private suspend fun downloadToCache(mediaFile: SmbImageFile): File? =
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = externalCacheDir?.let { File(it, "share") } ?: run {
                    android.util.Log.e("PhotoDetail", "downloadToCache: externalCacheDir is null")
                    return@withContext null
                }
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val safeName = mediaFile.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val targetFile = File(cacheDir, "${System.currentTimeMillis()}_$safeName")

                // 直接用 SMB InputStream 流式下载，不用 Glide
                val inputStream = smbManager.openInputStream(mediaFile.remotePath)
                inputStream.use { stream ->
                    FileOutputStream(targetFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                        fos.flush()
                    }
                }

                if (targetFile.exists() && targetFile.length() > 0) {
                    android.util.Log.i("PhotoDetail", "downloadToCache: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                    targetFile
                } else {
                    android.util.Log.e("PhotoDetail", "downloadToCache: file empty for ${mediaFile.name}")
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoDetail", "downloadToCache failed for ${mediaFile.name}", e)
                null
            }
        }

    // ============================ 沉浸式全屏 ============================

    private fun setupImmersive() {
        hideSystemUi()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun showSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun toggleUi() {
        if (isUiVisible) hideUi() else showUi()
    }

    private fun hideUi() {
        isUiVisible = false
        binding.appBar.animate().translationY(-binding.appBar.height.toFloat())
            .alpha(0f).setDuration(200).start()
        binding.bottomBar.animate().translationY(binding.bottomBar.height.toFloat())
            .alpha(0f).setDuration(200).start()
        binding.tvPageIndicator.animate().alpha(0f).setDuration(200).withEndAction {
            binding.tvPageIndicator.visibility = View.GONE
        }.start()
        hideSystemUi()
    }

    private fun showUi() {
        isUiVisible = true
        binding.appBar.animate().translationY(0f).alpha(1f).setDuration(200).start()
        binding.bottomBar.animate().translationY(0f).alpha(1f).setDuration(200).start()
        binding.tvPageIndicator.visibility = View.VISIBLE
        binding.tvPageIndicator.animate().alpha(1f).setDuration(200).start()
        showSystemUi()
    }

    // ============================ UI 更新辅助 ============================

    private fun updateTitle() {
        val file = imageList.getOrNull(currentPosition)
        supportActionBar?.title = file?.name ?: ""
        supportActionBar?.subtitle = if (imageList.size > 1) {
            "${currentPosition + 1} / ${imageList.size}"
        } else null
    }

    /**
     * 更新页码指示器和底部操作栏的可见性
     *
     * 核心修复：
     * - bottomBar 在有内容时**始终可见**（不再限制 size > 1）
     * - 单张图时也显示保存/分享按钮
     */
    private fun updatePageIndicator() {
        if (imageList.isNotEmpty()) {
            // 页码指示器：多张图时显示
            binding.tvPageIndicator.text = "${currentPosition + 1} / ${imageList.size}"
            binding.tvPageIndicator.visibility = if (imageList.size > 1 && isUiVisible) View.VISIBLE else View.GONE
        } else {
            binding.tvPageIndicator.visibility = View.GONE
        }
        // 操作栏：只要有内容就始终显示（修复核心bug！之前要求 size > 1 才显示）
        binding.bottomBar.visibility =
            if (imageList.isNotEmpty() && isUiVisible) View.VISIBLE else View.GONE
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return "${bytes / 1024}KB"
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        return String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ============================ Menu ============================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_photo_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isUiVisible) hideSystemUi()
    }
}
