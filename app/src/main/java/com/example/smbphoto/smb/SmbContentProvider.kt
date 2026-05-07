package com.example.smbphoto.smb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "SmbContentProvider"

/**
 * 轻量级 ContentProvider，将 SMB 文件路径映射为 content:// URI
 *
 * URI 格式：content://com.example.smbphoto.provider/image/{encoded_smb_path}
 *
 * 其他应用可以通过标准 Intent 访问 SMB 图片，无需了解底层协议。
 * 注意：此实现使用临时缓存文件；生产环境应通过 Hilt 注入 SmbManager。
 */
class SmbContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val encodedPath = uri.lastPathSegment ?: return null
        val smbPath = Uri.decode(encodedPath)

        return try {
            // 从应用进程中访问 SmbManager（简化示例）
            // 实际项目中应通过 EntryPoint 或服务获取注入实例
            val tempFile = File(context!!.cacheDir, "smb_cache_${smbPath.hashCode()}")
            if (!tempFile.exists()) {
                Log.w(TAG, "Cache file not found for: $smbPath")
                return null
            }
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open file: $smbPath", e)
            null
        }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/*"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
