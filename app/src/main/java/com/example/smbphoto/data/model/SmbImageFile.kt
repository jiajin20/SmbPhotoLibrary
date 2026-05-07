package com.example.smbphoto.data.model

import java.io.Serializable

/**
 * 代表远程 SMB 服务器上的一个媒体文件（图片或视频）
 */
data class SmbImageFile(
    val name: String,           // 文件名，如 "photo.jpg"
    val remotePath: String,     // 完整远程路径，如 "photos/2024/photo.jpg"
    val fileSize: Long,         // 文件大小（字节）
    val lastModified: Long = 0L // 最后修改时间（Unix 毫秒）
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
    /** 用作 Glide 缓存键（全局唯一） */
    val cacheKey: String get() = "smb://$remotePath"

    /** 是否为支持的图片格式 */
    val isImage: Boolean
        get() = name.lowercase().let { n ->
            n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".png") || n.endsWith(".webp") ||
            n.endsWith(".gif") || n.endsWith(".bmp")
        }

    /** 是否为支持的视频格式 */
    val isVideo: Boolean
        get() = name.lowercase().let { n ->
            n.endsWith(".mp4") || n.endsWith(".mov") ||
            n.endsWith(".avi") || n.endsWith(".mkv") ||
            n.endsWith(".webm") || n.endsWith(".3gp") ||
            n.endsWith(".flv") || n.endsWith(".m4v")
        }

    /** 是否为媒体文件（图片或视频） */
    val isMedia: Boolean get() = isImage || isVideo
}
