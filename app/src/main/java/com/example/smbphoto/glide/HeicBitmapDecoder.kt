package com.example.smbphoto.glide

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.Size
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import java.io.IOException
import java.io.InputStream

private const val TAG = "HeicBitmapDecoder"

/**
 * Glide ResourceDecoder：支持 HEIC/HEIF 格式图片解码
 *
 * 工作流程：
 * 1. 通过 HEIC 文件头魔数 (ftyp heic/mif1/hevc) 判断是否为 HEIC 格式
 * 2. Android 9+：使用 ImageDecoder 解码（系统原生支持）
 * 3. Android 8 及以下：尝试 BitmapFactory 回退（大部分设备不支持）
 * 4. 非 HEIC 格式：直接返回 null，交给 Glide 默认 Decoder 处理
 *
 * 注册方式：prepend 到 InputStream -> Bitmap 解码链最前端，
 * 非 HEIC 数据会快速跳过，不影响其他格式性能。
 */
class HeicBitmapDecoder(
    private val bitmapPool: BitmapPool
) : ResourceDecoder<InputStream, Bitmap> {

    /** HEIC/HEIF 文件的 ftyp 值 */
    private val heicFtyps = arrayOf("heic", "heix", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avci", "avcs")

    override fun handles(source: InputStream, options: Options): Boolean = true

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        val dataBytes = try {
            // 读取文件头检测格式
            source.mark(SIGNATURE_SIZE)
            val header = ByteArray(SIGNATURE_SIZE)
            val bytesRead = source.read(header)
            if (bytesRead < SIGNATURE_SIZE) {
                source.reset()
                return null
            }
            if (!isHeicSignature(header)) {
                // 不是 HEIC，重置流并返回 null 让下游 decoder 处理
                source.reset()
                return null
            }
            // 是 HEIC，读取全部数据
            source.reset()
            source.readBytes()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read input stream for HEIC detection", e)
            return null
        }

        if (dataBytes.isEmpty()) {
            Log.w(TAG, "Empty HEIC data")
            return null
        }

        val bitmap = decodeHeic(dataBytes, width, height) ?: run {
            Log.w(TAG, "Failed to decode HEIC image (${dataBytes.size} bytes)")
            return null
        }

        Log.d(TAG, "HEIC decoded successfully: ${bitmap.width}x${bitmap.height}")
        return BitmapResource.obtain(bitmap, bitmapPool)
    }

    /**
     * 通过 ISOBMFF (ISO Base Media File Format) 的 ftyp box 检测 HEIC/HEIF 文件
     *
     * HEIC/HEIF 文件头结构：
     * - bytes[0..3]: box size (big-endian int)
     * - bytes[4..7]: "ftyp"
     * - bytes[8..11]: major_brand (如 "heic", "mif1" 等)
     */
    private fun isHeicSignature(header: ByteArray): Boolean {
        if (header.size < SIGNATURE_SIZE) return false
        // 检查 ftyp box 标识
        if (header[4].toInt() and 0xFF != 'f'.code ||
            header[5].toInt() and 0xFF != 't'.code ||
            header[6].toInt() and 0xFF != 'y'.code ||
            header[7].toInt() and 0xFF != 'p'.code) {
            return false
        }
        // 提取 major_brand (bytes[8..11])
        val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
        return brand in heicFtyps
    }

    private fun decodeHeic(data: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
        // 先获取原始图片尺寸
        val originalSize = getImageSize(data)
        if (originalSize == null) {
            return decodeWithBitmapFactory(data, 1)
        }

        // 计算 inSampleSize
        val inSampleSize = calculateInSampleSize(originalSize.width, originalSize.height, targetWidth, targetHeight)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && inSampleSize <= 1) {
            // Android 9+ 且不需要缩小时，使用 ImageDecoder（支持更好）
            decodeWithImageDecoder(data)
        } else {
            // 需要缩小或 Android 9 以下，使用 BitmapFactory
            decodeWithBitmapFactory(data, inSampleSize)
        }
    }

    /**
     * 计算采样率
     */
    private fun calculateInSampleSize(originalWidth: Int, originalHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (reqWidth <= 0 || reqHeight <= 0) return 1

        var inSampleSize = 1

        if (originalHeight > reqHeight || originalWidth > reqWidth) {
            val halfHeight = originalHeight / 2
            val halfWidth = originalWidth / 2

            // 计算最大的 inSampleSize 值，使得宽高都大于等于请求尺寸的 2 倍
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 使用 BitmapFactory 获取图片尺寸
     */
    private fun getImageSize(data: ByteArray): Size? {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                Size(options.outWidth, options.outHeight)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get image size", e)
            null
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(data: ByteArray): Bitmap? {
        return try {
            val byteBuffer = java.nio.ByteBuffer.wrap(data)
            val source = android.graphics.ImageDecoder.createSource(byteBuffer)
            android.graphics.ImageDecoder.decodeBitmap(source) { _, _, _ ->
                android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }.also { bitmap ->
                Log.d(TAG, "HEIC decoded via ImageDecoder: ${bitmap.width}x${bitmap.height}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ImageDecoder failed, trying BitmapFactory fallback", e)
            decodeWithBitmapFactory(data, 1)
        }
    }

    private fun decodeWithBitmapFactory(data: ByteArray, inSampleSize: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inSampleSize = inSampleSize
            BitmapFactory.decodeByteArray(data, 0, data.size, options)?.also {
                Log.d(TAG, "HEIC decoded via BitmapFactory: ${it.width}x${it.height} (inSampleSize=$inSampleSize)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "BitmapFactory failed for HEIC", e)
            null
        }
    }

    companion object {
        /** 用于检测文件头的最小字节数 (ftyp box = 12字节) */
        private const val SIGNATURE_SIZE = 12
    }
}
