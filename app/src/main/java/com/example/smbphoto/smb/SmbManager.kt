package com.example.smbphoto.smb

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.smbphoto.data.model.PhotoAlbum
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.data.model.SmbImageFile
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
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
 * 架构升级：使用 SmbConnectionPool 进行连接池化管理
 * - 引用计数：只有当没有任何组件使用时，才真正关闭连接
 * - 自动重连：连接断开时自动重建
 * - 线程安全：所有连接操作都是线程安全的
 *
 * 职责：
 * 1. 建立与 SMB 服务器的连接和身份验证
 * 2. 枚举远程文件系统中的图片（支持按相簿/目录分组）
 * 3. 浏览远程目录结构（用于用户选择路径）
 * 4. 读取远程文件流供 Glide 加载
 */
@Singleton
class SmbManager @Inject constructor() {

    /**
     * 当前活跃的服务器配置（用于重连检测）
     */
    @Volatile private var currentConfig: ServerConfig? = null

    // 是否已连接
    val isConnected: Boolean
        get() = SmbConnectionPool.isConnected

    /** 获取当前连接的服务器 IP */
    val serverIp: String?
        get() = currentConfig?.serverIp

    /** 获取当前连接的共享目录名 */
    val shareName: String?
        get() = currentConfig?.shareName

    /** 获取当前连接的服务器配置（不含密码，供 DataFetcher 重连使用） */
    fun getCurrentConfig(): ServerConfig? = currentConfig

    /**
     * 获取共享目录
     * @throws IOException 如果未连接
     */
    @Throws(IOException::class)
    private fun getDiskShare(): DiskShare {
        val config = currentConfig ?: throw IOException("SMB 连接未建立")
        return SmbConnectionPool.acquireConnection(config)
    }

    /**
     * 建立 SMB 连接并认证
     *
     * 内部使用 SmbConnectionPool 获取连接（引用计数 +1）
     */
    suspend fun connect(config: ServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 使用连接池获取连接
            SmbConnectionPool.acquireConnection(config)
            currentConfig = config.copy(password = "")
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
        val share = getDiskShare() ?: return@withContext emptyList()
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
            // 检测连接断开，抛出特殊标记让上层处理重连
            if (isConnectionClosedException(e)) {
                throw ConnectionClosedException(path, e)
            }
            emptyList()
        }
    }

    /**
     * 列出指定路径下包含图片的子目录（相簿）
     *
     * 扫描直接子目录，递归检查每个子目录内是否含有图片文件，
     * 有则返回为 PhotoAlbum（附带第一张图作为封面）。
     *
     * 注意：为保证扫描速度，相簿预览时跳过 EXIF 读取（封面图不显示拍摄时间）。
     * EXIF 拍摄时间仅在进入相簿查看具体图片时才读取。
     *
     * @param path 相对于共享根的路径，空字符串表示根目录
     * @return 相簿列表（按名称排序）
     */
    suspend fun listAlbums(path: String = ""): List<PhotoAlbum> = withContext(Dispatchers.IO) {
        val share = getDiskShare() ?: return@withContext emptyList()
        try {
            val entries = share.list(path)
            val albums = mutableListOf<PhotoAlbum>()

            for (entry in entries) {
                val name = entry.fileName
                if (name == "." || name == "..") continue

                val fullPath = if (path.isEmpty()) name else "$path\\$name"

                // 只处理子目录
                if (entry.fileAttributes and 0x10L != 0L) {
                    // 快速采样（最多2张）判断是否含图，skipExif=true 跳过 EXIF 读取保证速度
                    val preview = collectImages(share, fullPath, 2, 0, skipExif = true)
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
            // 检测连接断开，抛出特殊标记让上层处理重连
            if (isConnectionClosedException(e)) {
                throw ConnectionClosedException(path, e)
            }
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
            // 连接断开时抛出异常让上层处理重连
            if (isConnectionClosedException(e)) {
                throw ConnectionClosedException(path, e)
            }
        }
        return count
    }

    /**
     * 列出共享目录列表
     */
    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        try {
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
     * @param skipExif 是否跳过 EXIF 读取（跳过可大幅提升扫描速度）
     */
    suspend fun listImageFiles(path: String = "", maxDepth: Int = 5, skipExif: Boolean = false): List<SmbImageFile> =
        withContext(Dispatchers.IO) {
            val share = getDiskShare() ?: return@withContext emptyList()
            collectImages(share, path, maxDepth, 0, skipExif)
        }

    /**
     * 获取指定相簿（子目录）内的所有图片
     *
     * @param albumPath 相簿/子目录的相对路径
     * @param skipExif 是否跳过 EXIF 读取（默认 true，跳过以提升加载速度）
     *                 设置为 false 时会读取 EXIF 拍摄时间用于时间轴排序
     */
    suspend fun listImagesInAlbum(albumPath: String, skipExif: Boolean = true): List<SmbImageFile> =
        withContext(Dispatchers.IO) {
            val share = getDiskShare() ?: return@withContext emptyList()
            collectImages(share, albumPath, 10, 0, skipExif)
        }

    /**
     * 获取指定相簿内的所有图片（使用索引缓存 EXIF 拍摄时间）
     *
     * 优先使用 ExifIndexManager 缓存的数据，对未缓存的图片才从 SMB 读取。
     * 读取后自动保存到索引缓存，支持进度回调。
     *
     * @param albumPath 相簿路径
     * @param indexManager EXIF 索引管理器
     * @param serverIp 服务器 IP（用于索引隔离）
     * @param shareName 共享目录名（用于索引隔离）
     * @param progressCallback 进度回调（可为 null）
     * @return 图片列表（含拍摄时间）
     */
    suspend fun listImagesInAlbumWithIndex(
        albumPath: String,
        indexManager: ExifIndexManager,
        serverIp: String,
        shareName: String,
        progressCallback: ExifIndexManager.ProgressCallback? = null
    ): List<SmbImageFile> = withContext(Dispatchers.IO) {
        val share = getDiskShare() ?: return@withContext emptyList()

        // 第一步：快速列出所有图片文件（跳过 EXIF 读取）
        val allFiles = collectImages(share, albumPath, 10, 0, skipExif = true)
        if (allFiles.isEmpty()) {
            progressCallback?.onComplete(0)
            return@withContext emptyList()
        }

        val total = allFiles.size
        progressCallback?.onProgress(0, total, "")

        // 第二步：批量查询已缓存的 EXIF 数据
        val cachedExifs = indexManager.getCachedTakenAts(
            serverIp, shareName, albumPath,
            allFiles.map { it.remotePath }
        )

        // 第三步：构建结果，优先使用缓存
        val uncachedFiles = mutableListOf<SmbImageFile>()
        val result = allFiles.map { file ->
            val takenAt = cachedExifs[file.remotePath] ?: 0L
            if (takenAt == 0L) {
                uncachedFiles.add(file)
            }
            file.copy(takenAt = takenAt)
        }

        // 如果全部命中缓存，直接返回
        if (uncachedFiles.isEmpty()) {
            progressCallback?.onComplete(total)
            return@withContext result
        }

        // 第四步：对未缓存的文件逐个读取 EXIF
        val newlyCached = mutableMapOf<String, Long>()
        var processed = cachedExifs.size

        for (file in uncachedFiles) {
            val takenAt = readExifTakenAt(share, file.remotePath)
            newlyCached[file.remotePath] = takenAt
            processed++

            progressCallback?.onProgress(processed, total, file.name)

            // 每 10 个文件或最后一批保存一次
            if (newlyCached.size >= 10 || processed == total) {
                indexManager.saveTakenAts(serverIp, shareName, albumPath, newlyCached)
                newlyCached.clear()
            }
        }

        progressCallback?.onComplete(total)

        // 第五步：返回完整结果
        result.map { file ->
            val cached = cachedExifs[file.remotePath] ?: 0L
            val takenAt = newlyCached[file.remotePath] ?: cached
            file.copy(takenAt = takenAt)
        }
    }

    private fun collectImages(
        share: DiskShare,
        path: String,
        maxDepth: Int,
        currentDepth: Int,
        skipExif: Boolean = false
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
                    results.addAll(collectImages(share, fullPath, maxDepth, currentDepth + 1, skipExif))
                } else {
                    // 收集所有媒体文件（图片 + 视频），视频文件需要 isVideo 属性用于缩略图显示
                    if (isMediaFile(name)) {
                        val lastModifiedMs = file.lastWriteTime.toEpochMillis()
                        // 对图片文件尝试读取 EXIF 拍摄时间（仅 JPEG/HEIC/PNG 等格式）
                        // skipExif=true 时跳过 EXIF 读取，大幅提升扫描速度
                        val takenAtMs = if (!skipExif && isExifSupported(name)) {
                            readExifTakenAt(share, fullPath)
                        } else {
                            0L
                        }
                        results.add(
                            SmbImageFile(
                                name = name,
                                remotePath = fullPath,
                                fileSize = file.endOfFile,
                                lastModified = lastModifiedMs,
                                takenAt = takenAtMs
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list files at path: $path", e)
            // 检测连接断开，抛出特殊标记让上层处理
            if (isConnectionClosedException(e)) {
                throw ConnectionClosedException(path, e)
            }
        }
        return results
    }

    /**
     * 连接关闭异常，用于通知上层需要重连
     */
    class ConnectionClosedException(val failedPath: String, cause: Throwable) : Exception("SMB 连接已断开，路径: $failedPath", cause)

    /**
     * 检测异常是否为连接已断开
     */
    private fun isConnectionClosedException(e: Throwable): Boolean {
        val msg = e.message ?: ""
        val causeMsg = e.cause?.message ?: ""
        val className = e.javaClass.simpleName
        return msg.contains("has already been closed") ||
               causeMsg.contains("has already been closed") ||
               msg.contains("DiskShare") ||
               causeMsg.contains("DiskShare") ||
               e is com.hierynomus.smbj.common.SMBRuntimeException ||
               msg.contains("closed") || causeMsg.contains("closed") ||
               msg.contains("断开") || causeMsg.contains("断开") ||
               className.contains("PoolConnectionClosedException")
    }

    /**
     * 判断文件是否支持 EXIF 读取（JPEG/HEIC/WEBP/PNG 等）
     */
    private fun isExifSupported(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".heic") || lower.endsWith(".heif") ||
            lower.endsWith(".webp") || lower.endsWith(".png")
    }

    /**
     * 从 SMB 远程文件读取 EXIF DateTimeOriginal（拍摄时间）
     *
     * 为减少网络 IO，使用 LimitedInputStream 只读取前 512 KB（EXIF 数据通常在文件头部）。
     * 若读取失败或无 EXIF，返回 0L。
     *
     * @param share  已连接的 DiskShare
     * @param path   文件远程路径
     * @return 拍摄时间的 Unix 毫秒时间戳，或 0L（不可用）
     */
    private fun readExifTakenAt(share: DiskShare, path: String): Long {
        return try {
            val smbFile = share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                null
            )
            smbFile.use { f ->
                // 只读前 512 KB，足以覆盖所有 EXIF 头
                val limitedStream = LimitedInputStream(f.inputStream, 512 * 1024L)
                val exif = ExifInterface(limitedStream)
                val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (dateStr != null) {
                    parseExifDate(dateStr)
                } else {
                    0L
                }
            }
        } catch (e: Exception) {
            // 静默处理：网络错误 / 文件无 EXIF 均返回 0
            Log.v(TAG, "readExifTakenAt: no EXIF for $path (${e.javaClass.simpleName})")
            0L
        }
    }

    /**
     * 解析 EXIF 日期字符串为 Unix 毫秒时间戳
     * EXIF 标准格式：yyyy:MM:dd HH:mm:ss
     */
    private fun parseExifDate(dateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            sdf.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 限速输入流：读取超过 maxBytes 字节后自动报 EOF（-1），
     * 防止 ExifInterface 读取整个大文件。
     */
    private class LimitedInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var read = 0L
        override fun read(): Int {
            if (read >= maxBytes) return -1
            val b = delegate.read()
            if (b != -1) read++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= maxBytes) return -1
            val toRead = minOf(len.toLong(), maxBytes - read).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) read += n
            return n
        }
        override fun close() = delegate.close()
    }

    /**
     * SMB 文件句柄封装（持有文件和输入流，可统一关闭）
     * 支持真正的随机访问（seek + readAt），底层使用 SMB2 READ 命令的 offset 参数，
     * 避免通过循环读取丢弃数据来实现"跳过"。
     */
    class SmbFileHandle(
        private val smbFile: SmbFile
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
        val share = getDiskShare()
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
        val share = getDiskShare()
        val smbFile = share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
            null
        )
        // 【修复】优先从 SMB 文件对象获取真实大小，knownSize 仅作后备
        val fileSize = try {
            // SMBJ 的 File 对象有 getFileInformation() 方法可以获取文件大小
            val fileInfo = smbFile.fileInformation
            fileInfo.standardInformation.endOfFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get SMB file size for $remotePath, fallback to knownSize=$knownSize", e)
            knownSize
        }
        Log.i(TAG, "openInputStreamWithSize: $remotePath, knownSize=$knownSize, actualSize=$fileSize")
        return Triple(
            smbFile.inputStream,
            SmbFileHandle(smbFile),
            fileSize
        )
    }

    /**
     * 文件过大异常（用于 Glide 跳过加载）
     */
    class FileTooLargeException(val fileSize: Long, maxSize: Long) : Exception(
        "File too large to buffer: $fileSize bytes (max: $maxSize bytes)"
    )

    /**
     * 读取 SMB 文件内容到字节数组（使用已存在的连接，不增加引用计数）
     *
     * 专门供 SmbDataFetcher 使用，避免缩略图加载时触发 acquireConnection()。
     * 读取过程中如果连接断开，抛出 ConnectionClosedException，由 DataFetcher 自行重连。
     *
     * @param remotePath 远程文件路径
     * @param knownSize 已知文件大小（用于预分配缓冲区，可为 0）
     * @return 文件内容字节数组
     * @throws FileTooLargeException 当文件超过 50MB 时抛出
     * @throws ConnectionClosedException 当连接已断开时抛出
     */
    @Throws(FileTooLargeException::class, ConnectionClosedException::class)
    fun readFileToBytesWithConnection(remotePath: String, knownSize: Long = 0L): ByteArray {
        val maxSize = 50L * 1024 * 1024 // 50MB
        if (knownSize > maxSize) {
            throw FileTooLargeException(knownSize, maxSize)
        }

        // 不再调用 getDiskShare()（会触发 acquireConnection），
        // 直接通过 SmbConnectionPool 获取已存在的连接（引用计数不变）
        val share: DiskShare
        try {
            val config = currentConfig ?: throw IOException("SMB 连接未建立")
            share = SmbConnectionPool.acquireExistingConnection(config)
        } catch (e: Exception) {
            if (isConnectionClosedException(e)) {
                throw ConnectionClosedException(remotePath, e)
            }
            throw e
        }

        var inputStream: java.io.InputStream? = null
        return try {
            val smbFile = share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                null
            )
            inputStream = smbFile.inputStream

            val bufferSize = if (knownSize > 0 && knownSize <= maxSize) {
                knownSize.toInt()
            } else {
                8 * 1024 * 1024
            }
            val buffer = ByteArray(bufferSize)
            val output = java.io.ByteArrayOutputStream(bufferSize)

            var bytesRead: Int
            var totalRead = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalRead += bytesRead
                if (totalRead > maxSize) {
                    try { inputStream?.close() } catch (_: Exception) {}
                    throw FileTooLargeException(totalRead, maxSize)
                }
                output.write(buffer, 0, bytesRead)
            }
            output.toByteArray()
        } catch (e: FileTooLargeException) {
            throw e
        } catch (e: Exception) {
            if (isConnectionClosedException(e)) {
                throw ConnectionClosedException(remotePath, e)
            }
            Log.w(TAG, "readFileToBytesWithConnection failed for $remotePath: ${e.javaClass.simpleName}: ${e.message}")
            ByteArray(0)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
        }
    }

    /** 关闭所有 SMB 资源 */
    fun close() {
        // 使用连接池释放连接
        SmbConnectionPool.releaseConnection()
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
        val share = try {
            getDiskShare()
        } catch (e: Exception) {
            Log.e(TAG, "deleteRemoteFile: not connected", e)
            return@withContext false
        }
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
            val share = try {
                getDiskShare()
            } catch (e: Exception) {
                Log.e(TAG, "renameRemoteFile: not connected", e)
                return@withContext false
            }
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
            val share = try {
                getDiskShare()
            } catch (e: Exception) {
                Log.e(TAG, "copyRemoteFile: not connected", e)
                return@withContext false
            }
            var inStream: InputStream? = null
            var outFile: SmbFile? = null
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
        val share = try {
            getDiskShare()
        } catch (e: Exception) {
            Log.e(TAG, "deleteRemoteDirectory: not connected", e)
            return@withContext false
        }
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

    // ==================== 连接池引用计数管理 ====================

    /**
     * 开始一个需要保持连接的操作（如视频流传输）
     * 增加连接池引用计数，防止连接被意外关闭
     */
    fun beginActiveOperation() {
        val config = currentConfig ?: return
        try {
            SmbConnectionPool.acquireConnection(config)
        } catch (e: Exception) {
            Log.w(TAG, "beginActiveOperation failed", e)
        }
    }

    /**
     * 结束一个需要保持连接的操作
     * 减少连接池引用计数
     */
    fun endActiveOperation() {
        SmbConnectionPool.releaseConnection()
    }

    /**
     * 更新连接活动时间（防止连接池误判为空闲）
     * 注意：连接池使用引用计数，不再需要心跳机制
     */
    fun updateLastActivity() {
        // 连接池模式下不再需要更新活动时间
        // 连接通过引用计数管理，只要还有引用就不会被关闭
    }

    private fun isMediaFile(name: String): Boolean = isImageFile(name) || isVideoFile(name)

    /** 判断是否为图片文件（仅供缩略图加载） */
    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") || lower.endsWith(".bmp") ||
            lower.endsWith(".heic") || lower.endsWith(".heif")
    }

    /** 判断是否为视频文件 */
    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mov") ||
            lower.endsWith(".avi") || lower.endsWith(".mkv") ||
            lower.endsWith(".webm") || lower.endsWith(".3gp") ||
            lower.endsWith(".flv") || lower.endsWith(".m4v")
    }
}
