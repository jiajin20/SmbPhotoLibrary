package com.example.smbphoto.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdateChecker"

/**
 * 应用更新信息
 */
data class UpdateInfo(
    val tagName: String,        // 如 "v1.1.0"
    val versionName: String,    // 如 "1.1.0"
    val releaseName: String,   // 发布标题
    val body: String,          // 更新日志
    val downloadUrl: String,   // APK 下载地址
    val createdAt: String,     // 发布时间
    val isNewer: Boolean        // 是否比当前版本更新
)

/**
 * 更新下载进度回调
 */
interface UpdateDownloadListener {
    fun onProgress(progress: Int, downloadedBytes: Long, totalBytes: Long)
    fun onSuccess(apkFile: File)
    fun onError(error: String)
}

/**
 * 软件更新检测与下载管理器
 *
 * 功能：
 * 1. 检测软件更新（每次打开 App 检测一次）
 * 2. 后台下载 APK（不跳转浏览器）
 * 3. 下载完成后自动安装
 *
 * 数据来源：Gitee Releases API
 */
object UpdateChecker {

    private const val API_URL = "https://gitee.com/api/v5/repos/jiajin0920/SmbPhotoLibrary/releases/latest"

    /**
     * 获取当前应用版本号
     */
    fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to get version", e)
            "1.0.0"
        }
    }

    /**
     * 获取当前应用版本码
     */
    fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to get version code", e)
            1L
        }
    }

    /**
     * 比较版本号（支持 x.y.z 格式）
     */
    private fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    /**
     * 检查软件更新（每次调用都会检测，不限制频率）
     *
     * @param context Android Context
     * @param onResult 回调，返回 UpdateInfo（如果有更新）或 null（无更新或检查失败）
     */
    suspend fun checkUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "===== UpdateChecker: 开始检查更新 =====")
                Log.i(TAG, "UpdateChecker: 当前应用版本 = ${getCurrentVersion(context)}")

                val currentVersion = getCurrentVersion(context)

                // 请求 Gitee API
                Log.i(TAG, "UpdateChecker: 正在请求 Gitee API...")
                Log.i(TAG, "UpdateChecker: URL = $API_URL")

                val url = URL(API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "SmbPhotoLibrary-Android")

                val responseCode = connection.responseCode
                Log.i(TAG, "UpdateChecker: HTTP 响应码 = $responseCode")

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "UpdateChecker: API 请求失败，响应码 = $responseCode")
                    withContext(Dispatchers.Main) {
                        onResult(null)
                    }
                    return@withContext
                }

                val response = connection.inputStream.bufferedReader().readText()
                Log.i(TAG, "UpdateChecker: JSON 响应长度 = ${response.length}")

                // 解析 JSON（简单解析，无需 Gson）
                val tagName = extractJsonString(response, "tag_name")
                val versionName = extractJsonString(response, "name")
                val releaseName = extractJsonString(response, "tag_name")
                val body = extractJsonString(response, "body")
                val createdAt = extractJsonString(response, "created_at")

                Log.i(TAG, "UpdateChecker: tag=$tagName, version=$versionName, release=$releaseName")

                // 提取下载链接（从 assets 中找到 browser_download_url）
                val downloadUrl = extractDownloadUrl(response)
                Log.i(TAG, "UpdateChecker: downloadUrl = $downloadUrl")

                // 比较版本
                val isNewer = compareVersion(versionName, currentVersion) > 0
                Log.i(TAG, "UpdateChecker: 当前版本=$currentVersion, 最新版本=$versionName, 是否更新=$isNewer")

                if (isNewer && downloadUrl.isNotEmpty()) {
                    val updateInfo = UpdateInfo(
                        tagName = tagName,
                        versionName = versionName,
                        releaseName = releaseName,
                        body = body,
                        downloadUrl = downloadUrl,
                        createdAt = createdAt,
                        isNewer = true
                    )
                    Log.i(TAG, "UpdateChecker: 检查完成，updateInfo = $updateInfo")
                    withContext(Dispatchers.Main) {
                        onResult(updateInfo)
                    }
                } else {
                    Log.i(TAG, "UpdateChecker: 当前已是最新版本")
                    withContext(Dispatchers.Main) {
                        onResult(null)
                    }
                }

                connection.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "UpdateChecker: 检查更新失败", e)
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            }
        }
    }

    /**
     * 简单 JSON 解析：提取字符串值
     */
    private fun extractJsonString(json: String, key: String): String {
        val pattern = """"$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
        val match = pattern.find(json)
        return match?.groupValues?.getOrNull(1)
            ?.replace("\\n", "\n")
            ?.replace("\\r", "\r")
            ?.replace("\\\"", "\"")
            ?: ""
    }

    /**
     * 提取下载链接（从 assets 数组中找到 .apk 结尾的链接）
     */
    private fun extractDownloadUrl(json: String): String {
        // 查找所有 browser_download_url
        val pattern = """"browser_download_url"\s*:\s*"([^"]+)"""".toRegex()
        val matches = pattern.findAll(json)
        for (match in matches) {
            val url = match.groupValues[1]
            if (url.endsWith(".apk", ignoreCase = true)) {
                return url
            }
        }
        // 如果没找到 .apk，返回第一个下载链接
        return matches.firstOrNull()?.groupValues?.getOrNull(1) ?: ""
    }

    /**
     * 后台下载 APK 文件
     *
     * @param context Android Context
     * @param updateInfo 更新信息
     * @param listener 下载进度回调
     * @return 下载完成的 APK 文件（已修正为 .apk 后缀）
     */
    suspend fun downloadApk(
        context: Context,
        updateInfo: UpdateInfo,
        listener: UpdateDownloadListener
    ) {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "===== UpdateChecker: 开始下载 APK =====")
                Log.i(TAG, "UpdateChecker: 下载地址 = ${updateInfo.downloadUrl}")
                Log.i(TAG, "UpdateChecker: 版本 = ${updateInfo.versionName}")

                val url = URL(updateInfo.downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.setRequestProperty("User-Agent", "SmbPhotoLibrary-Android")

                val responseCode = connection.responseCode
                Log.i(TAG, "UpdateChecker: 下载 HTTP 响应码 = $responseCode")

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val error = "下载失败，响应码: $responseCode"
                    Log.e(TAG, "UpdateChecker: $error")
                    withContext(Dispatchers.Main) {
                        listener.onError(error)
                    }
                    return@withContext
                }

                // 获取文件大小
                val contentLength = connection.contentLength
                Log.i(TAG, "UpdateChecker: 文件大小 = ${formatBytes(contentLength.toLong())}")

                // 创建下载目录
                val downloadDir = File(context.getExternalFilesDir(null), "updates")
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }

                // 清理旧版本（保留最新版本）
                downloadDir.listFiles()?.forEach { file ->
                    if (file.name != "${updateInfo.versionName}.apk") {
                        file.delete()
                    }
                }

                // 生成文件名（强制使用 .apk 后缀）
                val apkFileName = "${updateInfo.versionName}.apk"
                val apkFile = File(downloadDir, apkFileName)

                // 如果文件已存在且大小匹配，直接使用
                if (apkFile.exists() && apkFile.length() == contentLength.toLong()) {
                    Log.i(TAG, "UpdateChecker: APK 已存在，直接使用: ${apkFile.absolutePath}")
                    withContext(Dispatchers.Main) {
                        listener.onSuccess(apkFile)
                    }
                    return@withContext
                }

                // 开始下载
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var totalBytesRead = 0L
                var lastProgress = -1

                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // 计算进度
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            Log.i(TAG, "UpdateChecker: 下载进度 $progress% (${formatBytes(totalBytesRead)} / ${formatBytes(contentLength.toLong())})")
                            withContext(Dispatchers.Main) {
                                listener.onProgress(progress, totalBytesRead, contentLength.toLong())
                            }
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
                connection.disconnect()

                Log.i(TAG, "UpdateChecker: 下载完成: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

                withContext(Dispatchers.Main) {
                    listener.onSuccess(apkFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "UpdateChecker: 下载失败", e)
                withContext(Dispatchers.Main) {
                    listener.onError("下载失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 安装 APK
     *
     * @param context Android Context
     * @param apkFile APK 文件
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            Log.i(TAG, "UpdateChecker: 开始安装 APK: ${apkFile.absolutePath}")

            val intent = Intent(Intent.ACTION_VIEW)

            // Android 7.0+ 使用 FileProvider
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    apkFile
                )
                intent.setDataAndType(uri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "UpdateChecker: 安装失败", e)
        }
    }

    /**
     * 格式化文件大小
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
