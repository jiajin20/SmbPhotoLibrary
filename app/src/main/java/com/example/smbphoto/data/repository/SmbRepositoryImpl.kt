package com.example.smbphoto.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smbphoto.data.model.PhotoAlbum
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.smb.ExifIndexManager
import com.example.smbphoto.smb.MediaStoreHelper
import com.example.smbphoto.smb.SmbEntry
import com.example.smbphoto.smb.SmbManager
import com.example.smbphoto.smb.SmbManager.ConnectionClosedException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SmbRepositoryImpl"

/**
 * 连接断开异常，用于通知 ViewModel 需要重连
 */
class ConnectionLostException(message: String = "SMB 连接已断开") : Exception(message)

/**
 * SmbRepository 的生产实现
 *
 * 协调 SmbManager 与 MediaStoreHelper 完成数据操作。
 */
@Singleton
class SmbRepositoryImpl @Inject constructor(
    private val smbManager: SmbManager,
    private val mediaStoreHelper: MediaStoreHelper,
    @ApplicationContext private val context: Context
) : SmbRepository {

    /** EXIF 索引管理器（按需延迟初始化，避免 ApplicationContext 在早期不可用） */
    private val exifIndexManager: ExifIndexManager by lazy {
        ExifIndexManager(context)
    }

    override suspend fun connect(config: ServerConfig): Result<Unit> =
        smbManager.connect(config)

    override suspend fun listShares(): List<String> =
        withContext(Dispatchers.IO) {
            try {
                smbManager.listShares()
            } catch (e: Exception) {
                Log.e(TAG, "listShares failed", e)
                throw ConnectionLostException("获取共享目录列表失败：${e.message}")
            }
        }

    /**
     * 检测连接状态，如果断开则抛出 ConnectionLostException
     * 让 ViewModel 知道需要重连
     */
    private fun checkConnection() {
        if (!smbManager.isConnected) {
            throw ConnectionLostException("SMB 连接已断开，请重新连接")
        }
    }

    /**
     * 捕获 SMB 异常并转换为 ConnectionLostException
     */
    private inline fun <T> catchSmbException(action: () -> T): T {
        return try {
            action()
        } catch (e: ConnectionClosedException) {
            throw ConnectionLostException("SMB 连接已断开: ${e.failedPath}")
        } catch (e: Exception) {
            // 检测是否是与连接相关的异常
            val msg = e.message ?: ""
            val causeMsg = e.cause?.message ?: ""
            if (msg.contains("closed") || msg.contains("DiskShare") ||
                causeMsg.contains("closed") || causeMsg.contains("DiskShare") ||
                e is com.hierynomus.smbj.common.SMBRuntimeException) {
                Log.w(TAG, "SMB connection related exception: $msg")
                throw ConnectionLostException("SMB 连接异常：$msg")
            }
            throw e
        }
    }

    override suspend fun listEntries(path: String): List<SmbEntry> {
        checkConnection()
        return catchSmbException { smbManager.listEntries(path) }
    }

    override suspend fun listImageFiles(path: String): List<SmbImageFile> {
        checkConnection()
        return catchSmbException { smbManager.listImageFiles(path) }
    }

    override suspend fun listAlbums(path: String): List<PhotoAlbum> {
        checkConnection()
        return catchSmbException { smbManager.listAlbums(path) }
    }

    override suspend fun listImagesInAlbum(albumPath: String): List<SmbImageFile> {
        checkConnection()
        return catchSmbException { smbManager.listImagesInAlbum(albumPath) }
    }

    override suspend fun listImagesFromCache(albumPath: String): List<SmbImageFile> {
        val serverIp = smbManager.serverIp ?: return emptyList()
        val shareName = smbManager.shareName ?: return emptyList()
        // 直接从 Room 数据库返回所有图片（毫秒级）
        return exifIndexManager.getAllImages(serverIp, shareName, albumPath)
    }

    override suspend fun listImagesInAlbumWithIndex(
        albumPath: String,
        progressCallback: ExifIndexManager.ProgressCallback?
    ): List<SmbImageFile> {
        checkConnection()
        val serverIp = smbManager.serverIp ?: throw ConnectionLostException("服务器信息不可用")
        val shareName = smbManager.shareName ?: throw ConnectionLostException("共享目录信息不可用")
        return catchSmbException {
            smbManager.listImagesInAlbumWithIndex(albumPath, exifIndexManager, serverIp, shareName, progressCallback)
        }
    }

    override suspend fun listImagesInAlbumPaged(albumPath: String, page: Int): List<SmbImageFile> {
        checkConnection()
        val serverIp = smbManager.serverIp ?: throw ConnectionLostException("服务器信息不可用")
        val shareName = smbManager.shareName ?: throw ConnectionLostException("共享目录信息不可用")

        // 先尝试从缓存获取
        val cachedImages = exifIndexManager.getImagesPaged(serverIp, shareName, albumPath, page)
        if (cachedImages.isNotEmpty()) {
            Log.d(TAG, "Loaded $page page from cache: ${cachedImages.size} images")
            return cachedImages
        }

        // 缓存为空，需要从 SMB 扫描并建立索引
        Log.d(TAG, "Cache empty, need to scan album from SMB: $albumPath")
        val callback = object : ExifIndexManager.ProgressCallback {
            override fun onProgress(current: Int, total: Int, currentFile: String) {
                Log.d(TAG, "Indexing progress: $current/$total - $currentFile")
            }
            override fun onComplete(total: Int) {
                Log.d(TAG, "Indexing complete: $total images")
            }
        }
        return smbManager.listImagesInAlbumWithIndex(albumPath, exifIndexManager, serverIp, shareName, callback)
    }

    override suspend fun getAlbumImageCount(albumPath: String): Int {
        val serverIp = smbManager.serverIp ?: return 0
        val shareName = smbManager.shareName ?: return 0
        return exifIndexManager.getImageCount(serverIp, shareName, albumPath)
    }

    override suspend fun openInputStream(remotePath: String): InputStream =
        withContext(Dispatchers.IO) {
            checkConnection()
            smbManager.openInputStream(remotePath)
        }

    override suspend fun saveToGallery(imageFile: SmbImageFile): Uri? =
        mediaStoreHelper.saveImageToGallery(imageFile)

    override fun disconnect() = smbManager.close()

    override suspend fun deleteFile(remotePath: String): Boolean =
        smbManager.deleteRemoteFile(remotePath)

    override suspend fun deleteFileWithCache(remotePath: String, albumPath: String): Boolean {
        val serverIp = smbManager.serverIp ?: return false
        val shareName = smbManager.shareName ?: return false

        // 1. 删除 SMB 上的文件
        val deleted = smbManager.deleteRemoteFile(remotePath)

        // 2. 清理 Room 缓存索引，确保 UI 刷新时不再显示已删除的图片
        if (deleted) {
            exifIndexManager.deleteFile(serverIp, shareName, albumPath, remotePath)
        }

        return deleted
    }

    override suspend fun renameFile(remotePath: String, newName: String): Boolean =
        smbManager.renameRemoteFile(remotePath, newName)

    override suspend fun copyFile(sourcePath: String, destPath: String): Boolean =
        smbManager.copyRemoteFile(sourcePath, destPath)

    override suspend fun deleteDirectory(dirPath: String): Boolean =
        smbManager.deleteRemoteDirectory(dirPath)
}
