package com.example.smbphoto.glide

import android.graphics.Bitmap
import android.content.Context
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.module.AppGlideModule
import com.example.smbphoto.data.model.SmbImageFile
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.InputStream

private const val TAG = "SmbGlideModule"

/**
 * Glide 自定义模块，注册 SMB 数据源
 *
 * 磁盘缓存设置为 512MB，以支持高分辨率图片的持久化缓存。
 *
 * 关键修复：registerComponents() 使用 try-catch 包裹 Hilt 调用，
 * 防止 Glide 在 Application.onCreate() 早期初始化时因 Hilt 未就绪而崩溃。
 * 同时在 DataFetcher 侧做懒加载，确保实际使用时 SmbManager 已可用。
 */
@GlideModule
class SmbGlideModule : AppGlideModule() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmbManagerEntryPoint {
        fun smbManager(): com.example.smbphoto.smb.SmbManager
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 磁盘缓存 256MB（控制缩略图存储大小，防止占用过多空间）
        val diskCacheSize = 256L * 1024 * 1024
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, diskCacheSize))
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        try {
            // 通过 Hilt EntryPoint 获取 SmbManager 实例
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SmbManagerEntryPoint::class.java
            )
            val smbManager = entryPoint.smbManager()

            // 将 SmbImageFile -> InputStream 的转换注册到 Glide 管道
            registry.prepend(
                SmbImageFile::class.java,
                InputStream::class.java,
                SmbModelLoader.Factory(smbManager)
            )

            // 注册 HEIC/HEIF 解码器（在默认 StreamBitmapDecoder 之前，优先处理 HEIC）
            val heicDecoder = HeicBitmapDecoder(glide.bitmapPool)
            registry.prepend(InputStream::class.java, Bitmap::class.java, heicDecoder)

            Log.i(TAG, "SMB ModelLoader + HEIC decoder registered successfully")
        } catch (e: Exception) {
            // 关键修复：捕获所有异常，防止应用启动崩溃
            // 如果 Hilt 未就绪，Glide 仍然可以正常工作（只是无法加载 SMB 图片）
            Log.e(TAG, "Failed to register SMB ModelLoader, Glide will work without SMB support", e)
        }
    }

    // 返回 false 使 Glide 默认的 manifest 解析也生效
    override fun isManifestParsingEnabled(): Boolean = false
}
