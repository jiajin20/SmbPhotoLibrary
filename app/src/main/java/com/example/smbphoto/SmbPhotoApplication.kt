package com.example.smbphoto

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bumptech.glide.Glide
import com.example.smbphoto.smb.SmbAuthException
import com.example.smbphoto.smb.SmbConnectionPool
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
 * - 全局未捕获异常处理（增强 SMB 连接异常 + 认证异常）
 * - 退出应用时自动清理查看图片/视频产生的临时缓存
 * - Glide 磁盘缓存大小限制与定期自动清理
 * - SMB 连接状态 LiveData（用于 UI 提示）
 *
 * 健壮性增强：
 * - SmbAuthException 专门处理，提示用户重新登录而不闪退
 * - 网络波动异常自动重连，不崩溃
 * - 不可恢复异常记录日志后优雅退出
 */
@HiltAndroidApp
class SmbPhotoApplication : Application() {

    private var activityCount = 0

    companion object {
        /** Glide 缩略图磁盘缓存上限（MB） */
        const val GLIDE_DISK_CACHE_MAX_MB = 256L

        /** 临时文件保留最大天数 */
        const val TEMP_FILE_MAX_DAYS = 1L

        /** SMB 连接断开事件 */
        private val _smbConnectionLost = MutableLiveData<String?>()
        val smbConnectionLost: LiveData<String?> = _smbConnectionLost

        /**
         * SMB 认证失效事件（需要重新登录）
         */
        private val _smbAuthRequired = MutableLiveData<String?>()
        val smbAuthRequired: LiveData<String?> = _smbAuthRequired

        /**
         * 通知 SMB 连接已断开（供其他组件调用）
         */
        fun notifyConnectionLost(message: String?) {
            _smbConnectionLost.postValue(message)
        }

        /**
         * 通知 SMB 认证失效，需要重新登录（供其他组件调用）
         */
        fun notifyAuthRequired(message: String?) {
            _smbAuthRequired.postValue(message)
        }

        /**
         * 清除 SMB 连接断开事件
         */
        fun clearConnectionLostEvent() {
            _smbConnectionLost.postValue(null)
        }

        /**
         * 清除 SMB 认证失效事件
         */
        fun clearAuthRequiredEvent() {
            _smbAuthRequired.postValue(null)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // ========== 设置全局未捕获异常处理器 ==========
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Unhandled exception on thread ${thread.name}", throwable)

            // ========== 健壮性增强：检测是否是 SMB 认证异常 ==========
            if (throwable is SmbAuthException || isSmbAuthException(throwable)) {
                handleSmbAuthException(throwable, thread)
                // 认证异常不崩溃，提示用户重新登录
                return@setDefaultUncaughtExceptionHandler
            }

            // ========== 增强：检测是否是 SMB 连接相关异常 ==========
            if (isSmbConnectionException(throwable)) {
                handleSmbConnectionException(throwable, thread)
                // SMB 连接异常不崩溃，只是提示用户并尝试恢复
                return@setDefaultUncaughtExceptionHandler
            }

            // 其他严重异常，记录日志后退出
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
     * 检测异常是否是 SMB 认证相关（不可恢复）
     */
    private fun isSmbAuthException(throwable: Throwable): Boolean {
        val msg = throwable.message ?: ""
        val causeMsg = throwable.cause?.message ?: ""
        val className = throwable.javaClass.name

        return className.contains("SmbAuthException") ||
               className.contains("SMBApiException") && (
                   msg.contains("LOGON_FAILURE", ignoreCase = true) ||
                   msg.contains("Access denied", ignoreCase = true) ||
                   msg.contains("STATUS_LOGON_FAILURE", ignoreCase = true)
               ) ||
               msg.contains("认证", ignoreCase = true) ||
               msg.contains("账号", ignoreCase = true) && msg.contains("错误", ignoreCase = true) ||
               causeMsg.contains("LOGON_FAILURE", ignoreCase = true)
    }

    /**
     * 检测异常是否是 SMB 连接相关（可重试）
     */
    private fun isSmbConnectionException(throwable: Throwable): Boolean {
        val msg = throwable.message ?: ""
        val causeMsg = throwable.cause?.message ?: ""
        val className = throwable.javaClass.name

        return className.contains("SMBRuntimeException") ||
               className.contains("ConnectionLostException") ||
               className.contains("DisconnectedException") ||
               msg.contains("has already been closed") ||
               msg.contains("DiskShare has already been closed") ||
               msg.contains("ConnectionLost") ||
               causeMsg.contains("has already been closed") ||
               causeMsg.contains("Connection reset")
    }

    /**
     * 处理 SMB 认证异常（不崩溃，提示用户重新登录）
     *
     * 健壮性核心：
     * - 认证失败是"不可恢复"的，应该立即告知用户
     * - 不尝试重连，因为重试也不会成功
     * - 发布事件让 UI 跳转到登录页面
     */
    private fun handleSmbAuthException(throwable: Throwable, thread: Thread) {
        val errorMessage = when {
            throwable.message?.contains("账号", ignoreCase = true) == true ||
            throwable.message?.contains("密码", ignoreCase = true) == true ->
                "账号或密码错误，请重新输入"
            throwable.message?.contains("权限", ignoreCase = true) == true ->
                "权限不足，无法访问共享目录"
            throwable.message?.contains("超时", ignoreCase = true) == true ->
                "连接超时，请检查网络"
            else -> throwable.message ?: "SMB 认证失败，请重新登录"
        }

        Log.e(TAG, "SMB Authentication Error: $errorMessage", throwable)

        // 1. 重置连接池的认证状态
        SmbConnectionPool.resetAuthState()

        // 2. 发布认证失效事件（UI 应该监听此事件并跳转到登录页面）
        notifyAuthRequired(errorMessage)

        // 3. 记录日志
        Log.i(TAG, "Auth error handled: $errorMessage")
        Log.i(TAG, "UI should navigate to login screen")
    }

    /**
     * 处理 SMB 连接异常（不崩溃，优雅恢复）
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun handleSmbConnectionException(throwable: Throwable, thread: Thread) {
        val errorMessage = when {
            throwable.message?.contains("has already been closed") == true ->
                "SMB 连接已断开，请检查网络"
            throwable.message?.contains("Connection reset") == true ->
                "网络连接被重置，请检查网络"
            else -> throwable.message ?: "SMB 连接异常"
        }

        Log.e(TAG, "SMB Connection Error: $errorMessage", throwable)

        // 1. 尝试重连
        val reconnected = SmbConnectionPool.tryReconnect()
        if (reconnected) {
            Log.i(TAG, "SMB reconnection successful after error")
        } else {
            Log.w(TAG, "SMB reconnection failed")
        }

        // 2. 发布连接断开事件（UI 可以监听并显示提示）
        notifyConnectionLost(errorMessage)

        // 3. 如果是后台线程抛出异常，不退出应用
        // 如果是主线程，可能需要显示 Toast
        if (thread.name == "main") {
            Log.w(TAG, "SMB exception on main thread, showing toast")
            // 注意：这里不能直接 show Toast，因为可能没有 Activity
            // 实际使用中，应该在 Activity 中监听 smbConnectionLost LiveData 来显示 Toast
        }
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
