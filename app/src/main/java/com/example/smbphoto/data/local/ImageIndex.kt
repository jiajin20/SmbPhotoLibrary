package com.example.smbphoto.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * 图片索引实体 - 存储图片的 EXIF 元数据
 *
 * 复合主键：(serverIp, shareName, albumPath, filePath)
 * 索引：serverIp + shareName + albumPath + takenAt DESC（用于分页排序查询）
 *
 * @param serverIp 服务器 IP
 * @param shareName 共享目录名
 * @param albumPath 相簿路径
 * @param filePath  文件相对路径，如 "2024/05/photo.jpg"
 * @param fileName  文件名，如 "photo.jpg"
 * @param fileSize  文件大小（字节）
 * @param lastModified 文件修改时间（Unix 毫秒）
 * @param takenAt   EXIF 拍摄时间（Unix 毫秒，0 表示无 EXIF）
 * @param indexedAt  索引建立时间（Unix 毫秒）
 */
@Entity(
    tableName = "image_index",
    primaryKeys = ["serverIp", "shareName", "albumPath", "filePath"],
    indices = [
        Index(
            name = "idx_album_sort",
            value = ["serverIp", "shareName", "albumPath", "takenAt"]
        )
    ]
)
data class ImageIndex(
    val serverIp: String,
    val shareName: String,
    val albumPath: String,
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val takenAt: Long,
    val indexedAt: Long
)
