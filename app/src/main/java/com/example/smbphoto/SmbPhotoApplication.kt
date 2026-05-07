package com.example.smbphoto

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.bumptech.glide.Glide
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

private const val TAG = "SmbPhotoApp"

/**
 * Application 入口，启用 Hilt 依赖注入
 *
 * 功能：
 * - 全局未捕获异常处理
 * - 退出应用时自动清理查看图片/视频产生的临时缓存
 * - Glide 磁盘缓存大小限制与定期自动清理
 */
@HiltAndroidApp
class SmbPhotoApplication : Application() {

    private var activityCount = 0

    companion object {
        /** Glide 缩略图磁盘缓存上限（MB） */
        const val GLIDE_DISK_CACHE_MAX_MB = 256L

        /** 临时文件保留最大天数 */
        const val TEMP_FILE_MAX_DAYS = 1L
    }

    override fun onCreate() {
        super.onCreate()

        // 设置全局未捕获异常处理器
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Unhandled exception on thread ${thread.name}", throwable)
            val crashInfo = buildString {
                appendLine("=== APP CRASH ===")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${throwable.javaClass.simpleName}")
                appendLine("Message: ${throwable.message}")
                throwable.stackTrace.take(10).forEach { appendLine("  at $it") }
                val cause = throwable.cause
                if (cause != null) {
                    appendLine("Caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                    cause.stackTrace.take(5).forEach { appendLine("    at $it") }
                }
            }
            Log.e(TAG, crashInfo)
            exitProcess(1)
        }

        // 注册 Activity 生命周期回调，用于检测应用退出并清理缓存
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { activityCount++ }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                activityCount--
                // 当所有 Activity 都停止时（用户按 Home 或退出），清理缓存
                if (activityCount <= 0) {
                    cleanupMediaCache()
                    trimGlideMemory()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        Log.i(TAG, "Application initialized successfully")
    }

    /**
     * 清理照片/视频查看过程中产生的临时缓存文件
     *
     * 清理范围：
     * 1. externalCacheDir/share/ — PhotoDetailActivity 下载的临时文件
     * 2. externalCacheDir/glide/ — Glide 缓存中超过 1 天的过期文件
     * 3. cacheDir/tmp/ — 应用级临时目录
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun cleanupMediaCache() {
        GlobalScope.launch {
            var totalDeleted = 0L
            var totalFreedBytes = 0L

            try {
                // 1. 清理 share 临时目录（PhotoDetailActivity 下载的文件）
                externalCacheDir?.let { extCache ->
                    val shareDir = File(extCache, "share")
                    totalDeleted += cleanDirectory(shareDir, TEMP_FILE_MAX_DAYS, true).first
                    totalFreedBytes += cleanDirectory(shareDir, TEMP_FILE_MAX_DAYS, true).second

                    // 也清理 glide 缓存中的旧文件（保留最近访问的）
                    val glideDir = File(extCache, "image_manager_disk_cache")
                    val glideCleanResult = cleanOldFiles(glideDir, TEMP_FILE_MAX_DAYS * 2)
                    totalDeleted += glideCleanResult.first
                    totalFreedBytes += glideCleanResult.second
                }

                // 2. 清理内部缓存中的临时目录
                cacheDir?.let { internalCache ->
                    val tmpDir = File(internalCache, "tmp")
                    totalDeleted += cleanDirectory(tmpDir, 0L, true).first
                    totalFreedBytes += cleanDirectory(tmpDir, 0L, true).second
                }

                if (totalDeleted > 0 || totalFreedBytes > 0) {
                    Log.i(TAG, "Cache cleanup: deleted $totalDeleted files, freed ${formatSize(totalFreedBytes)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup media cache", e)
            }
        }
    }

    /**
     * 清理指定目录下的文件
     *
     * @param dir 目标目录
     * @param maxAgeDays 最大保留天数，0=全部删除
     * @param deleteEmptyDir 是否删除空目录本身
     * @return Pair<删除文件数, 释放字节数>
     */
    private fun cleanDirectory(dir: File, maxAgeDays: Long, deleteEmptyDir: Boolean): Pair<Long, Long> {
        if (!dir.exists() || !dir.isDirectory) return Pair(0L, 0L)

        var deletedCount = 0L
        var freedBytes = 0L
        val now = System.currentTimeMillis()
        val maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000L

        dir.listFiles()?.forEach { file ->
            try {
                if (file.isDirectory) {
                    // 先递归清理子目录
                    val subResult = cleanDirectory(file, maxAgeDays, false)
                    deletedCount += subResult.first
                    freedBytes += subResult.second
                    // 子目录清空后也删掉
                    if (file.listFiles()?.isEmpty() != false && deleteEmptyDir) {
                        if (file.delete()) deletedCount++
                    }
                } else {
                    // 检查文件年龄
                    val age = now - file.lastModified()
                    if (maxAgeDays == 0L || age > maxAgeMs) {
                        freedBytes += file.length()
                        if (file.delete()) deletedCount++
                    }
                }
            } catch (_: Exception) { /* 忽略单个文件错误 */ }
        }

        return Pair(deletedCount, freedBytes)
    }

    /**
     * 清理目录下过期的文件（不递归）
     */
    private fun cleanOldFiles(dir: File, maxAgeDays: Long): Pair<Long, Long> {
        if (!dir.exists() || !dir.isDirectory) return Pair(0L, 0L)

        var deletedCount = 0L
        var freedBytes = 0L
        val now = System.currentTimeMillis()
        val maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000L

        dir.listFiles()?.filter { it.isFile }?.forEach { file ->
            try {
                val age = now - file.lastModified()
                if (age > maxAgeMs) {
                    freedBytes += file.length()
                    if (file.delete()) deletedCount++
                }
            } catch (_: Exception) { /* 忽略 */ }
        }

        return Pair(deletedCount, freedBytes)
    }

    /**
     * 释放 Glide 内存缓存（低内存策略）
     *
     * 在应用退到后台时调用，避免被系统杀掉。
     */
    private fun trimGlideMemory() {
        try {
            Glide.get(this).clearMemory()
            Log.d(TAG, "Glide memory cache trimmed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trim Glide memory", e)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
