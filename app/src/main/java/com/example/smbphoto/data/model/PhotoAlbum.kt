package com.example.smbphoto.data.model

/**
 * 相簿数据模型
 *
 * 代表 SMB 服务器上一个包含图片的子目录。
 * 用户在第一层界面看到的就是相簿列表，点击后进入相簿查看图片。
 *
 * @property name       相簿名称（子目录名）
 * @property path        相对于共享根目录的路径
 * @property photoCount  该相簿内的照片数量
 * @property coverPath   封面图片的 remotePath（取该目录下第一张图），用于缩略图
 */
data class PhotoAlbum(
    val name: String,
    val path: String,
    val photoCount: Int,
    val coverPath: String? = null
)
