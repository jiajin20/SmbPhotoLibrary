package com.example.smbphoto.ui.activity

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.smbphoto.R
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.databinding.ActivityPhotoDetailBinding
import com.github.chrisbanes.photoview.PhotoView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 图片详情界面：全屏图片查看器
 *
 * 功能：
 * - ViewPager2 左右滑动切换图片
 * - PhotoView 双指缩放 / 双击放大 / 平移
 * - 分享到其他应用
 * - 保存到本地相册
 * - 沉浸式体验（单击隐藏/显示 UI）
 * - 页码指示器 (1/120)
 */
@AndroidEntryPoint
class PhotoDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_PATH = "extra_remote_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_FILE_SIZE = "extra_file_size"
        /** 传入完整图片列表用于 ViewPager2 滑动浏览 */
        const val EXTRA_IMAGE_LIST = "extra_image_list"
    }

    private lateinit var binding: ActivityPhotoDetailBinding

    private var imageList: ArrayList<SmbImageFile> = arrayListOf()
    private var initialPosition: Int = 0

    /** 当前正在显示的图片 */
    private var currentImage: SmbImageFile? = null
    /** UI 控件是否可见 */
    private var isUiVisible: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)

        // 全屏沉浸式设置
        setupImmersive()

        setContentView(binding.root)

        // 解析参数
        parseIntent()

        // 设置 ViewPager2 + Toolbar + 按钮
        setupViewPager()
        setupToolbar()
        setupButtons()

        // 更新页码指示器
        updatePageIndicator()
    }

    private fun parseIntent() {
        val remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH) ?: run { finish(); return }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: remotePath.substringAfterLast("\\")
        val fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
        initialPosition = intent.getIntExtra(EXTRA_POSITION, 0)

        // 尝试获取完整列表（如果调用方传了的话）
        @Suppress("UNCHECKED_CAST")
        val list = intent.getSerializableExtra(EXTRA_IMAGE_LIST) as? ArrayList<SmbImageFile>

        if (!list.isNullOrEmpty()) {
            imageList = list
        } else {
            // 兼容旧模式：只有单张图
            imageList.add(
                SmbImageFile(name = fileName, remotePath = remotePath, fileSize = fileSize)
            )
        }

        if (imageList.isEmpty()) {
            finish()
            return
        }

        currentImage = imageList.getOrNull(initialPosition) ?: imageList[0]
    }

    /**
     * 配置沉浸式全屏：隐藏状态栏和导航栏
     */
    private fun setupImmersive() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        actionBar?.hide()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupImmersive()
        }
    }

    private fun setupViewPager() {
        val adapter = PhotoPagerAdapter(imageList) { position ->
            currentImage = imageList.getOrNull(position)
            updatePageIndicator()
            // 加载时短暂显示 progress bar
            binding.progressBar.visibility = View.VISIBLE
        }
        binding.viewPager.adapter = adapter
        // 从指定位置开始
        binding.viewPager.setCurrentItem(initialPosition, false)

        // 监听滑动 → 更新页码
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentImage = imageList.getOrNull(position)
                updatePageIndicator()
                supportActionBar?.title = currentImage?.name ?: ""
            }
        })

        // 单击屏幕切换 UI 显隐
        // （在每个 page 的 PhotoView 上设置了点击事件）
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = currentImage?.name ?: ""

        // 单击 Toolbar 区域也切换 UI 显隐
        binding.appBar.setOnClickListener { toggleUiVisibility() }
    }

    private fun setupButtons() {
        binding.btnShare.setOnClickListener {
            currentImage?.let { shareImage(it) }
        }
        binding.btnSave.setOnClickListener {
            currentImage?.let { saveToGallery(it) }
        }
    }

    private fun updatePageIndicator() {
        val pos = binding.viewPager.currentItem
        val total = imageList.size
        if (total > 1) {
            binding.tvPageIndicator.text = getString(R.string.photo_page_indicator, pos + 1, total)
            binding.tvPageIndicator.visibility = View.VISIBLE
        } else {
            binding.tvPageIndicator.visibility = View.GONE
        }
    }

    /** 切换 UI 控件显隐（沉浸式） */
    private fun toggleUiVisibility() {
        isUiVisible = !isUiVisible
        binding.appBar.visibility = if (isUiVisible) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (isUiVisible && imageList.size > 1) View.VISIBLE else View.GONE
        binding.tvPageIndicator.visibility = if (isUiVisible && imageList.size > 1) View.VISIBLE else View.GONE

        if (isUiVisible) {
            // 显示系统 UI
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            // 隐藏所有 UI
            setupImmersive()
        }
    }

    /** 确保底部操作栏在有多张图片且 UI 可见时显示 */
    fun ensureBottomBarVisible() {
        if (isUiVisible && imageList.size > 1) {
            binding.bottomBar.visibility = View.VISIBLE
        }
    }

    // ==================== 分享功能 ====================

    private fun shareImage(imageFile: SmbImageFile) {
        Toast.makeText(this, R.string.sharing, Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val uri = withContext(Dispatchers.IO) { downloadToCache(imageFile) }
                    ?: run {
                        Toast.makeText(this@PhotoDetailActivity,
                            getString(R.string.share_failed, "无法下载图片"), Toast.LENGTH_LONG).show()
                        return@launch
                    }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_photo)))
            } catch (e: Exception) {
                Toast.makeText(this@PhotoDetailActivity,
                    getString(R.string.share_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 将远程图片下载到应用缓存目录，返回 content:// URI 用于分享 */
    private suspend fun downloadToCache(imageFile: SmbImageFile): Uri? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(cacheDir, "share_temp").apply { mkdirs() }
            val cacheFile = File(cacheDir, imageFile.name)

            // 通过 Glide 下载原始图片到缓存文件
            val futureTarget = Glide.with(this@PhotoDetailActivity)
                .asFile()
                .load(imageFile)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .submit()

            val downloadedFile = futureTarget.get()

            // 复制到 share_temp 目录
            downloadedFile.copyTo(cacheFile, overwrite = true)
            futureTarget.cancel(true)

            // 使用 FileProvider 生成 URI
            FileProvider.getUriForFile(
                this@PhotoDetailActivity,
                "${applicationId}.fileprovider",
                cacheFile
            )
        } catch (e: Exception) {
            android.util.Log.e("PhotoDetail", "Failed to download for sharing", e)
            null
        }
    }

    // ==================== 保存功能 ====================

    private fun saveToGallery(imageFile: SmbImageFile) {
        // 直接通过 MediaStoreHelper 保存（复用已有逻辑）
        val intent = Intent("com.example.smbphoto.ACTION_SAVE_TO_GALLERY").apply {
            putExtra(EXTRA_REMOTE_PATH, imageFile.remotePath)
            putExtra(EXTRA_FILE_NAME, imageFile.name)
            putExtra(EXTRA_FILE_SIZE, imageFile.fileSize)
        }
        setResult(RESULT_OK, intent)
        // 注意：实际保存由 MainActivity 的 ViewModel 处理，
        // 这里通过 setResult + EventBus 或直接调用 Repository 实现
        // 简化方案：直接用 Glide + MediaStoreHelper
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val futureTarget = Glide.with(this@PhotoDetailActivity)
                        .asFile()
                        .load(imageFile)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .submit()
                    futureTarget.get()
                }
                // 复制到 Pictures 目录并通知 MediaStore
                val picturesDir = File(getExternalFilesDir(null), "Pictures")
                picturesDir.mkdirs()
                val destFile = File(picturesDir, imageFile.name)
                file.copyTo(destFile, overwrite = true)

                // 通知媒体扫描器
                val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                    data = Uri.fromFile(destFile)
                }
                sendBroadcast(scanIntent)

                Toast.makeText(this@PhotoDetailActivity,
                    getString(R.string.photo_saved, imageFile.name), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PhotoDetailActivity,
                    getString(R.string.photo_save_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 菜单 ====================

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理缓存中的临时分享文件
        try {
            File(cacheDir, "share_temp")?.deleteRecursively()
        } catch (_: Exception) {}
    }

    // ==================== ViewPager2 Adapter ====================

    /**
     * ViewPager2 适配器，每页一个 PhotoView
     */
    inner class PhotoPagerAdapter(
        private val images: List<SmbImageFile>,
        private val onPageChanged: (Int) -> Unit
    ) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val photoView: PhotoView = itemView as PhotoView
            val progressBar: ProgressBar = itemView.findViewById(R.id.progressItem)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val photoView = PhotoView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PhotoView.ScaleType.FIT_CENTER
                maximumScale = 6f
                mediumScale = 3f

                // 单击切换 UI 显隐
                setOnClickListener { toggleUiVisibility() }
            }

            // 包装为带进度条的 FrameLayout
            val frameLayout = android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
               .addView(photoView, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                addView(android.widget.ProgressBar(parent.context).apply {
                    id = R.id.progressItem
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = android.view.Gravity.CENTER }
                    indeterminateTint = android.content.res.ColorStateList.valueOf(-0x1)
                })
            }
            return PhotoViewHolder(frameLayout)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            val image = images[position]
            holder.progressBar.visibility = View.VISIBLE

            Glide.with(this@PhotoDetailActivity)
                .load(image)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .error(R.drawable.ic_image_broken)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.progressBar.visibility = View.GONE
                        binding.progressBar.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.progressBar.visibility = View.GONE
                        binding.progressBar.visibility = View.GONE
                        ensureBottomBarVisible()
                        return false
                    }
                })
                .into(holder.photoView)
        }

        override fun getItemCount(): Int = images.size
    }
}
