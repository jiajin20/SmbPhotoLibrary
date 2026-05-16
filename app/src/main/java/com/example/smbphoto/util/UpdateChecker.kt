package com.example.smbphoto.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用更新检查器
 *
 * 功能：
 * 1. 检测 Gitee releases 最新版本
 * 2. 与本地版本号比对
 * 3. 弹出更新对话框
 * 4. 拉起系统下载管理器
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val API_URL = "https://gitee.com/api/v5/repos/jiajin0920/SmbPhotoLibrary/releases/latest"

    /**
     * 更新信息数据类
     */
    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val releaseName: String,
        val body: String,
        val downloadUrl: String,
        val createdAt: String,
        val isNewer: Boolean
    )

    /**
     * SharedPreferences key for ignored version
     */
    private const val PREFS_NAME = "update_checker"
    private const val KEY_IGNORED_VERSION = "ignored_version"

    /**
     * 获取当前应用版本号
     */
    fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version", e)
            "1.0.0"
        }
    }

    /**
     * 获取当前应用版本号（数字）
     */
    fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version code", e)
            1L
        }
    }

    /**
     * 比较两个版本号
     * @return 1 表示 v1 > v2, -1 表示 v1 < v2, 0 表示相等
     */
    private fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) {
                return if (p1 > p2) 1 else -1
            }
        }
        return 0
    }

    /**
     * 解析 Gitee release tag 获取版本号
     * tag 格式可能是 "app-debug", "v1.0.0" 等
     * 需要从 tag 中提取真正的版本号
     */
    private fun parseVersionFromTag(tagName: String): String {
        // 移除 "v" 前缀
        var version = tagName.removePrefix("v").trim()
        // 如果是 "app-debug" 这种格式，需要从 release name 或 body 获取版本
        if (version == "app-debug" || version.isEmpty()) {
            return "999.0.0" // 这种情况下默认认为是最新版本
        }
        return version
    }

    /**
     * 从 release JSON 解析版本号
     */
    private fun parseVersionFromRelease(json: JSONObject): String {
        // 优先从 name 字段获取
        val name = json.optString("name", "")
        if (name.isNotEmpty() && name != "null") {
            val version = name.removePrefix("v").trim()
            if (version.matches(Regex("\\d+\\.\\d+\\.\\d+.*"))) {
                return version
            }
        }
        // 从 tag_name 获取
        val tagName = json.optString("tag_name", "")
        return parseVersionFromTag(tagName)
    }

    /**
     * 获取 APK 下载链接
     */
    private fun getApkDownloadUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk")) {
                val url = asset.optString("browser_download_url")
                return if (url.isNullOrEmpty()) null else url
            }
        }
        return null
    }

    /**
     * 检查更新（后台执行）
     * @return UpdateInfo 或 null（检查失败）
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "UpdateChecker: 正在请求 Gitee API...")

            val url = URL(API_URL)
            Log.i(TAG, "UpdateChecker: URL = $API_URL")

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "SmbPhotoLibrary Android")

            val responseCode = connection.responseCode
            Log.i(TAG, "UpdateChecker: HTTP 响应码 = $responseCode")
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "UpdateChecker: API 返回非 200，停止解析")
                return@withContext null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()
            connection.disconnect()

            Log.i(TAG, "UpdateChecker: JSON 响应长度 = ${response.length}")

            val json = JSONObject(response)

            val tagName = json.optString("tag_name", "")
            val releaseName = json.optString("name", "")
            val body = json.optString("body", "暂无更新日志")
            val createdAt = json.optString("created_at", "")
            val versionName = parseVersionFromRelease(json)
            val downloadUrl = getApkDownloadUrl(json) ?: json.optString("zipball_url", "")

            val currentVersion = getCurrentVersion(context)
            val isNewer = compareVersion(versionName, currentVersion) > 0

            Log.i(TAG, "UpdateChecker: tag=$tagName, version=$versionName, release=$releaseName")
            Log.i(TAG, "UpdateChecker: 当前版本=$currentVersion, 最新版本=$versionName, 是否更新=$isNewer")

            UpdateInfo(
                tagName = tagName,
                versionName = versionName,
                releaseName = releaseName,
                body = body,
                downloadUrl = downloadUrl,
                createdAt = createdAt,
                isNewer = isNewer
            )
        } catch (e: Exception) {
            Log.e(TAG, "UpdateChecker: 检查更新失败", e)
            null
        }
    }

    /**
     * 忽略指定版本
     */
    fun ignoreVersion(context: Context, version: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_IGNORED_VERSION, version).apply()
        Log.i(TAG, "Ignored version: $version")
    }

    /**
     * 获取忽略的版本
     */
    private fun getIgnoredVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_IGNORED_VERSION, null)
    }

    /**
     * 显示更新对话框
     */
    fun showUpdateDialog(context: Context, updateInfo: UpdateInfo) {
        // 检查是否已忽略
        val ignoredVersion = getIgnoredVersion(context)
        if (ignoredVersion == updateInfo.versionName) {
            Log.i(TAG, "Version ${updateInfo.versionName} ignored by user")
            return
        }

        AlertDialog.Builder(context)
            .setTitle("发现新版本：${updateInfo.versionName}")
            .setMessage(updateInfo.body)
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstall(context, updateInfo.downloadUrl)
            }
            .setNegativeButton("稍后") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("忽略此版本") { _, _ ->
                ignoreVersion(context, updateInfo.versionName)
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 下载并安装 APK
     */
    private fun downloadAndInstall(context: Context, downloadUrl: String) {
        if (downloadUrl.isBlank()) {
            Toast.makeText(context, "下载链接无效", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Android 8.0+ 需要申请安装未知应用权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(intent)
                } else {
                    // 引导用户去设置页面开启权限
                    Toast.makeText(
                        context,
                        "请在设置中开启「安装未知应用」权限",
                        Toast.LENGTH_LONG
                    ).show()
                    val settingsIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settingsIntent)
                }
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download", e)
            Toast.makeText(context, "打开下载链接失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 执行更新检查（每次打开 App 时调用）
     */
    suspend fun checkUpdateWithLimit(context: Context, onResult: (UpdateInfo?) -> Unit) {
        Log.i(TAG, "===== UpdateChecker: 开始检查更新 =====")
        Log.i(TAG, "UpdateChecker: 当前应用版本 = ${getCurrentVersion(context)}")

        val updateInfo = checkForUpdate(context)
        Log.i(TAG, "UpdateChecker: 检查完成，updateInfo = $updateInfo")

        if (updateInfo != null) {
            Log.i(TAG, "UpdateChecker: 最新版本 = ${updateInfo.versionName}, 是否更新 = ${updateInfo.isNewer}")
            if (updateInfo.isNewer) {
                Log.i(TAG, "UpdateChecker: 发现新版本，准备显示更新对话框")
                // 在主线程显示对话框
                withContext(Dispatchers.Main) {
                    showUpdateDialog(context, updateInfo)
                }
            } else {
                Log.i(TAG, "UpdateChecker: 当前已是最新版本")
            }
        } else {
            Log.w(TAG, "UpdateChecker: 检查失败，未获取到更新信息")
        }

        onResult(updateInfo)
        Log.i(TAG, "===== UpdateChecker: 检查更新完成 =====")
    }
}
