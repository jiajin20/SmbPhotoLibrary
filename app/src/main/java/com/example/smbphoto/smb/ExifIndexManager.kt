package com.example.smbphoto.smb

import android.content.Context
import android.util.Log
import com.example.smbphoto.data.local.AppDatabase
import com.example.smbphoto.data.local.ImageIndex
import com.example.smbphoto.data.model.SmbImageFile

private const val TAG = "ExifIndexManager"

/**
 * EXIF 索引管理器（基于 Room 数据库）
 *
 * 工作流程：
 * 1. 首次进入相簿：从 SMB 扫描所有图片，建立索引（显示进度）
 * 2. 再次进入相簿：直接从 Room 查询（毫秒级），后台增量同步
 * 3. 增量同步：比对 SMB 文件列表和数据库，检测新增/删除/修改
 *
 * @param context Application Context
 */
class ExifIndexManager(context: Context) {

    private val database = AppDatabase.getInstance(context)
    private val dao = database.imageIndexDao()

    /** 每页加载数量 */
    companion object {
        const val PAGE_SIZE = 50
    }

    /**
     * 进度回调接口
     */
    interface ProgressCallback {
        /** 进度更新 */
        fun onProgress(current: Int, total: Int, currentFile: String)

        /** 完成回调 */
        fun onComplete(total: Int)
    }

    /**
     * 分页获取图片（从缓存）
     *
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param albumPath 相簿路径
     * @param page 页码（从 0 开始）
     * @return SmbImageFile 列表（按拍摄时间倒序）
     */
    suspend fun getImagesPaged(
        serverIp: String,
        shareName: String,
        albumPath: String,
        page: Int
    ): List<SmbImageFile> {
        val offset = page * PAGE_SIZE
        val indices = dao.getImagesPaged(serverIp, shareName, albumPath, PAGE_SIZE, offset)
        return indices.map { it.toSmbImageFile() }
    }

    /**
     * 获取图片总数（从缓存）
     *
     * @return 图片总数
     */
    suspend fun getImageCount(
        serverIp: String,
        shareName: String,
        albumPath: String
    ): Int = dao.getImageCount(serverIp, shareName, albumPath)

    /**
     * 获取所有图片（从缓存，一次性加载）
     *
     * 第二次进入相簿时使用，直接从 Room 数据库返回所有图片（按拍摄时间倒序）
     *
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param albumPath 相簿路径
     * @return SmbImageFile 列表（按拍摄时间倒序）
     */
    suspend fun getAllImages(
        serverIp: String,
        shareName: String,
        albumPath: String
    ): List<SmbImageFile> {
        val indices = dao.getAllImages(serverIp, shareName, albumPath)
        return indices.map { it.toSmbImageFile() }
    }

    /**
     * 获取单张图片的缓存拍摄时间
     *
     * @return 拍摄时间，如果未缓存返回 null
     */
    suspend fun getCachedTakenAt(
        serverIp: String,
        shareName: String,
        albumPath: String,
        filePath: String
    ): Long? = dao.getCachedTakenAt(serverIp, shareName, albumPath, filePath)

    /**
     * 批量获取多个文件的拍摄时间
     *
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param albumPath 相簿路径
     * @param filePaths 文件路径列表
     * @return Map<filePath, takenAt>
     */
    suspend fun getCachedTakenAts(
        serverIp: String,
        shareName: String,
        albumPath: String,
        filePaths: List<String>
    ): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (path in filePaths) {
            val takenAt = dao.getCachedTakenAt(serverIp, shareName, albumPath, path)
            if (takenAt != null) {
                result[path] = takenAt
            }
        }
        return result
    }

    /**
     * 批量保存拍摄时间
     *
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param albumPath 相簿路径
     * @param takenAts Map<filePath, takenAt>
     */
    suspend fun saveTakenAts(
        serverIp: String,
        shareName: String,
        albumPath: String,
        takenAts: Map<String, Long>
    ) {
        if (takenAts.isEmpty()) return
        val now = System.currentTimeMillis()
        val indices = takenAts.map { (path, takenAt) ->
            ImageIndex(
                serverIp = serverIp,
                shareName = shareName,
                albumPath = albumPath,
                filePath = path,
                fileName = path.substringAfterLast('\\').substringAfterLast('/'),
                fileSize = 0L,
                lastModified = 0L,
                takenAt = takenAt,
                indexedAt = now
            )
        }
        dao.insertAll(indices)
    }

    /**
     * 建立/重建相簿索引
     *
     * @param albumPath 相簿路径
     * @param files 从 SMB 扫描到的所有图片
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @param callback 进度回调（可为空）
     */
    suspend fun indexAlbum(
        albumPath: String,
        files: List<SmbImageFile>,
        serverIp: String,
        shareName: String,
        callback: ProgressCallback? = null
    ) {
        if (files.isEmpty()) {
            callback?.onComplete(0)
            return
        }

        val total = files.size
        val now = System.currentTimeMillis()

        // 分批处理，每 20 个文件保存一次
        val batchSize = 20
        files.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            val imageIndices = batch.map { file ->
                ImageIndex(
                    serverIp = serverIp,
                    shareName = shareName,
                    albumPath = albumPath,
                    filePath = file.remotePath,
                    fileName = file.name,
                    fileSize = file.fileSize,
                    lastModified = file.lastModified,
                    takenAt = file.takenAt,
                    indexedAt = now
                )
            }

            dao.insertAll(imageIndices)

            val processed = minOf((batchIndex + 1) * batchSize, total)
            callback?.onProgress(processed, total, batch.lastOrNull()?.name ?: "")
        }

        callback?.onComplete(total)
        Log.i(TAG, "Indexed $total images for album: $albumPath")
    }

    /**
     * 删除单张图片的索引（文件被删除时同步清理缓存）
     */
    suspend fun deleteFile(serverIp: String, shareName: String, albumPath: String, filePath: String) {
        dao.deleteByPath(serverIp, shareName, albumPath, filePath)
    }

    /**
     * 增量同步：比对 SMB 文件列表和数据库
     *
     * @param albumPath 相簿路径
     * @param currentFiles 当前 SMB 文件列表
     * @param serverIp 服务器 IP
     * @param shareName 共享目录名
     * @return SyncResult 包含新增、删除、修改的数量
     */
    suspend fun syncWithSmb(
        albumPath: String,
        currentFiles: List<SmbImageFile>,
        serverIp: String,
        shareName: String
    ): SyncResult {
        val currentPaths = currentFiles.map { it.remotePath }.toSet()
        val cachedPaths = dao.getAllFilePaths(serverIp, shareName, albumPath).toSet()

        // 检测新增的文件
        val newPaths = currentPaths - cachedPaths
        // 检测已删除的文件
        val deletedPaths = cachedPaths - currentPaths

        var newCount = 0
        var deletedCount = 0

        // 插入新文件
        if (newPaths.isNotEmpty()) {
            val newFiles = currentFiles.filter { it.remotePath in newPaths }
            val now = System.currentTimeMillis()
            val indices = newFiles.map { file ->
                ImageIndex(
                    serverIp = serverIp,
                    shareName = shareName,
                    albumPath = albumPath,
                    filePath = file.remotePath,
                    fileName = file.name,
                    fileSize = file.fileSize,
                    lastModified = file.lastModified,
                    takenAt = file.takenAt,
                    indexedAt = now
                )
            }
            dao.insertAll(indices)
            newCount = indices.size
        }

        // 删除不存在的文件
        for (path in deletedPaths) {
            dao.deleteByPath(serverIp, shareName, albumPath, path)
            deletedCount++
        }

        Log.i(TAG, "Sync result for $albumPath: +$newCount, -$deletedCount")
        return SyncResult(newCount, deletedCount, 0)
    }

    /**
     * 删除相簿索引（相簿被删除时调用）
     */
    suspend fun deleteAlbum(serverIp: String, shareName: String, albumPath: String) {
        dao.deleteAlbum(serverIp, shareName, albumPath)
    }

    /**
     * 删除服务器索引（断开连接时调用）
     */
    suspend fun deleteServer(serverIp: String) {
        dao.deleteServer(serverIp)
    }

    /**
     * 将 ImageIndex 转换为 SmbImageFile
     */
    private fun ImageIndex.toSmbImageFile() = SmbImageFile(
        name = fileName,
        remotePath = filePath,
        fileSize = fileSize,
        lastModified = lastModified,
        takenAt = takenAt
    )

    /**
     * 同步结果
     */
    data class SyncResult(
        val newCount: Int,
        val deletedCount: Int,
        val modifiedCount: Int
    )
}
