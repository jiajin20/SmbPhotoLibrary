package com.example.smbphoto.data.repository

import android.net.Uri
import com.example.smbphoto.data.model.PhotoAlbum
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.smb.MediaStoreHelper
import com.example.smbphoto.smb.SmbEntry
import com.example.smbphoto.smb.SmbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SmbRepository 的生产实现
 *
 * 协调 SmbManager 与 MediaStoreHelper 完成数据操作。
 */
@Singleton
class SmbRepositoryImpl @Inject constructor(
    private val smbManager: SmbManager,
    private val mediaStoreHelper: MediaStoreHelper
) : SmbRepository {

    override suspend fun connect(config: ServerConfig): Result<Unit> =
        smbManager.connect(config)

    override suspend fun listShares(): List<String> =
        smbManager.listShares()

    override suspend fun listEntries(path: String): List<SmbEntry> =
        smbManager.listEntries(path)

    override suspend fun listImageFiles(path: String): List<SmbImageFile> =
        smbManager.listImageFiles(path)

    override suspend fun listAlbums(path: String): List<PhotoAlbum> =
        smbManager.listAlbums(path)

    override suspend fun listImagesInAlbum(albumPath: String): List<SmbImageFile> =
        smbManager.listImagesInAlbum(albumPath)

    override suspend fun openInputStream(remotePath: String): InputStream =
        withContext(Dispatchers.IO) {
            smbManager.openInputStream(remotePath)
        }

    override suspend fun saveToGallery(imageFile: SmbImageFile): Uri? =
        mediaStoreHelper.saveImageToGallery(imageFile)

    override fun disconnect() = smbManager.close()

    override suspend fun deleteFile(remotePath: String): Boolean =
        smbManager.deleteRemoteFile(remotePath)

    override suspend fun renameFile(remotePath: String, newName: String): Boolean =
        smbManager.renameRemoteFile(remotePath, newName)

    override suspend fun copyFile(sourcePath: String, destPath: String): Boolean =
        smbManager.copyRemoteFile(sourcePath, destPath)

    override suspend fun deleteDirectory(dirPath: String): Boolean =
        smbManager.deleteRemoteDirectory(dirPath)
}
