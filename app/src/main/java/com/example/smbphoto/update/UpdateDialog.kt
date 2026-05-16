package com.example.smbphoto.update

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import com.example.smbphoto.R
import kotlinx.coroutines.launch

/**
 * 软件更新对话框
 *
 * 功能：
 * 1. 显示更新信息（版本号、更新日志）
 * 2. 后台下载 APK（显示进度）
 * 3. 下载完成后自动触发安装
 */
class UpdateDialog(
    private val context: Context,
    private val updateInfo: UpdateInfo
) {

    private var dialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())

    // UI 元素（用于更新下载进度）
    private var tvTitle: TextView? = null
    private var tvVersion: TextView? = null
    private var tvChangelog: TextView? = null
    private var tvProgress: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnDownload: AppCompatButton? = null
    private var btnCancel: AppCompatButton? = null
    private var btnInstall: AppCompatButton? = null

    // 下载状态
    private var isDownloading = false
    private var downloadedApkFile: java.io.File? = null

    /**
     * 显示更新对话框
     */
    fun show() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_update, null)

        // 初始化 UI 元素
        tvTitle = view.findViewById(R.id.tvUpdateTitle)
        tvVersion = view.findViewById(R.id.tvVersion)
        tvChangelog = view.findViewById(R.id.tvChangelog)
        tvProgress = view.findViewById(R.id.tvProgress)
        progressBar = view.findViewById(R.id.progressBar)
        btnDownload = view.findViewById(R.id.btnDownload)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnInstall = view.findViewById(R.id.btnInstall)

        // 设置更新信息
        tvTitle?.text = "发现新版本"
        tvVersion?.text = "版本 ${updateInfo.versionName}"
        tvChangelog?.text = updateInfo.body.ifEmpty { "暂无更新日志" }

        // 隐藏安装按钮和进度
        btnInstall?.visibility = View.GONE
        tvProgress?.visibility = View.GONE
        progressBar?.visibility = View.GONE

        // 下载按钮点击
        btnDownload?.setOnClickListener {
            startDownload()
        }

        // 取消按钮点击
        btnCancel?.setOnClickListener {
            dialog?.dismiss()
        }

        // 创建对话框
        dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(false)  // 下载过程中不允许取消
            .create()

        dialog?.window?.apply {
            // 设置对话框宽度
            val width = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
            setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        dialog?.show()
    }

    /**
     * 检查并请求安装未知应用权限（Android 8.0+）
     * @return true 如果已有权限，false 如果需要请求权限
     */
    private fun checkInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val packageManager = context.packageManager
            if (!packageManager.canRequestPackageInstalls()) {
                android.util.Log.w("UpdateDialog", "需要安装未知应用权限")
                return false
            }
        }
        return true
    }

    /**
     * 显示安装未知应用权限引导对话框
     */
    private fun showInstallPermissionDialog() {
        AlertDialog.Builder(context)
            .setTitle("需要安装权限")
            .setMessage("为了安装应用更新，需要您开启「安装未知应用」权限。\n\n点击确定将跳转到设置页面，找到本应用并开启「允许安装未知应用」。")
            .setPositiveButton("确定") { _, _ ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        intent.data = Uri.parse("package:${context.packageName}")
                        context.startActivity(intent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("UpdateDialog", "跳转到设置失败", e)
                    Toast.makeText(context, "无法打开设置，请手动开启安装权限", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                android.util.Log.i("UpdateDialog", "用户取消安装权限请求")
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 开始下载
     */
    private fun startDownload() {
        if (isDownloading) return

        isDownloading = true
        btnDownload?.visibility = View.GONE
        tvProgress?.visibility = View.VISIBLE
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        tvProgress?.text = "准备下载..."

        // 启动下载
        kotlinx.coroutines.MainScope().launch {
            UpdateChecker.downloadApk(context, updateInfo, object : UpdateDownloadListener {
                override fun onProgress(progress: Int, downloadedBytes: Long, totalBytes: Long) {
                    handler.post {
                        progressBar?.progress = progress
                        tvProgress?.text = "下载中: $progress% (${formatSize(downloadedBytes)} / ${formatSize(totalBytes)})"
                    }
                }

                override fun onSuccess(apkFile: java.io.File) {
                    handler.post {
                        downloadedApkFile = apkFile
                        onDownloadComplete(apkFile)
                    }
                }

                override fun onError(error: String) {
                    handler.post {
                        onDownloadError(error)
                    }
                }
            })
        }
    }

    /**
     * 下载完成
     */
    private fun onDownloadComplete(apkFile: java.io.File) {
        android.util.Log.i("UpdateDialog", "下载完成: ${apkFile.absolutePath}")

        isDownloading = false
        progressBar?.visibility = View.GONE
        tvProgress?.visibility = View.GONE
        tvChangelog?.visibility = View.GONE

        // 显示安装按钮
        btnInstall?.visibility = View.VISIBLE
        btnInstall?.text = "立即安装 (${formatSize(apkFile.length())})"
        btnInstall?.setOnClickListener {
            // 安装 APK（带权限检查）
            startInstall(apkFile)
            dialog?.dismiss()
        }

        // 显示取消按钮（允许用户稍后安装）
        btnCancel?.text = "稍后"
        btnCancel?.visibility = View.VISIBLE
    }

    /**
     * 开始安装 APK（带安装权限检查）
     */
    private fun startInstall(apkFile: java.io.File) {
        android.util.Log.i("UpdateDialog", "开始安装流程，APK: ${apkFile.absolutePath}")

        // 检查安装权限
        if (!checkInstallPermission()) {
            // 保存待安装文件，显示权限引导对话框
            pendingInstallFile = apkFile
            showInstallPermissionDialog()
            return
        }

        // 权限已就绪，直接安装
        installApkInternal(apkFile)
    }

    private var pendingInstallFile: java.io.File? = null

    /**
     * 实际执行 APK 安装
     */
    private fun installApkInternal(apkFile: java.io.File) {
        try {
            android.util.Log.i("UpdateDialog", "执行 APK 安装: ${apkFile.absolutePath}")
            UpdateChecker.installApk(context, apkFile)
        } catch (e: Exception) {
            android.util.Log.e("UpdateDialog", "安装失败", e)
            Toast.makeText(context, "安装失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 下载出错
     */
    private fun onDownloadError(error: String) {
        android.util.Log.e("UpdateDialog", "下载失败: $error")

        isDownloading = false
        progressBar?.visibility = View.GONE
        tvProgress?.text = "下载失败: $error"

        // 重新显示下载按钮
        btnDownload?.visibility = View.VISIBLE
        btnDownload?.text = "重试"

        // 允许取消
        btnCancel?.text = "取消"
        btnCancel?.visibility = View.VISIBLE
    }

    /**
     * 格式化文件大小
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * 关闭对话框
     */
    fun dismiss() {
        dialog?.dismiss()
    }
}
