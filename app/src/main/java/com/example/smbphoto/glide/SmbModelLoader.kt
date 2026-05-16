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
 * 目标尺寸传递给 DataFetcher，用于通知解码器优化。
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
            ObjectKey(model.cacheKey),
            SmbDataFetcher(model, smbManager, width, height)
        )
    }

    override fun handles(model: SmbImageFile): Boolean = true

    class Factory(private val smbManager: SmbManager) : ModelLoaderFactory<SmbImageFile, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<SmbImageFile, InputStream> =
            SmbModelLoader(smbManager)

        override fun teardown() {}
    }
}
