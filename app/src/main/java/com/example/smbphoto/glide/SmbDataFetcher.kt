package com.example.smbphoto.glide

import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.smb.SmbManager
import java.io.IOException
import java.io.InputStream

private const val TAG = "SmbDataFetcher"

/**
 * Glide DataFetcher：从 SMB 服务器打开文件流
 *
 * 在 Glide 的后台线程池中运行（非主线程），负责实际的网络 I/O。
 */
class SmbDataFetcher(
    private val model: SmbImageFile,
    private val smbManager: SmbManager
) : DataFetcher<InputStream> {

    @Volatile
    private var inputStream: InputStream? = null

    @Volatile
    private var cancelled = false

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        if (cancelled) return
        try {
            // 检查连接状态
            if (!smbManager.isConnected) {
                Log.w(TAG, "SMB not connected, cannot load: ${model.remotePath}")
                callback.onLoadFailed(IOException("SMB 连接未建立"))
                return
            }
            val stream = smbManager.openInputStream(model.remotePath)
            inputStream = stream
            if (!cancelled) {
                callback.onDataReady(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ${model.remotePath}", e)
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        inputStream = null
    }

    override fun cancel() {
        cancelled = true
        cleanup()
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE
}
