package com.example.smbphoto.smb

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.smbphoto.data.model.SmbImageFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MediaStoreHelper"

/**
 * 与系统 MediaStore 交互的帮助类
 *
 * 负责将 SMB 图片保存到本地相册（Android 10+ 用 RELATIVE_PATH，低版本用 File API）
 */
@Singleton
class MediaStoreHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smbManager: SmbManager
) {

    /**
     * 将远程 SMB 图片保存到系统相册（Pictures/SMB_Photos 目录）
     *
     * @return 保存后的 content:// Uri，失败返回 null
     */
    suspend fun saveImageToGallery(smbImageFile: SmbImageFile): Uri? =
        withContext(Dispatchers.IO) {
            try {
                // 1. 从 SMB 读取数据
                val inputStream = smbManager.openInputStream(smbImageFile.remotePath)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode bitmap from ${smbImageFile.remotePath}")
                    return@withContext null
                }

                val mimeType = getMimeType(smbImageFile.name)
                val compressFormat = getCompressFormat(smbImageFile.name)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 使用 MediaStore RELATIVE_PATH + IS_PENDING
                    saveImageApi29Plus(context.contentResolver, bitmap, smbImageFile.name, mimeType, compressFormat)
                } else {
                    // Android 5-9 使用传统 File API 写入 Pictures 目录
                    saveImageLegacy(context, bitmap, smbImageFile.name, compressFormat)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image to gallery: ${smbImageFile.name}", e)
                null
            }
        }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveImageApi29Plus(
        resolver: ContentResolver,
        bitmap: Bitmap,
        fileName: String,
        mimeType: String,
        compressFormat: Bitmap.CompressFormat
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/SMB_Photos"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val insertUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore 插入失败")

        resolver.openOutputStream(insertUri)?.use { outputStream ->
            bitmap.compress(compressFormat, 95, outputStream)
        }

        val updateValues = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        resolver.update(insertUri, updateValues, null, null)

        Log.i(TAG, "Saved $fileName to gallery (API29+) -> $insertUri")
        return insertUri
    }

    @Suppress("DEPRECATION")
    private fun saveImageLegacy(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        compressFormat: Bitmap.CompressFormat
    ): Uri? {
        // Android 9 及以下：直接写入 Pictures/SMB_Photos 文件夹
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val smbDir = File(picturesDir, "SMB_Photos").also { it.mkdirs() }
        val outFile = File(smbDir, fileName)

        FileOutputStream(outFile).use { fos ->
            bitmap.compress(compressFormat, 95, fos)
        }

        // 通知 MediaStore 刷新
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DATA, outFile.absolutePath)
            put(MediaStore.Images.Media.MIME_TYPE, getMimeType(fileName))
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )

        Log.i(TAG, "Saved $fileName to gallery (legacy) -> ${outFile.absolutePath}")
        return uri
    }

    private fun getMimeType(filename: String): String {
        return when {
            filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
            filename.endsWith(".png", true) -> "image/png"
            filename.endsWith(".webp", true) -> "image/webp"
            filename.endsWith(".gif", true) -> "image/gif"
            filename.endsWith(".bmp", true) -> "image/bmp"
            filename.endsWith(".heic", true) || filename.endsWith(".heif", true) -> "image/heic"
            else -> "image/*"
        }
    }

    private fun getCompressFormat(filename: String): Bitmap.CompressFormat {
        return when {
            filename.endsWith(".png", true) -> Bitmap.CompressFormat.PNG
            filename.endsWith(".webp", true) -> {
                // WEBP_LOSSLESS 只有 Android 11+，低版本用 WEBP（有损但兼容）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            else -> Bitmap.CompressFormat.JPEG
        }
    }
}
