package com.example.smbphoto.glide

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.smb.SmbManager
import java.io.InputStream

/**
 * Glide ModelLoader：将 SmbImageFile 映射为 InputStream
 *
 * 通过 SmbGlideModule 注册到 Glide 加载管道中。
 */
class SmbModelLoader(
    private val smbManager: SmbManager
) : ModelLoader<SmbImageFile, InputStream> {

    override fun buildLoadData(
        model: SmbImageFile,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> {
        return ModelLoader.LoadData(
            ObjectKey(model.cacheKey), // 确保同一文件使用相同缓存键
            SmbDataFetcher(model, smbManager)
        )
    }

    override fun handles(model: SmbImageFile): Boolean = true

    /**
     * Factory：由 SmbGlideModule 持有 SmbManager 后提供给 Glide 注册
     */
    class Factory(private val smbManager: SmbManager) : ModelLoaderFactory<SmbImageFile, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<SmbImageFile, InputStream> =
            SmbModelLoader(smbManager)

        override fun teardown() {}
    }
}
