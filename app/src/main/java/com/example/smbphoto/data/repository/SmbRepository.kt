package com.example.smbphoto.data.repository

import android.net.Uri
import com.example.smbphoto.data.model.PhotoAlbum
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.smb.ExifIndexManager
import com.example.smbphoto.smb.SmbEntry

/**
 * SMB 相片库 Repository 接口
 *
 * ViewModel 只依赖此接口，与具体实现解耦，便于单元测试时使用 Mock。
 */
interface SmbRepository {

    /** 连接到 SMB 服务器 */
    suspend fun connect(config: ServerConfig): Result<Unit>

    /** 列出所有可用的共享目录 */
    suspend fun listShares(): List<String>

    /** 浏览指定路径下的所有条目（目录 + 文件） */
    suspend fun listEntries(path: String = ""): List<SmbEntry>

    /** 递归获取当前连接共享下的所有图片文件 */
    suspend fun listImageFiles(path: String = ""): List<SmbImageFile>

    /** 列出指定路径下包含图片的子目录（相簿） */
    suspend fun listAlbums(path: String = ""): List<PhotoAlbum>

    /** 获取指定相簿内的所有图片 */
    suspend fun listImagesInAlbum(albumPath: String): List<SmbImageFile>

    /** 直接从缓存获取所有图片（第二次进入相簿时使用，毫秒级响应） */
    suspend fun listImagesFromCache(albumPath: String): List<SmbImageFile>

    /** 分页获取相簿图片（按拍摄时间倒序，从缓存加载） */
    suspend fun listImagesInAlbumPaged(albumPath: String, page: Int): List<SmbImageFile>

    /** 获取相簿图片总数 */
    suspend fun getAlbumImageCount(albumPath: String): Int

    /** 获取带 EXIF 索引的完整图片列表（首次加载用，会建立索引） */
    suspend fun listImagesInAlbumWithIndex(
        albumPath: String,
        progressCallback: ExifIndexManager.ProgressCallback?
    ): List<SmbImageFile>

    /** 打开远程文件的输入流（Glide DataFetcher 使用） */
    suspend fun openInputStream(remotePath: String): java.io.InputStream

    /** 将远程图片保存到本地系统相册 */
    suspend fun saveToGallery(imageFile: SmbImageFile): Uri?

    /** 断开连接并释放资源 */
    fun disconnect()

    /** 删除远程文件 */
    suspend fun deleteFile(remotePath: String): Boolean

    /**
     * 删除远程文件并同步清理缓存索引
     *
     * 删除后自动清除本地缓存（Room），确保 UI 刷新时不再显示已删除的图片。
     * 用于相簿视图内的图片删除。
     *
     * @param remotePath 远程文件路径
     * @param albumPath 所属相簿路径（用于清理 Room 缓存）
     * @return 操作结果
     */
    suspend fun deleteFileWithCache(remotePath: String, albumPath: String): Boolean

    /** 重命名远程文件/目录 */
    suspend fun renameFile(remotePath: String, newName: String): Boolean

    /** 复制远程文件到目标路径 */
    suspend fun copyFile(sourcePath: String, destPath: String): Boolean

    /** 删除远程目录（递归） */
    suspend fun deleteDirectory(dirPath: String): Boolean
}
