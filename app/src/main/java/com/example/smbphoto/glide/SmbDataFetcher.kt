package com.example.smbphoto.glide

import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.smb.SmbAuthException
import com.example.smbphoto.smb.SmbConnectionPool
import com.example.smbphoto.smb.SmbManager
import com.example.smbphoto.smb.SmbManager.ConnectionClosedException
import com.hierynomus.smbj.common.SMBRuntimeException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

private const val TAG = "SmbDataFetcher"

/**
 * Glide DataFetcher：从 SMB 服务器加载图片
 *
 * 在 Glide 的后台线程池中运行（非主线程），负责实际的网络 I/O。
 *
 * 策略：
 * 1. 大文件（>20MB）直接跳过，显示占位图 - 节省内存，避免视频文件被当作图片加载
 * 2. 小文件先读入内存缓冲区，再从缓冲区返回流 - 避免 Glide 异步读取时 SMB 连接关闭导致 NPE
 *
 * 健壮性增强：
 * - 专门处理 SmbAuthException（认证失败），通知 UI 跳转登录
 * - 区分网络波动（可重试）和认证失败（不可重试）
 * - 通过 SmbConnectionPool 自动重连
 *
 * @param targetWidth 目标宽度（来自 Glide 的 override），用于通知下游解码器
 * @param targetHeight 目标高度（来自 Glide 的 override），用于通知下游解码器
 */
class SmbDataFetcher(
    private val model: SmbImageFile,
    private val smbManager: SmbManager,
    private val targetWidth: Int = 0,
    private val targetHeight: Int = 0
) : DataFetcher<InputStream> {

    @Volatile
    private var bufferedData: ByteArray? = null

    @Volatile
    private var cancelled = false

    /**
     * 检测异常是否为连接相关（需要重连）
     *
     * 注意：SmbAuthException 不在这里处理，会被单独捕获
     */
    private fun isConnectionException(e: Throwable): Boolean {
        val msg = e.message ?: ""
        val causeMsg = e.cause?.message ?: ""
        return e is ConnectionClosedException ||
            e is SMBRuntimeException ||
            msg.contains("closed", ignoreCase = true) ||
            msg.contains("DiskShare", ignoreCase = true) ||
            causeMsg.contains("closed", ignoreCase = true)
    }

    /**
     * 检测异常是否为认证相关（不可重试）
     */
    private fun isAuthException(e: Throwable): Boolean {
        return e is SmbAuthException ||
            e.javaClass.name.contains("SmbAuthException") ||
            e.message?.contains("LOGON_FAILURE", ignoreCase = true) == true ||
            e.message?.contains("认证", ignoreCase = true) == true
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        if (cancelled) return

        try {
            // ========== 优化1：大文件（>20MB）直接跳过 ==========
            // 视频等大文件不应该通过 Glide 缩略图加载
            // 视频缩略图由 VideoThumbnailLoader 单独处理
            if (isLargeFile(model.fileSize)) {
                Log.d(TAG, "Skipping large file for thumbnail: ${model.remotePath} " +
                        "(${model.fileSize / 1024 / 1024}MB > 20MB)")
                callback.onLoadFailed(IOException("File too large for thumbnail: ${model.remotePath}"))
                return
            }

            // ========== 优化2：检查认证状态 ==========
            if (SmbConnectionPool.isAuthFailed()) {
                Log.w(TAG, "SMB authentication failed, refusing to load: ${model.remotePath}")
                callback.onLoadFailed(SmbAuthException("SMB 认证已失效，请重新登录"))
                return
            }

            // ========== 优化3：读取文件数据（使用已有连接，不增加引用计数）==========
            // 不再调用 getDiskShare()（会触发 acquireConnection 导致 refCount 暴增），
            // 而是使用 SmbManager.readFileToBytesWithConnection()，
            // 内部通过 SmbConnectionPool.acquireExistingConnection() 获取连接（引用计数不变）。
            // 如果连接已断开，直接抛出 ConnectionClosedException，触发重连逻辑。
            val data = smbManager.readFileToBytesWithConnection(model.remotePath, model.fileSize)

            if (cancelled) {
                callback.onLoadFailed(IOException("Load cancelled"))
                return
            }

            if (data.isEmpty()) {
                Log.w(TAG, "Empty data from SMB: ${model.remotePath}")
                callback.onLoadFailed(IOException("文件为空或读取失败"))
                return
            }

            bufferedData = data
            val inputStream = ByteArrayInputStream(data)

            if (!cancelled) {
                callback.onDataReady(inputStream)
            }

        } catch (e: SmbManager.FileTooLargeException) {
            // 大文件（视频等），缩略图不需要加载
            Log.d(TAG, "Skipping large file for thumbnail: ${model.remotePath} (${e.fileSize / 1024 / 1024}MB)")
            callback.onLoadFailed(IOException("File too large for thumbnail: ${model.remotePath}"))

        } catch (e: SmbAuthException) {
            // ========== 健壮性核心：专门处理认证异常 ==========
            Log.e(TAG, "Authentication failed for thumbnail: ${model.remotePath}", e)
            callback.onLoadFailed(e)

        } catch (e: Exception) {
            // 连接相关异常：尝试重连并重试一次
            if (isConnectionException(e) && !cancelled) {
                Log.w(TAG, "Connection error during load, attempting reconnect: ${e.javaClass.simpleName}")
                try {
                    val reconnected = tryReconnect()
                    if (reconnected) {
                        // 重连成功，重试读取
                        retryLoad(callback)
                        return
                    }
                } catch (authEx: SmbAuthException) {
                    // 重连时发现认证失败
                    Log.e(TAG, "Auth failed during reconnect", authEx)
                    callback.onLoadFailed(authEx)
                    return
                }
            }
            Log.w(TAG, "Failed to load SMB file: ${model.remotePath}, error: ${e.javaClass.simpleName}: ${e.message}")
            cleanup()
            callback.onLoadFailed(e)
        }
    }

    /**
     * 判断文件是否为大文件（>20MB）
     * 这些文件通常是视频，不应该通过 Glide 缩略图加载
     */
    private fun isLargeFile(fileSize: Long): Boolean {
        val threshold = 20L * 1024 * 1024 // 20MB
        return fileSize > threshold
    }

    /**
     * 尝试重连 SMB 服务器
     * @return true=重连成功，false=重连失败
     */
    private fun tryReconnect(): Boolean {
        return try {
            // 通过 SmbConnectionPool 进行重连
            SmbConnectionPool.tryReconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Reconnect failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 重试加载（重连后）
     */
    private fun retryLoad(callback: DataFetcher.DataCallback<in InputStream>) {
        if (cancelled) {
            callback.onLoadFailed(IOException("Load cancelled after reconnect"))
            return
        }
        try {
            // 重连后使用 readFileToBytesWithConnection（不触发 refCount）
            val data = smbManager.readFileToBytesWithConnection(model.remotePath, model.fileSize)
            if (data.isEmpty()) {
                callback.onLoadFailed(IOException("重连后文件为空"))
                return
            }
            bufferedData = data
            if (!cancelled) {
                callback.onDataReady(ByteArrayInputStream(data))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Retry load failed: ${model.remotePath}, error: ${e.javaClass.simpleName}")
            cleanup()
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        bufferedData = null
    }

    override fun cancel() {
        cancelled = true
        cleanup()
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE
}
