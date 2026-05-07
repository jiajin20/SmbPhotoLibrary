package com.example.smbphoto.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smbphoto.data.model.PhotoAlbum
import com.example.smbphoto.data.model.SaveResult
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.data.model.UiState
import com.example.smbphoto.data.repository.SmbRepository
import com.example.smbphoto.smb.SmbEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "PhotoViewModel"

/**
 * 相片库主 ViewModel
 *
 * 管理两层导航：
 * - 相簿层 (albumList)：展示含图片的子目录
 * - 图片层 (photoList)：展示某相簿内的图片
 */
@HiltViewModel
class PhotoViewModel @Inject constructor(
    private val repository: SmbRepository
) : ViewModel() {

    // ---- 连接状态 ----
    private val _connectionState = MutableStateFlow<UiState<Unit>>(UiState.Empty)
    val connectionState: StateFlow<UiState<Unit>> = _connectionState.asStateFlow()

    // ---- 相簿列表（第一层） ----
    private val _albumList = MutableStateFlow<UiState<List<PhotoAlbum>>>(UiState.Empty)
    val albumList: StateFlow<UiState<List<PhotoAlbum>>> = _albumList.asStateFlow()

    // ---- 图片列表（第二层，进入相簿后） ----
    private val _photoList = MutableStateFlow<UiState<List<SmbImageFile>>>(UiState.Empty)
    val photoList: StateFlow<UiState<List<SmbImageFile>>> = _photoList.asStateFlow()

    // ---- 保存到相册的结果 ----
    private val _saveResult = MutableStateFlow<SaveResult?>(null)
    val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

    // ---- 可用共享目录列表 ----
    private val _shareList = MutableStateFlow<List<String>>(emptyList())
    val shareList: StateFlow<List<String>> = _shareList.asStateFlow()

    // 当前浏览路径
    private var currentPath: String = ""

    /** 当前是否处于相簿视图（true=相簿层, false=图片层） */
    private var isAlbumView: Boolean = true

    /** 当前正在查看的相簿路径 */
    private var currentAlbumPath: String? = null

    /** 当前正在查看的相簿名称（缓存，避免路径解析失败） */
    private var currentAlbumName: String = ""

    /**
     * 连接到 SMB 服务器并自动开始加载相簿
     */
    fun connect(config: ServerConfig) {
        viewModelScope.launch {
            _connectionState.value = UiState.Loading
            currentPath = config.rootPath
            isAlbumView = true
            currentAlbumPath = null
            val result = repository.connect(config)
            if (result.isSuccess) {
                _connectionState.value = UiState.Success(Unit)
                loadAlbums(config.rootPath)
            } else {
                val msg = result.exceptionOrNull()?.message ?: "连接失败"
                _connectionState.value = UiState.Error(msg, result.exceptionOrNull())
                Log.e(TAG, "Connection failed: $msg")
            }
        }
    }

    /**
     * 加载指定路径下的相簿列表（第一层）
     * @param path 相对路径，默认使用 currentPath（防止覆盖）
     */
    fun loadAlbums(path: String = currentPath) {
        // 只有传入非空路径时才更新 currentPath
        if (path.isNotEmpty()) {
            currentPath = path
        }
        isAlbumView = true
        currentAlbumPath = null
        viewModelScope.launch {
            _albumList.value = UiState.Loading
            try {
                val albums = repository.listAlbums(currentPath)
                _albumList.value = if (albums.isEmpty()) UiState.Empty else UiState.Success(albums)
                Log.d(TAG, "Loaded ${albums.size} albums from path='$currentPath'")
            } catch (e: Exception) {
                _albumList.value = UiState.Error("加载相簿失败：${e.message}", e)
                Log.e(TAG, "Failed to load albums", e)
            }
        }
    }

    /**
     * 加载指定相簿内的图片列表（第二层）
     *
     * @param albumPath 相簿/子目录的相对路径
     * @param albumName 相簿名称（避免路径解析失败）
     */
    fun loadAlbumPhotos(albumPath: String, albumName: String = "") {
        currentAlbumPath = albumPath
        currentAlbumName = albumName.ifBlank {
            albumPath.substringAfterLast('\\').substringAfterLast('/')
        }
        isAlbumView = false
        viewModelScope.launch {
            _photoList.value = UiState.Loading
            try {
                val files = repository.listImagesInAlbum(albumPath)
                _photoList.value = if (files.isEmpty()) UiState.Empty else UiState.Success(files)
                Log.d(TAG, "Loaded ${files.size} images from album='$albumPath'")
            } catch (e: Exception) {
                _photoList.value = UiState.Error("加载图片失败：${e.message}", e)
                Log.e(TAG, "Failed to load album photos", e)
            }
        }
    }

    /**
     * 加载当前路径下的所有图片（扁平模式，兼容旧逻辑）
     */
    fun loadPhotos(path: String = "") {
        currentPath = path
        isAlbumView = false
        viewModelScope.launch {
            _photoList.value = UiState.Loading
            try {
                val files = repository.listImageFiles(path)
                _photoList.value = if (files.isEmpty()) UiState.Empty else UiState.Success(files)
                Log.d(TAG, "Loaded ${files.size} images from path='$path'")
            } catch (e: Exception) {
                _photoList.value = UiState.Error("加载图片失败：${e.message}", e)
                Log.e(TAG, "Failed to load photos", e)
            }
        }
    }

    /** 返回相簿层并重新加载相簿列表 */
    fun goBackToAlbums() {
        isAlbumView = true
        currentAlbumPath = null
        loadAlbums() // 自动使用 currentPath 重新加载
    }

    /** 当前是否在相簿层 */
    fun isInAlbumView(): Boolean = isAlbumView

    /** 当前 SMB 是否已连接（用于判断是否需要重连） */
    fun isConnected(): Boolean = connectionState.value is UiState.Success

    /** 获取当前相簿名称（用于标题显示） */
    fun getCurrentAlbumName(): String {
        // 优先用显式缓存的相簿名，路径解析作为兜底
        if (currentAlbumName.isNotBlank()) return currentAlbumName
        val path = currentAlbumPath ?: return ""
        return path.substringAfterLast('\\').substringAfterLast('/')
    }

    /** 获取当前相簿完整路径（用于状态保存/恢复） */
    fun getCurrentAlbumPath(): String = currentAlbumPath ?: ""

    /** 刷新当前视图 */
    fun refresh() {
        if (isAlbumView) {
            loadAlbums(currentPath)
        } else if (currentAlbumPath != null) {
            loadAlbumPhotos(currentAlbumPath!!)
        } else {
            loadPhotos(currentPath)
        }
    }

    /** 浏览指定路径下的目录条目（用于目录选择器） */
    suspend fun browsePath(path: String): List<SmbEntry> {
        return try {
            repository.listEntries(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to browse path: $path", e)
            emptyList()
        }
    }

    /** 获取可用共享目录 */
    fun fetchShares() {
        viewModelScope.launch {
            _shareList.value = repository.listShares()
        }
    }

    /** 将指定图片保存到系统相册 */
    fun saveToGallery(imageFile: SmbImageFile) {
        viewModelScope.launch {
            val uri = repository.saveToGallery(imageFile)
            _saveResult.value = if (uri != null) {
                SaveResult.Success(uri, imageFile.name)
            } else {
                SaveResult.Failure("保存失败，请检查存储权限")
            }
        }
    }

    /** 消费保存结果（避免重复弹窗） */
    fun consumeSaveResult() {
        _saveResult.value = null
    }

    /**
     * 删除远程文件
     * @return 操作结果（成功/失败消息）
     */
    fun deleteFile(remotePath: String) {
        if (remotePath.isBlank()) {
            Log.w(TAG, "deleteFile: blank path, ignoring")
            return
        }
        viewModelScope.launch {
            repository.deleteFile(remotePath)  // 结果由 refresh() 隐式反映
            // 刷新当前视图
            refresh()
        }
    }

    /**
     * 重命名远程文件/目录
     */
    fun renameFile(remotePath: String, newName: String) {
        if (remotePath.isBlank() || newName.isBlank()) {
            Log.w(TAG, "renameFile: blank path or name, ignoring")
            return
        }
        viewModelScope.launch {
            repository.renameFile(remotePath, newName)
            refresh()
        }
    }

    /**
     * 复制远程文件到目标路径
     */
    fun copyFile(sourcePath: String, destPath: String) {
        if (sourcePath.isBlank() || destPath.isBlank()) {
            Log.w(TAG, "copyFile: blank path, ignoring")
            return
        }
        viewModelScope.launch {
            repository.copyFile(sourcePath, destPath)
        }
    }

    /**
     * 删除整个相簿目录
     */
    fun deleteAlbum(albumPath: String) {
        if (albumPath.isBlank()) {
            Log.w(TAG, "deleteAlbum: blank path, ignoring")
            return
        }
        viewModelScope.launch {
            repository.deleteDirectory(albumPath)
            loadAlbums(currentPath)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}
