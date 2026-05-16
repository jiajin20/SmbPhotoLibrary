package com.example.smbphoto.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smbphoto.R
import com.example.smbphoto.data.model.PhotoAlbum
import com.example.smbphoto.data.model.SaveResult
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.data.model.UiState
import com.example.smbphoto.databinding.ActivityMainBinding
import com.example.smbphoto.glide.VideoThumbnailLoader
import com.example.smbphoto.smb.SmbManager
import com.example.smbphoto.ui.adapter.AlbumAdapter
import com.example.smbphoto.ui.adapter.PhotoAdapter
import com.example.smbphoto.ui.viewmodel.PhotoViewModel
import com.example.smbphoto.ui.viewmodel.ServerConfigViewModel
import com.example.smbphoto.update.UpdateChecker
import com.example.smbphoto.update.UpdateDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：家庭相册（全屏照片网格 + 时间轴）
 *
 * 两层级导航：
 * - 第一层：相簿网格（含图片的子目录）
 * - 第二层：图片网格（进入相簿后展示）+ 时间轴滚动条
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PhotoViewModel by viewModels()
    private val configViewModel: ServerConfigViewModel by viewModels()
    @Inject lateinit var smbManager: SmbManager

    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var photoAdapter: PhotoAdapter
    private var drawerToggle: ActionBarDrawerToggle? = null

    /** 当前连接的服务器配置列表 */
    private var serverConfigs: List<ServerConfig> = emptyList()
    /** 当前连接的服务器 ID */
    private var currentServerId: Long? = null
    /** 当前相簿列表（用于标题点击切换相簿） */
    private var currentAlbumList: List<PhotoAlbum> = emptyList()

    companion object {
        const val REQUEST_CODE_CONFIG = 1001
        const val REQUEST_CODE_MANAGE = 1002
        const val REQUEST_CODE_PHOTO_DETAIL = 2001
        private const val STATE_KEY_SERVER_ID = "key_server_id"
        private const val STATE_KEY_VIEW_MODE = "key_view_mode"
        private const val STATE_KEY_ALBUM_PATH = "key_album_path"
        private const val PREFS_NAME = "smbphoto_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch_done"
    }

    // 权限请求 Launcher
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            android.util.Log.i("MainActivity", "存储权限已授予")
        } else {
            android.util.Log.w("MainActivity", "存储权限部分或全部被拒绝")
            // 仍然继续，因为部分功能可能仍可用
        }
        // 权限获取后继续自动连接
        continueAfterPermissions()
    }

    // 安装未知应用权限请求 Launcher（跳转到设置页面）
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 用户从设置返回后检查是否已授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                android.util.Log.i("MainActivity", "安装未知应用权限已授予")
            } else {
                android.util.Log.w("MainActivity", "安装未知应用权限未授予")
            }
        }
    }

    /** 首次启动标记（用于跳过权限检查） */
    private var isFirstLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 初始化视频缩略图加载器（使用 SMB 流提取帧）
            VideoThumbnailLoader.init(this, smbManager)

            setupToolbar()
            setupRecyclerViews()
            setupSwipeRefresh()
            observeViewModel()

            if (savedInstanceState == null) {
                // 检查是否是首次启动
                checkFirstLaunch()
            } else {
                // 恢复状态
                currentServerId = savedInstanceState.getLong(STATE_KEY_SERVER_ID).takeIf { it != 0L }
                val viewMode = savedInstanceState.getString(STATE_KEY_VIEW_MODE) ?: "photos_list"
                val albumPath = savedInstanceState.getString(STATE_KEY_ALBUM_PATH)

                // viewMode == "album_detail" 表示正在查看某相簿内部的图片
                if (viewMode == "album_detail" && !albumPath.isNullOrBlank()) {
                    val restoredName = albumPath.substringAfterLast('\\').substringAfterLast('/')
                    viewModel.loadAlbumPhotos(albumPath, restoredName)
                    showPhotoView(restoredName)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onCreate failed", e)
            Toast.makeText(this, "界面初始化失败：${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * 检查是否是首次启动，并请求必要的权限
     */
    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true)

        if (isFirstLaunch) {
            android.util.Log.i("MainActivity", "首次启动，请求必要权限...")
            // 标记首次启动已完成（即使权限被拒绝也标记，避免每次都弹窗）
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
            // 请求存储和网络权限
            requestRequiredPermissions()
        } else {
            android.util.Log.i("MainActivity", "非首次启动，跳过权限请求")
            continueAfterPermissions()
        }
    }

    /**
     * 请求必要的运行时权限
     */
    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Android 13+ (API 33+) 需要 READ_MEDIA_IMAGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            // Android 14+ 还需要 POST_NOTIFICATIONS 用于通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Android 6-12 需要 READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            android.util.Log.i("MainActivity", "请求权限: $permissionsToRequest")
            storagePermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            android.util.Log.i("MainActivity", "权限已全部授予，继续执行")
            continueAfterPermissions()
        }
    }

    /**
     * 权限获取完成后继续执行后续操作
     */
    private fun continueAfterPermissions() {
        android.util.Log.i("MainActivity", "权限处理完成，开始后续操作...")
        // 检查安装未知应用权限
        checkInstallPermission()
        // 自动连接
        attemptAutoConnect()
        // 检查更新
        checkForUpdate()
    }

    /**
     * 检查并请求安装未知应用权限（Android 8.0+）
     */
    private fun checkInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                android.util.Log.w("MainActivity", "需要安装未知应用权限，显示引导对话框")
                showInstallPermissionDialog()
            } else {
                android.util.Log.i("MainActivity", "安装未知应用权限已授权")
            }
        }
    }

    /**
     * 显示安装未知应用权限引导对话框
     */
    private fun showInstallPermissionDialog() {
        // 只有在有更新或者需要安装时才提示，避免骚扰用户
        AlertDialog.Builder(this)
            .setTitle("需要安装权限")
            .setMessage("为了安装应用更新，需要您开启「安装未知应用」权限。\n\n点击确定将跳转到设置页面，找到本应用并开启「允许安装未知应用」。")
            .setPositiveButton("确定") { _, _ ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        intent.data = Uri.parse("package:$packageName")
                        installPermissionLauncher.launch(intent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "跳转到设置失败", e)
                    Toast.makeText(this, "无法打开设置，请手动开启安装权限", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("稍后") { _, _ ->
                android.util.Log.i("MainActivity", "用户选择稍后开启安装权限")
            }
            .setCancelable(true)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_KEY_SERVER_ID, currentServerId ?: 0L)
        val inAlbumView = viewModel.isInAlbumView()
        // 保存视图模式：photos_list=相簿层，album_detail=图片层
        outState.putString(STATE_KEY_VIEW_MODE, if (inAlbumView) "photos_list" else "album_detail")
        if (!inAlbumView) {
            // 保存当前相簿路径和名称，供旋转屏幕/重建时恢复
            outState.putString(STATE_KEY_ALBUM_PATH, viewModel.getCurrentAlbumPath())
            outState.putString("key_album_name", viewModel.getCurrentAlbumName())
        }
    }

    // ==================== UI 初始化 ====================

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
        // 确保 CollapsingToolbar 启用标题功能
        binding.collapsingToolbar.isTitleEnabled = true
        updateHomeIcon()
        // 点击标题区域弹出相簿切换下拉框（仅在图片层有效）
        binding.collapsingToolbar.setOnClickListener {
            if (!viewModel.isInAlbumView() && currentAlbumList.isNotEmpty()) {
                showAlbumSwitchPopup()
            }
        }
        // Toolbar 本身也响应点击
        binding.toolbar.setOnClickListener {
            if (!viewModel.isInAlbumView() && currentAlbumList.isNotEmpty()) {
                showAlbumSwitchPopup()
            }
        }
    }

    /** 根据当前层级切换首页按钮图标 */
    private fun updateHomeIcon() {
        val isAlbum = viewModel.isInAlbumView()
        binding.toolbar.navigationIcon = getDrawable(
            if (isAlbum) R.drawable.ic_menu_drawer
            else R.drawable.ic_arrow_back_white
        )
        binding.toolbar.contentDescription = if (isAlbum) "服务器菜单" else "返回相簿"
    }

    /**
     * 在 Toolbar 标题位置弹出相簿切换 PopupMenu
     */
    private fun showAlbumSwitchPopup() {
        if (currentAlbumList.isEmpty()) return
        try {
            val popup = PopupMenu(this, binding.toolbar, Gravity.TOP or Gravity.END)
            popup.menuInflater.inflate(R.menu.menu_album_switch, popup.menu)
            for ((index, album) in currentAlbumList.withIndex()) {
                popup.menu.add(0, index, index, "${album.name} (${album.photoCount}项)")
            }
            popup.setOnMenuItemClickListener { item ->
                val album = currentAlbumList.getOrNull(item.itemId)
                if (album != null) {
                    openAlbum(album)
                }
                true
            }
            popup.show()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to show album switch popup", e)
        }
    }

    private fun setupRecyclerViews() {
        // 相簿适配器
        albumAdapter = AlbumAdapter(
            onItemClick = { album -> openAlbum(album) },
            onItemLongClick = { album, anchorView -> showAlbumContextMenu(album, anchorView) }
        )
        binding.rvAlbums.apply {
            layoutManager = GridLayoutManager(this@MainActivity, calculateAlbumSpanCount())
            adapter = albumAdapter
            setHasFixedSize(true)
        }

        // 图片适配器
        photoAdapter = PhotoAdapter(
            onItemClick = { image, position -> openPhotoDetail(image, position) },
            onLongClick = { image, anchorView -> showPhotoContextMenu(image, anchorView) }
        )
        binding.rvPhotos.apply {
            layoutManager = GridLayoutManager(this@MainActivity, calculatePhotoSpanCount())
            adapter = photoAdapter
            setHasFixedSize(true)
            // 不再需要滚动监听器，所有图片一次性加载完成
        }
    }

    private fun calculateAlbumSpanCount(): Int = when {
        resources.configuration.smallestScreenWidthDp >= 840 -> 3
        resources.configuration.smallestScreenWidthDp >= 600 -> 2
        else -> 2
    }

    private fun calculatePhotoSpanCount(): Int = when {
        resources.configuration.smallestScreenWidthDp >= 840 -> 5
        resources.configuration.smallestScreenWidthDp >= 600 -> 4
        else -> 3
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
        binding.swipeRefresh.setColorSchemeColors(
            resources.getColor(R.color.primary, null),
            resources.getColor(R.color.accent, null)
        )
    }

    // ==================== ViewModel 观察 ====================

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        when (state) {
                            is UiState.Loading ->
                                showConnecting("正在连接服务器…")
                            is UiState.Success -> {
                                showConnected()
                            }
                            is UiState.Error -> {
                                showConnectionError(state.message)
                                launch {
                                    kotlinx.coroutines.delay(800)
                                    if (!isFinishing && !isDestroyed) {
                                        if (serverConfigs.isEmpty()) {
                                            openServerConfig(null)
                                        }
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }

                launch {
                    viewModel.albumList.collect { state ->
                        _binding?.let {
                            it.swipeRefresh.isRefreshing = false
                            when (state) {
                                is UiState.Loading -> showLoading()
                                is UiState.Success -> showAlbums(state.data)
                                is UiState.Empty -> showEmpty(getString(R.string.empty_albums_hint))
                                is UiState.Error -> showError(state.message)
                            }
                        }
                    }
                }

                launch {
                    viewModel.photoList.collect { state ->
                        _binding?.let {
                            it.swipeRefresh.isRefreshing = false
                            when (state) {
                                is UiState.Loading -> showLoading()
                                is UiState.Success -> showPhotos(state.data)
                                is UiState.Empty -> showEmpty(getString(R.string.empty_album_photos_hint))
                                is UiState.Error -> showError(state.message)
                            }
                        }
                    }
                }

                // 观察加载更多状态
                launch {
                    viewModel.isLoadingMoreFlow.collect { isLoading ->
                        _binding?.let {
                            if (isLoading) {
                                it.progressBar.visibility = View.VISIBLE
                                it.tvStatus.text = "正在加载更多..."
                                it.tvStatus.visibility = View.VISIBLE
                            } else {
                                it.tvStatus.visibility = View.GONE
                            }
                        }
                    }
                }

                launch {
                    viewModel.saveResult.collect { result ->
                        result ?: return@collect
                        when (result) {
                            is SaveResult.Success ->
                                Toast.makeText(this@MainActivity, getString(R.string.photo_saved, result.displayName), Toast.LENGTH_SHORT).show()
                            is SaveResult.Failure ->
                                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                        viewModel.consumeSaveResult()
                    }
                }
            }
        }
    }

    // ==================== 状态显示方法 ====================

    private fun showConnecting(message: String = "正在连接…") {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.tvStatus.visibility = View.VISIBLE
        hideLists()
        binding.layoutEmpty.visibility = View.GONE
        hideTimeline()
    }

    private fun showConnected() {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.visibility = View.GONE
    }

    private fun showConnectionError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "连接失败：$message"
        binding.tvStatus.visibility = View.VISIBLE
        hideLists()
        binding.layoutEmpty.visibility = View.GONE
        hideTimeline()
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.layoutEmpty.visibility = View.GONE
        hideLists()
        hideTimeline()
    }

    /**
     * 显示相簿视图（第一层）
     */
    private fun showAlbums(albums: List<PhotoAlbum>) {
        // 守卫：如果当前不是相簿层（例如正在查看图片），忽略此更新
        if (viewModel.isInAlbumView()) {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.visibility = View.GONE
            binding.layoutEmpty.visibility = View.GONE
            binding.rvPhotos.visibility = View.GONE
            binding.rvAlbums.visibility = View.VISIBLE
            albumAdapter.submitList(albums)
            // CollapsingToolbar 标题：相簿层显示应用名
            binding.collapsingToolbar.title = getString(R.string.app_name)
            supportActionBar?.subtitle = "${albums.size} 个相簿"
            hideTimeline()
            // 保存相簿列表供标题点击切换使用
            currentAlbumList = albums
            // 更新首页按钮图标（相簿层用菜单图标）
            updateHomeIcon()
        }
    }

    /**
     * 显示图片视图（第二层，进入相簿后）+ 时间轴
     */
    private fun showPhotos(photos: List<SmbImageFile>) {
        // 守卫：如果当前是相簿层（例如刚返回），忽略此更新避免覆盖标题
        if (!viewModel.isInAlbumView()) {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.visibility = View.GONE
            binding.layoutEmpty.visibility = View.GONE
            binding.rvAlbums.visibility = View.GONE
            binding.rvPhotos.visibility = View.VISIBLE
            photoAdapter.submitList(photos)
            val albumName = viewModel.getCurrentAlbumName()
            // 同时设置 CollapsingToolbar 和 Toolbar 标题，防止滚动折叠后标题丢失
            binding.collapsingToolbar.title = albumName.ifBlank { getString(R.string.all_albums) }
            supportActionBar?.title = albumName.ifBlank { getString(R.string.all_albums) }
            supportActionBar?.subtitle = "${photos.size} 项"
            // 构建时间轴
            buildTimeline(photos)
            // 更新首页按钮图标（图片层用返回图标）
            updateHomeIcon()
        }
    }

    /** 切换到图片视图模式（供状态恢复使用） */
    private fun showPhotoView(title: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.visibility = View.GONE
        binding.layoutEmpty.visibility = View.GONE
        binding.rvAlbums.visibility = View.GONE
        binding.rvPhotos.visibility = View.VISIBLE
        binding.collapsingToolbar.title = title
        supportActionBar?.subtitle = null
        // 状态恢复时若有当前照片列表，尝试重建时间轴
        val currentPhotos = try { photoAdapter.currentList } catch (e: Exception) { emptyList() }
        if (currentPhotos.isNotEmpty()) {
            buildTimeline(currentPhotos)
        }
        // 更新首页按钮图标（图片层用返回图标）
        updateHomeIcon()
    }

    /**
     * 构建时间轴快速定位条
     *
     * 从图片列表中提取日期分组，生成可点击的日期标签。
     * 点击日期标签可快速跳转到对应位置。
     * 时间优先使用 EXIF 拍摄时间（takenAt），其次使用文件修改时间（lastModified）。
     */
    private fun buildTimeline(photos: List<SmbImageFile>) {
        val container = binding.timelineContainer
        container.removeAllViews()

        if (photos.isEmpty()) {
            hideTimeline()
            return
        }

        // 按日期分组（优先使用 EXIF 拍摄时间，回退到文件修改时间）
        val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
        val monthFormat = SimpleDateFormat("MM月dd日", Locale.CHINA)
        val dateGroups = photos.groupBy { item ->
            val ts = item.bestTimestamp  // 优先 EXIF 拍摄时间
            if (ts > 0) {
                dateFormat.format(Date(ts))
            } else {
                "未知日期"
            }
        }.toSortedMap(compareByDescending { it }) // 最新的在前

        val inflater = layoutInflater
        val primaryColor = ContextCompat.getColor(this, R.color.primary)

        dateGroups.entries.forEachIndexed { index, entry ->
            val dateStr = entry.key
            val itemsInGroup: List<SmbImageFile> = entry.value
            val textView = inflater.inflate(
                R.layout.item_timeline_date, container, false
            ) as TextView

            // 显示简短格式（节省空间）；优先使用该组第一张的 EXIF 拍摄时间
            val timestamp = itemsInGroup.firstOrNull()?.bestTimestamp ?: 0L
            val displayTime = if (timestamp > 0) timestamp else System.currentTimeMillis()
            textView.text = monthFormat.format(Date(displayTime))

            // 第一个项高亮为当前选中色
            if (index == 0) {
                textView.setTextColor(primaryColor)
                textView.paint.isFakeBoldText = true
            }

            // 记录该组第一项在列表中的位置
            val firstPosition = photos.indexOfFirst { it == itemsInGroup.first() }.coerceAtLeast(0)

            textView.setOnClickListener {
                // 跳转到对应位置
                if (firstPosition >= 0 && binding.rvPhotos.adapter != null) {
                    val lm = binding.rvPhotos.layoutManager as? GridLayoutManager
                    if (lm != null) {
                        lm.scrollToPositionWithOffset(firstPosition, 0)
                    } else {
                        binding.rvPhotos.scrollToPosition(firstPosition)
                    }
                }
                // 高亮当前选中的日期标签
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i) as? TextView
                    child?.setTextColor(
                        if (i == index) primaryColor
                        else ContextCompat.getColor(this@MainActivity, R.color.on_surface_variant)
                    )
                    child?.paint?.isFakeBoldText = (i == index)
                }
            }

            container.addView(textView)
        }

        binding.timelineScroll.visibility = View.VISIBLE
    }

    private fun hideTimeline() {
        binding.timelineScroll.visibility = View.GONE
    }

    private fun showEmpty(hintText: String) {
        binding.progressBar.visibility = View.GONE
        hideLists()
        hideTimeline()
        binding.tvEmpty.text = hintText
        binding.layoutEmpty.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        hideLists()
        hideTimeline()
        binding.tvEmpty.text = "加载失败：$message"
        binding.layoutEmpty.visibility = View.VISIBLE
    }

    private fun hideLists() {
        binding.rvAlbums.visibility = View.GONE
        binding.rvPhotos.visibility = View.GONE
    }

    // ==================== 导航操作 ====================

    /** 点击相簿 → 加载该相簿内的照片 */
    private fun openAlbum(album: PhotoAlbum) {
        viewModel.loadAlbumPhotos(album.path, album.name)
    }

    /** 打开照片/视频详情（传入完整列表以支持左右滑动） */
    private fun openPhotoDetail(image: SmbImageFile, position: Int) {
        val currentList = try { photoAdapter.currentList } catch (e: Exception) { emptyList() }

        val intent = Intent(this, PhotoDetailActivity::class.java).apply {
            putExtra(PhotoDetailActivity.EXTRA_REMOTE_PATH, image.remotePath)
            putExtra(PhotoDetailActivity.EXTRA_FILE_NAME, image.name)
            putExtra(PhotoDetailActivity.EXTRA_POSITION, position.coerceAtLeast(0))
            putExtra(PhotoDetailActivity.EXTRA_FILE_SIZE, image.fileSize)
            if (currentList.isNotEmpty()) {
                putExtra(PhotoDetailActivity.EXTRA_IMAGE_LIST, ArrayList(currentList))
            }
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_PHOTO_DETAIL)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to open photo detail", e)
            Toast.makeText(this, "无法打开图片详情", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveDialog(image: SmbImageFile) {
        AlertDialog.Builder(this)
            .setTitle(image.name)
            .setMessage("是否将此文件保存到本地？")
            .setPositiveButton("保存") { _, _ ->
                viewModel.saveToGallery(image)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 相簿长按弹出操作菜单（复制、下载、删除、重命名）
     * @param album 被操作的相簿
     * @param anchorView 长按时所在的视图，用于正确定位菜单弹出位置
     */
    private fun showAlbumContextMenu(album: PhotoAlbum, anchorView: View) {
        try {
            val popup = PopupMenu(this, anchorView)
            popup.menuInflater.inflate(R.menu.menu_album_context, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_album_delete -> {
                        AlertDialog.Builder(this)
                            .setTitle("删除相簿")
                            .setMessage("确定要删除「${album.name}」吗？此操作不可恢复！")
                            .setPositiveButton("删除") { _, _ ->
                                viewModel.deleteAlbum(album.path)
                                Toast.makeText(this, "正在删除：${album.name}", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                        true
                    }
                    R.id.action_album_rename -> {
                        showRenameDialog(album.name, isAlbum = true, targetPath = album.path)
                        true
                    }
                    R.id.action_album_download -> {
                        Toast.makeText(this, "开始下载 ${album.name} 中的图片…", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to show album context menu", e)
        }
    }

    /**
     * 图片/视频长按弹出操作菜单（移动、删除、复制/保存）
     * @param image 被操作的文件
     * @param anchorView 长按时所在的视图，用于正确定位菜单弹出位置
     */
    private fun showPhotoContextMenu(image: SmbImageFile, anchorView: View) {
        try {
            val popup = PopupMenu(this, anchorView)
            popup.menuInflater.inflate(R.menu.menu_photo_context, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_photo_save -> {
                        viewModel.saveToGallery(image)
                        true
                    }
                    R.id.action_photo_delete -> {
                        AlertDialog.Builder(this)
                            .setTitle("删除文件")
                            .setMessage("确定要删除「${image.name}」吗？")
                            .setPositiveButton("删除") { _, _ ->
                                viewModel.deleteFile(image.remotePath)
                                Toast.makeText(this, "已删除：${image.name}", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                        true
                    }
                    R.id.action_photo_copy -> {
                        Toast.makeText(this, "复制功能：请选择目标相簿", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to show photo context menu", e)
        }
    }

    /**
     * 重命名对话框
     */
    private fun showRenameDialog(currentName: String, isAlbum: Boolean, targetPath: String) {
        val editText = android.widget.EditText(this).apply {
            setText(currentName)
            setSelection(0, currentName.length.coerceAtMost(text.length))
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(if (isAlbum) "重命名相簿" else "重命名文件")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName.contains("\\") || newName.contains("/") || newName.contains(":") ||
                    newName.contains("*") || newName.contains("?") || newName.contains("\"") ||
                    newName.contains("<") || newName.contains(">") || newName.contains("|")
                ) {
                    Toast.makeText(this, "名称包含非法字符", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == currentName) {
                    return@setPositiveButton
                }
                viewModel.renameFile(targetPath, newName)
                Toast.makeText(this, "正在重命名…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 服务器配置 ====================

    /** 尝试自动连接第一个已保存的服务器（仅在 onCreate 时执行一次） */
    private fun attemptAutoConnect() {
        lifecycleScope.launch {
            serverConfigs = configViewModel.getAllConfigs()

            if (serverConfigs.isNotEmpty()) {
                val firstConfig = serverConfigs.first()
                currentServerId = firstConfig.id
                android.util.Log.i("MainActivity", "Auto-connecting to ${firstConfig.serverIp}")
                showConnecting("正在连接 \\\\${firstConfig.serverIp}\\${firstConfig.shareName}…")
                viewModel.connect(firstConfig)
            } else {
                kotlinx.coroutines.delay(200)
                if (!isFinishing && !isDestroyed) {
                    openServerConfig(null)
                }
            }
        }
    }

    private fun openServerConfig(editConfig: ServerConfig?) {
        val intent = Intent(this, ServerConfigActivity::class.java)
        editConfig?.let {
            intent.putExtra(ServerConfigActivity.EXTRA_EDIT_CONFIG_ID, it.id)
        }
        startActivityForResult(intent, REQUEST_CODE_CONFIG)
    }

    private fun openManageServers() {
        val intent = Intent(this, ServerConfigActivity::class.java).apply {
            putExtra(ServerConfigActivity.EXTRA_MANAGE_MODE, true)
        }
        startActivityForResult(intent, REQUEST_CODE_MANAGE)
    }

    @Deprecated("Use ActivityResultContracts")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CONFIG || requestCode == REQUEST_CODE_MANAGE) {
            // 重新获取最新配置列表
            val newConfigs = configViewModel.getAllConfigs()
            val oldSize = serverConfigs.size
            serverConfigs = newConfigs

            if (resultCode == RESULT_OK && newConfigs.isNotEmpty()) {
                // 有新增或最新配置：连接最后一个（刚刚保存的）配置
                val latestConfig = newConfigs.last()
                currentServerId = latestConfig.id
                showConnecting("正在连接 \\\\${latestConfig.serverIp}\\${latestConfig.shareName}…")
                viewModel.connect(latestConfig)
            } else if (newConfigs.isNotEmpty() && currentServerId != null) {
                // 管理页面返回但没有变化，确保当前服务器仍连接
                val currentConfig = newConfigs.find { it.id == currentServerId }
                if (currentConfig != null && !viewModel.isConnected()) {
                    viewModel.connect(currentConfig)
                }
            }
            refreshDrawerServerList()
        }
        if (requestCode == REQUEST_CODE_PHOTO_DETAIL && resultCode == RESULT_OK) {
            val deletedPath = data?.getStringExtra("deleted_path")
            if (!deletedPath.isNullOrEmpty()) {
                viewModel.refresh()
            }
        }
    }

    /**
     * 刷新服务器配置列表（用于 Drawer / 菜单）
     * 注意：去掉 Drawer 后此方法保留兼容性，后续可改为弹出服务器选择对话框
     */
    private fun refreshDrawerServerList() {
        serverConfigs = configViewModel.getAllConfigs()
    }

    // ==================== Toolbar 菜单 ====================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // 动态控制刷新按钮的显示：仅在图片层显示
        menu.findItem(R.id.action_refresh)?.isVisible = !viewModel.isInAlbumView()

        // 自定义 actionLayout 需要手动绑定点击事件
        val refreshItem = menu.findItem(R.id.action_refresh)
        refreshItem?.actionView?.setOnClickListener {
            onOptionsItemSelected(refreshItem)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // 左侧按钮：图片层返回相簿层；相簿层弹出服务器菜单
                if (!viewModel.isInAlbumView()) {
                    viewModel.goBackToAlbums()
                } else {
                    showServerPopupMenu()
                }
                true
            }
            R.id.action_refresh -> {
                viewModel.refresh()
                true
            }
            R.id.action_connect -> {
                showServerPopupMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 弹出服务器管理 PopupMenu（替代原 Drawer 的服务器列表功能）
     *
     * 使用固定偏移量避免服务器索引与特殊菜单 ID 冲突：
     *   服务器列表 itemId = SERVER_ID_BASE + index (起始 2000)
     *   添加服务器 = 1000
     *   管理服务器 = 1001
     */
    private fun showServerPopupMenu() {
        try {
            val popup = PopupMenu(this, binding.toolbar, Gravity.TOP or Gravity.END)
            val allConfigs = configViewModel.getAllConfigs()
            if (allConfigs.isEmpty()) {
                Toast.makeText(this, "暂无服务器配置，请先添加", Toast.LENGTH_SHORT).show()
                openServerConfig(null)
                return
            }
            // 添加服务器列表（ID 从 2000 开始，避免与特殊 ID 1000/1001 冲突）
            val serverIdBase = 2000
            for ((index, cfg) in allConfigs.withIndex()) {
                val label = if (cfg.name.isNotBlank()) cfg.name else "${cfg.serverIp}/${cfg.shareName}"
                popup.menu.add(0, serverIdBase + index, index, label)
            }
            popup.menu.add(0, 1000, allConfigs.size, "➕ 添加服务器")
            popup.menu.add(0, 1001, allConfigs.size + 1, "⚙ 管理服务器")
            popup.setOnMenuItemClickListener { menuItem ->
                when {
                    menuItem.itemId == 1000 -> openServerConfig(null)
                    menuItem.itemId == 1001 -> openManageServers()
                    menuItem.itemId >= serverIdBase -> {
                        val cfgIndex = menuItem.itemId - serverIdBase
                        val cfg = allConfigs.getOrNull(cfgIndex)
                        if (cfg != null) {
                            currentServerId = cfg.id
                            showConnecting("正在切换至 ${cfg.serverIp}…")
                            viewModel.connect(cfg)
                        }
                    }
                }
                true
            }
            popup.show()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to show server menu", e)
        }
    }

    override fun onBackPressed() {
        // 如果当前在图片层（相簿详情），返回到相簿层
        if (!viewModel.isInAlbumView()) {
            // 立即切换 UI 状态，不等待 ViewModel 异步更新
            binding.rvPhotos.visibility = View.GONE
            binding.rvAlbums.visibility = View.VISIBLE
            hideTimeline()
            updateHomeIcon()
            // 更新标题
            binding.collapsingToolbar.title = getString(R.string.app_name)
            supportActionBar?.subtitle = null
            // 触发 ViewModel 加载
            viewModel.goBackToAlbums()
            return
        }
        // 相簿层：再次按返回键确认退出
        if (!isFinishing) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(getString(R.string.exit_confirm_message))
                .setPositiveButton(getString(R.string.exit_positive)) { _, _ ->
                    // 真正退出应用，结束所有线程和释放资源
                    finishAffinity()
                    System.exit(0)
                }
                .setNegativeButton(getString(R.string.exit_negative), null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // ==================== 软件更新 ====================

    /**
     * 检查软件更新
     * 每次打开 App 都检测，不限制频率
     */
    private fun checkForUpdate() {
        android.util.Log.i("MainActivity", "开始检查应用更新...")
        lifecycleScope.launch {
            UpdateChecker.checkUpdate(this@MainActivity) { updateInfo ->
                android.util.Log.i("MainActivity", "更新检查回调，updateInfo = $updateInfo")
                if (updateInfo != null && updateInfo.isNewer) {
                    android.util.Log.i("MainActivity", "发现新版本: ${updateInfo.versionName}")
                    // 显示更新对话框
                    showUpdateDialog(updateInfo)
                }
            }
        }
    }

    /**
     * 显示更新对话框
     */
    private fun showUpdateDialog(updateInfo: com.example.smbphoto.update.UpdateInfo) {
        try {
            val dialog = UpdateDialog(this, updateInfo)
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "显示更新对话框失败", e)
        }
    }
}
