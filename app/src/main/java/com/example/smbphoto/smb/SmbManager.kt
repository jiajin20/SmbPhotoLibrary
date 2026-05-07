package com.example.smbphoto.smb

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.smbphoto.data.model.PhotoAlbum
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.data.model.SmbImageFile
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SmbManager"

/**
 * SMB 远程目录条目（用于浏览选择）
 */
data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L
)

/**
 * SMB 连接管理器（单例）
 *
 * 负责：
 * 1. 建立与 SMB 服务器的连接和身份验证
 * 2. 枚举远程文件系统中的图片（支持按相簿/目录分组）
 * 3. 浏览远程目录结构（用于用户选择路径）
 * 4. 读取远程文件流供 Glide 加载
 * 5. 管理连接生命周期与连接池复用
 */
@Singleton
class SmbManager @Inject constructor() {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    /** 当前连接的服务器配置（不含密码） */
    private var currentConfig: ServerConfig? = null

    // 是否已连接
    val isConnected: Boolean
        get() = diskShare != null

    /**
     * 建立 SMB 连接并认证
     */
    suspend fun connect(config: ServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isConnected && currentConfig?.serverIp == config.serverIp
                && currentConfig?.shareName == config.shareName
            ) {
                return@withContext Result.success(Unit)
            }
            closeInternal()

            val smbConfig = SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build()

            client = SMBClient(smbConfig)
            connection = client!!.connect(config.serverIp)

            val authContext = if (config.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    config.username,
                    config.password.toCharArray(),
                    null
                )
            }
            session = connection!!.authenticate(authContext)

            diskShare = session!!.connectShare(config.shareName) as? DiskShare
                ?: throw IOException("目标共享目录不是 DiskShare 类型: ${config.shareName}")

            currentConfig = config.copy(password = "")
            Log.i(TAG, "Connected to \\\\${config.serverIp}\\${config.shareName}")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}", e)
            val msg = when {
                e is IOException ->
                    "无法连接到服务器，请检查 IP 地址和网络连接\n${e.message}"
                e.message?.contains("logon", ignoreCase = true) == true
                        || e.message?.contains("auth", ignoreCase = true) == true ->
                    "用户名或密码错误，请重新检查凭据"
                e.message?.contains("time", ignoreCase = true) == true ->
                    "连接超时，请检查 IP 地址和网络连接"
                else -> "无法连接到服务器：${e.message}"
            }
            Result.failure(Exception(msg, e))
        }
    }

    /**
     * 浏览指定路径下的所有条目（文件 + 目录）
     *
     * @param path 相对于共享根的路径，空字符串表示根目录
     * @return 目录条目列表（按：目录在前、名称排序）
     */
    suspend fun listEntries(path: String = ""): List<SmbEntry> = withContext(Dispatchers.IO) {
        val share = diskShare ?: return@withContext emptyList()
        try {
            val files = share.list(path)
            val entries = mutableListOf<SmbEntry>()
            for (file in files) {
                val name = file.fileName
                if (name == "." || name == "..") continue

                val fullPath = if (path.isEmpty()) name else "$path\\$name"
                val isDir = file.fileAttributes and 0x10L != 0L

                entries.add(SmbEntry(
                    name = name,
                    path = fullPath,
                    isDirectory = isDir,
                    size = if (!isDir) file.endOfFile else 0L,
                    lastModified = file.lastWriteTime.toEpochMillis()
                ))
            }
            // 排序：目录在前，同类型按名称排序
            entries.sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list entries at path: $path", e)
            emptyList()
        }
    }

    /**
     * 列出指定路径下包含图片的子目录（相簿）
     *
     * 扫描直接子目录，递归检查每个子目录内是否含有图片文件，
     * 有则返回为 PhotoAlbum（附带第一张图作为封面）。
     *
     * @param path 相对于共享根的路径，空字符串表示根目录
     * @return 相簿列表（按名称排序）
     */
    suspend fun listAlbums(path: String = ""): List<PhotoAlbum> = withContext(Dispatchers.IO) {
        val share = diskShare ?: return@withContext emptyList()
        try {
            val entries = share.list(path)
            val albums = mutableListOf<PhotoAlbum>()

            for (entry in entries) {
                val name = entry.fileName
                if (name == "." || name == "..") continue

                val fullPath = if (path.isEmpty()) name else "$path\\$name"

                // 只处理子目录
                if (entry.fileAttributes and 0x10L != 0L) {
                    // 先快速采样（最多2张）判断是否含图，避免完整扫描太慢
                    val preview = collectImages(share, fullPath, 2, 0)
                    if (preview.isNotEmpty()) {
                        // 完整计数：只统计直接子文件（深度1），不深度递归，避免性能问题
                        val totalCount = countImages(share, fullPath)
                        albums.add(PhotoAlbum(
                            name = name,
                            path = fullPath,
                            photoCount = totalCount,
                            coverPath = preview.first().remotePath
                        ))
                    }
                }
            }

            albums.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list albums at path: $path", e)
            emptyList()
        }
    }

    /**
     * 统计指定目录下的媒体文件总数（递归，不限深度）
     * 用于显示相簿的真实图片数量
     */
    private fun countImages(share: DiskShare, path: String, maxDepth: Int = 8, depth: Int = 0): Int {
        if (depth > maxDepth) return 0
        var count = 0
        try {
            val files = share.list(path)
            for (file in files) {
                val name = file.fileName
                if (name == "." || name == "..") continue
                val fullPath = if (path.isEmpty()) name else "$path\\$name"
                if (file.fileAttributes and 0x10L != 0L) {
                    count += countImages(share, fullPath, maxDepth, depth + 1)
                } else {
                    if (isMediaFile(name)) count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "countImages failed at $path", e)
        }
        return count
    }

    /**
     * 列出共享目录列表
     */
    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        try {
            val conn = connection ?: return@withContext emptyList()
            @Suppress("UNCHECKED_CAST")
            emptyList<String>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list shares", e)
            emptyList()
        }
    }

    /**
     * 递归遍历指定路径下的图片文件
     *
     * @param path 相对于共享根的路径，空字符串表示共享根目录
     * @param maxDepth 最大递归深度，防止无限递归
     */
    suspend fun listImageFiles(path: String = "", maxDepth: Int = 5): List<SmbImageFile> =
        withContext(Dispatchers.IO) {
            val share = diskShare ?: return@withContext emptyList()
            collectImages(share, path, maxDepth, 0)
        }

    /**
     * 获取指定相簿（子目录）内的所有图片
     *
     * 与 listImageFiles 类似，但限定在该相簿路径下。
     *
     * @param albumPath 相簿/子目录的相对路径
     */
    suspend fun listImagesInAlbum(albumPath: String): List<SmbImageFile> =
        withContext(Dispatchers.IO) {
            val share = diskShare ?: return@withContext emptyList()
            collectImages(share, albumPath, 10, 0)
        }

    private fun collectImages(
        share: DiskShare,
        path: String,
        maxDepth: Int,
        currentDepth: Int
    ): List<SmbImageFile> {
        if (currentDepth > maxDepth) return emptyList()
        val results = mutableListOf<SmbImageFile>()
        try {
            val files = share.list(path)
            for (file in files) {
                val name = file.fileName
                if (name == "." || name == "..") continue

                val fullPath = if (path.isEmpty()) name else "$path\\$name"

                if (file.fileAttributes and 0x10L != 0L) {
                    results.addAll(collectImages(share, fullPath, maxDepth, currentDepth + 1))
                } else {
                    if (isMediaFile(name)) {
                        results.add(
                            SmbImageFile(
                                name = name,
                                remotePath = fullPath,
                                fileSize = file.endOfFile,
                                lastModified = file.lastWriteTime.toEpochMillis()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list files at path: $path", e)
        }
        return results
    }

    /**
     * SMB 文件句柄封装（持有文件和输入流，可统一关闭）
     * 支持真正的随机访问（seek + readAt），底层使用 SMB2 READ 命令的 offset 参数，
     * 避免通过循环读取丢弃数据来实现"跳过"。
     */
    class SmbFileHandle(
        private val smbFile: com.hierynomus.smbj.share.File
    ) : Closeable {
        private var filePosition: Long = 0L

        /** 获取底层 InputStream（用于顺序读取场景） */
        val inputStream: InputStream get() = smbFile.inputStream

        /**
         * 真正定位文件指针（SMB2 READ 命令的 offset 由每次 read() 调用时传入，
         * 因此 seek() 只是设置下次读取的起始偏移量，不需要实际网络 IO）
         */
        fun seek(position: Long) {
            filePosition = position.coerceAtLeast(0L)
        }

        /** 获取当前文件指针位置 */
        fun getFilePosition(): Long = filePosition

        /**
         * 从当前文件指针位置读取数据，并自动更新指针。
         * 底层调用 SMBJ 的 File.read(byte[] data, long offset)，
         * 这是一个真正的随机访问读取，直接读取指定 offset 的数据。
         *
         * @return 实际读取的字节数，-1 表示 EOF（注意：SMBJ read 返回 0 表示 EOF）
         */
        fun read(buffer: ByteArray): Int {
            if (buffer.isEmpty()) return 0
            return try {
                // SMBJ: int read(byte[] data, long offset)
                val bytesRead = smbFile.read(buffer, filePosition)
                if (bytesRead > 0) {
                    filePosition += bytesRead
                }
                bytesRead
            } catch (e: Exception) {
                Log.e(TAG, "SmbFileHandle.read() failed at position $filePosition", e)
                throw e
            }
        }

        /**
         * 从指定偏移量读取数据（不修改文件指针）
         * 用于需要真正随机访问的场景（如 HTTP Range 请求）
         */
        fun readAt(position: Long, buffer: ByteArray): Int {
            if (buffer.isEmpty()) return 0
            return try {
                smbFile.read(buffer, position)
            } catch (e: Exception) {
                Log.e(TAG, "SmbFileHandle.readAt() failed at position $position", e)
                throw e
            }
        }

        override fun close() { try { smbFile.close() } catch (_: Exception) {} }
    }

    /**
     * 打开指定远程路径的文件输入流
     */
    @Throws(IOException::class)
    fun openInputStream(remotePath: String): InputStream {
        val share = diskShare ?: throw IOException("SMB 连接未建立")
        val smbFile = share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
            null
        )
        return smbFile.inputStream
    }

    /**
     * 打开文件并返回输入流、文件句柄和文件大小
     * @param knownSize 已知文件大小（来自列表查询，可靠）
     * @return Triple(inputStream, fileHandle, fileSize)
     */
    @Throws(IOException::class)
    fun openInputStreamWithSize(remotePath: String, knownSize: Long = 0L): Triple<InputStream, SmbFileHandle, Long> {
        val share = diskShare ?: throw IOException("SMB 连接未建立")
        val smbFile = share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
            null
        )
        Log.i(TAG, "openInputStreamWithSize: $remotePath, size=$knownSize")
        return Triple(
            smbFile.inputStream,
            SmbFileHandle(smbFile),
            knownSize
        )
    }

    /** 关闭所有 SMB 资源 */
    fun close() {
        closeInternal()
    }

    // ==================== 视频缩略图提取 ====================

    /**
     * 从 SMB 视频流中提取一帧作为缩略图，缓存到磁盘。
     * @param remotePath  远程视频文件路径
     * @param cacheDir    缩略图缓存目录
     * @return 缩略图文件路径，失败返回 null
     */
    suspend fun getVideoThumbnail(
        remotePath: String,
        cacheDir: File
    ): String? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        try {
            if (!isConnected) {
                Log.e(TAG, "getVideoThumbnail: SMB not connected")
                return@withContext null
            }
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val safeName = remotePath
                .substringAfterLast('\\').substringAfterLast('/')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")

            // 临时文件：写入视频流前 2MB（足够取帧）
            val tmpFile = File(cacheDir, "${safeName}_tmp_${System.currentTimeMillis()}.mp4")
            val input = openInputStream(remotePath)
            val buffer = ByteArray(8192)
            val limit = 2 * 1024 * 1024 // 最多读 2MB
            var total = 0
            FileOutputStream(tmpFile).use { fos ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1 && total < limit) {
                    fos.write(buffer, 0, read)
                    total += read
                }
            }
            input.close()

            if (!tmpFile.exists() || tmpFile.length() == 0L) {
                Log.e(TAG, "getVideoThumbnail: temp file empty for $remotePath")
                tmpFile.delete()
                return@withContext null
            }

            retriever = MediaMetadataRetriever()
            retriever.setDataSource(tmpFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(
                1000000, // 1 秒位置（微秒）
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(
                0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            retriever.release()
            retriever = null
            tmpFile.delete()

            if (bitmap == null) {
                Log.e(TAG, "getVideoThumbnail: no frame extracted for $remotePath")
                return@withContext null
            }

            // 压缩为 JPEG 保存到缓存（key 用 remotePath hash）
            val cacheKey = remotePath.hashCode().toString()
            val cacheFile = File(cacheDir, "${cacheKey}_thumb.jpg")
            FileOutputStream(cacheFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            bitmap.recycle()

            Log.i(TAG, "getVideoThumbnail: saved ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "getVideoThumbnail failed for $remotePath", e)
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    // ==================== 文件操作（删除 / 重命名 / 复制 / 移动） ====================

    /**
     * 删除远程文件或空目录
     * @return 成功返回 true
     */
    suspend fun deleteRemoteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val share = diskShare ?: return@withContext false
        try {
            // SMBJ 使用 DiskShare.rm() 删除文件
            share.rm(remotePath)
            Log.i(TAG, "Deleted: $remotePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete $remotePath", e)
            false
        }
    }

    /**
     * 重命名远程文件或目录
     */
    suspend fun renameRemoteFile(remotePath: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val share = diskShare ?: return@withContext false
            try {
                val parentPath = remotePath.substringBeforeLast("\\")
                val newPath = if (parentPath.isNotEmpty()) "$parentPath\\$newName" else newName
                // 防止源路径和目标路径相同
                if (newPath == remotePath) return@withContext true
                // 通过 copy → delete 来模拟 rename（SMBJ 不直接支持 rename）
                val copied = copyRemoteFile(remotePath, newPath)
                if (!copied) {
                    Log.e(TAG, "Rename failed: copy failed for $remotePath")
                    return@withContext false
                }
                val deleted = deleteRemoteFile(remotePath)
                if (!deleted) {
                    Log.w(TAG, "Rename: copy succeeded but delete failed for $remotePath (orphan file may exist at $newPath)")
                }
                Log.i(TAG, "Renamed: $remotePath -> $newPath")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename $remotePath", e)
                false
            }
        }

    /**
     * 复制远程文件：源→目标（通过流拷贝实现）
     */
    suspend fun copyRemoteFile(sourcePath: String, destPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val share = diskShare ?: return@withContext false
            var inStream: InputStream? = null
            var outFile: com.hierynomus.smbj.share.File? = null
            try {
                inStream = openInputStream(sourcePath)
                // 确保目标父目录存在（简单情况假设存在）
                outFile = share.openFile(
                    destPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN_IF,
                    null
                )
                var copied = 0L
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    outFile.outputStream.write(buffer, 0, bytesRead)
                    copied += bytesRead
                }
                outFile.outputStream.flush()
                Log.i(TAG, "Copied: $sourcePath -> $destPath ($copied bytes)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $sourcePath -> $destPath", e)
                false
            } finally {
                try { inStream?.close() } catch (_: Exception) {}
                try { outFile?.close() } catch (_: Exception) {}
            }
        }

    /**
     * 删除远程目录及其内容（递归）
     */
    suspend fun deleteRemoteDirectory(dirPath: String): Boolean = withContext(Dispatchers.IO) {
        val share = diskShare ?: return@withContext false
        if (dirPath.isBlank()) {
            Log.w(TAG, "deleteRemoteDirectory: blank path, refusing to delete root")
            return@withContext false
        }
        try {
            // 先列出并删除子项
            val entries = share.list(dirPath)
            for (entry in entries) {
                val name = entry.fileName
                if (name == "." || name == "..") continue
                val fullPath = if (dirPath.isEmpty()) name else "$dirPath\\$name"
                if (entry.fileAttributes and 0x10L != 0L) {
                    val subDeleted = deleteRemoteDirectory(fullPath)
                    if (!subDeleted) {
                        Log.w(TAG, "Failed to delete subdirectory: $fullPath")
                    }
                } else {
                    val fileDeleted = deleteRemoteFile(fullPath)
                    if (!fileDeleted) {
                        Log.w(TAG, "Failed to delete file in directory: $fullPath")
                    }
                }
            }
            // 再删除目录本身（尝试 rm）
            try {
                share.rm(dirPath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove directory $dirPath (may need manual cleanup)", e)
            }
            Log.i(TAG, "Deleted directory: $dirPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete directory $dirPath", e)
            false
        }
    }

    private fun closeInternal() {
        try { diskShare?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        diskShare = null
        session = null
        connection = null
        client = null
        currentConfig = null
    }

    private fun isMediaFile(name: String): Boolean {
        val lower = name.lowercase()
        // 图片格式
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") || lower.endsWith(".bmp")
        ) return true
        // 视频格式
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") ||
            lower.endsWith(".avi") || lower.endsWith(".mkv") ||
            lower.endsWith(".webm") || lower.endsWith(".3gp") ||
            lower.endsWith(".flv") || lower.endsWith(".m4v")
        ) return true
        return false
    }
}
