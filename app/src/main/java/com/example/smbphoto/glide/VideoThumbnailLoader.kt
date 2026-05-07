package com.example.smbphoto.glide

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.smbphoto.R
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.smb.SmbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * SMB 视频缩略图加载器
 *
 * 使用 MediaMetadataRetriever 从 SMB 视频流提取帧，缓存到本地。
 * 内存缓存使用 LruCache，磁盘缓存使用 app cache 目录。
 *
 * 使用方式：
 *   VideoThumbnailLoader.init(context, smbManager)
 *   VideoThumbnailLoader.load(videoItem, imageView)
 */
object VideoThumbnailLoader {

    private lateinit var appContext: Context
    private lateinit var smbManager: SmbManager

    private val memoryCache: LruCache<String, String> by lazy {
        // 缓存 32 个视频缩略图路径
        object : LruCache<String, String>(32) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 初始化（在 Application 或 Activity 中调用一次） */
    fun init(context: Context, manager: SmbManager) {
        appContext = context.applicationContext
        smbManager = manager
    }

    /**
     * 加载视频缩略图到 ImageView
     * 优先从缓存读取，缓存未命中则异步从 SMB 流提取
     */
    fun load(videoFile: SmbImageFile, imageView: ImageView) {
        val cacheKey = videoFile.remotePath

        // 1. 内存缓存命中：直接加载
        memoryCache.get(cacheKey)?.let { cachedPath ->
            val file = File(cachedPath)
            if (file.exists() && file.length() > 0) {
                Glide.with(imageView)
                    .load(file)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_broken)
                    .into(imageView)
                return
            } else {
                memoryCache.remove(cacheKey)
            }
        }

        // 2. 先显示占位图
        imageView.setImageResource(R.drawable.ic_image_placeholder)

        // 3. 异步从 SMB 流提取帧（使用协程）
        val workScope = CoroutineScope(Dispatchers.IO)
        workScope.launch {
            try {
                val cacheDir = File(appContext.cacheDir, "video_thumb")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val thumbnailPath = smbManager.getVideoThumbnail(
                    videoFile.remotePath,
                    cacheDir
                )

                if (thumbnailPath != null) {
                    memoryCache.put(cacheKey, thumbnailPath)
                    mainHandler.post {
                        Glide.with(imageView)
                            .load(File(thumbnailPath))
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .placeholder(R.drawable.ic_image_placeholder)
                            .error(R.drawable.ic_image_broken)
                            .into(imageView)
                    }
                } else {
                    mainHandler.post {
                        imageView.setImageResource(R.drawable.ic_image_broken)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    imageView.setImageResource(R.drawable.ic_image_broken)
                }
            }
        }
    }

    /**
     * 同步获取缩略图路径（用于详情页预加载）
     * 返回本地缓存文件路径，null 表示无缓存
     */
    fun getCachedThumbnail(videoFile: SmbImageFile): String? {
        val cacheKey = videoFile.remotePath
        memoryCache.get(cacheKey)?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) return path
        }
        return null
    }

    /** 清理内存缓存 */
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }
}
