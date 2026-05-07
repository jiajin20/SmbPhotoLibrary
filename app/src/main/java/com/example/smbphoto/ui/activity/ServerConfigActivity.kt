package com.example.smbphoto.ui.activity

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smbphoto.R
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.data.model.UiState
import com.example.smbphoto.databinding.ActivityServerConfigBinding
import com.example.smbphoto.smb.SmbEntry
import com.example.smbphoto.ui.adapter.DirectoryPickerAdapter
import com.example.smbphoto.ui.viewmodel.PhotoViewModel
import com.example.smbphoto.ui.viewmodel.ServerConfigViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 服务器配置界面
 *
 * 流程：
 * 1. 用户输入 IP + 共享目录 + 账号 → 点"连接"
 * 2. 连接成功后显示"浏览目录"按钮
 * 3. 点击浏览 → 弹出 SMB 目录浏览器，选择图片目录
 * 4. 确认后保存配置并 setResult(RESULT_OK) 返回主界面
 *    主界面的 onActivityResult 负责读取最新配置并发起连接
 *
 * 注意：此 Activity 持有自己的 photoViewModel 实例，仅用于测试连接和目录浏览，
 * 不与 MainActivity 共享。真正的"正式连接"由 MainActivity 在 onActivityResult 后触发。
 */
@AndroidEntryPoint
class ServerConfigActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT_CONFIG_ID = "extra_edit_config_id"
        const val EXTRA_MANAGE_MODE = "extra_manage_mode"
    }

    private var _binding: ActivityServerConfigBinding? = null
    private val binding get() = _binding!!
    private val configViewModel: ServerConfigViewModel by viewModels()
    /** 独立的 ViewModel 实例，仅用于本 Activity 的连接测试和目录浏览 */
    private val photoViewModel: PhotoViewModel by viewModels()

    /** 当前正在编辑的配置（连接成功后锁定部分字段） */
    private var pendingConfig: ServerConfig? = null
    /** 选中的浏览路径 */
    private var selectedPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            _binding = ActivityServerConfigBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            title = "配置 SMB 服务器"

            loadSavedConfig()
            observeViewModel()
            setupConnectButton()
        } catch (e: Exception) {
            android.util.Log.e("ServerConfigActivity", "onCreate failed", e)
            Toast.makeText(this, "配置界面初始化失败：${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadSavedConfig() {
        configViewModel.getSavedConfig()?.let { config ->
            binding.etServerIp.setText(config.serverIp)
            binding.etShareName.setText(config.shareName)
            binding.etUsername.setText(config.username)
            if (config.rootPath.isNotBlank()) {
                binding.etRootPath.setText(config.rootPath)
            }
            if (config.name.isNotBlank() && config.name != config.serverIp) {
                binding.etServerName.setText(config.name)
            }
            configViewModel.loadSavedCredentials(config.serverIp)?.let { (user, pass) ->
                binding.etUsername.setText(user)
                binding.etPassword.setText(pass)
                binding.cbRememberPassword.isChecked = true
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    configViewModel.validationError.collect { error ->
                        _binding?.let { b ->
                            b.tilServerIp.error = null
                            b.tilShareName.error = null
                            when {
                                error?.contains("IP") == true -> b.tilServerIp.error = error
                                error?.contains("共享") == true -> b.tilShareName.error = error
                                error != null -> Toast.makeText(this@ServerConfigActivity, error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                launch {
                    photoViewModel.connectionState.collect { state ->
                        _binding?.let { b ->
                            when (state) {
                                is UiState.Loading -> {
                                    b.btnConnect.isEnabled = false
                                    b.btnConnect.text = "连接中…"
                                }
                                is UiState.Success -> onConnectionSuccess(b)
                                is UiState.Error -> {
                                    b.btnConnect.isEnabled = true
                                    b.btnConnect.text = "连接"
                                    Toast.makeText(this@ServerConfigActivity, state.message, Toast.LENGTH_LONG).show()
                                }
                                else -> {
                                    b.btnConnect.isEnabled = true
                                    b.btnConnect.text = "连接"
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 连接测试成功：显示目录浏览选项 */
    private fun onConnectionSuccess(b: ActivityServerConfigBinding) {
        Toast.makeText(this@ServerConfigActivity, "✓ 连接成功！", Toast.LENGTH_SHORT).show()

        pendingConfig = configViewModel.buildConfig(
            name = b.etServerName.text.toString(),
            serverIp = b.etServerIp.text.toString(),
            shareName = b.etShareName.text.toString(),
            username = b.etUsername.text.toString(),
            password = b.etPassword.text.toString(),
            rootPath = selectedPath,
            rememberPassword = b.cbRememberPassword.isChecked
        )

        // 隐藏"连接"按钮，显示操作区
        b.btnConnect.visibility = View.GONE

        // 显示连接状态
        b.tvConnectionStatus.visibility = View.VISIBLE
        b.tvConnectionStatus.text = "✓ 已连接到 \\\\${pendingConfig?.serverIp}\\${pendingConfig?.shareName}"

        val container = binding.browseButtonContainer
        container.removeAllViews()

        // 添加"浏览并选择图片目录"按钮
        val browseBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "📁 浏览并选择图片目录"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dpToPx() }
        }
        browseBtn.setOnClickListener { openDirectoryBrowser() }
        container.addView(browseBtn)

        // 添加"使用当前路径开始加载"按钮
        val confirmBtn = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.style.Widget_MaterialComponents_Button_OutlinedButton).apply {
            text = "✓ 使用当前路径开始加载"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        confirmBtn.setOnClickListener { confirmAndFinish() }
        container.addView(confirmBtn)

        container.visibility = View.VISIBLE
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    /** 打开 SMB 目录浏览器对话框 */
    private fun openDirectoryBrowser() {
        if (pendingConfig == null) return

        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("正在读取目录…")
            .setMessage("请稍候，正在获取远程文件列表")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        lifecycleScope.launch {
            val entries = photoViewModel.browsePath(selectedPath)
            loadingDialog.dismiss()

            if (entries.isEmpty()) {
                Toast.makeText(this@ServerConfigActivity, "该目录为空或无法读取", Toast.LENGTH_SHORT).show()
                return@launch
            }

            showDirectoryPicker(entries)
        }
    }

    /** 显示目录选择器 */
    private fun showDirectoryPicker(entries: List<SmbEntry>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_directory_picker, null)
        val rvDirectories = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDirectories)
        val tvCurrentPath = dialogView.findViewById<TextView>(R.id.tvCurrentPath)

        tvCurrentPath.text = buildString {
            append("\\\\${pendingConfig?.serverIp}\\${pendingConfig?.shareName}")
            if (selectedPath.isNotBlank()) append("\\$selectedPath")
        }

        // 统计文件数（用于提示）
        val fileCount = entries.count { !it.isDirectory }
        if (fileCount > 0) {
            tvCurrentPath.text = "${tvCurrentPath.text}   （本目录有 $fileCount 个文件）"
        }

        val adapter = DirectoryPickerAdapter(
            onEntryClick = { entry ->
                if (entry.isDirectory) {
                    selectedPath = entry.path
                    openDirectoryBrowser()
                } else {
                    Toast.makeText(this@ServerConfigActivity, "请选择一个目录", Toast.LENGTH_SHORT).show()
                }
            }
        )
        adapter.submitList(entries)
        rvDirectories.layoutManager = LinearLayoutManager(this)
        rvDirectories.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle("选择图片目录")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .setPositiveButton("使用此目录") { _, _ ->
                confirmAndFinish()
            }
            .show()
    }

    /**
     * 确认选择并返回主界面
     *
     * 保存配置后通过 setResult(RESULT_OK) 返回，
     * MainActivity.onActivityResult() 会读取最新配置并发起正式连接。
     */
    private fun confirmAndFinish() {
        val config = pendingConfig ?: return
        val finalConfig = config.copy(rootPath = selectedPath)
        configViewModel.saveConfig(finalConfig)
        // 通知主界面配置已更新，由主界面负责发起正式连接
        setResult(RESULT_OK)
        finish()
    }

    private fun setupConnectButton() {
        binding.btnConnect.setOnClickListener {
            val config = configViewModel.buildConfig(
                name = binding.etServerName.text.toString(),
                serverIp = binding.etServerIp.text.toString(),
                shareName = binding.etShareName.text.toString(),
                username = binding.etUsername.text.toString(),
                password = binding.etPassword.text.toString(),
                rootPath = binding.etRootPath.text.toString(),
                rememberPassword = binding.cbRememberPassword.isChecked
            ) ?: return@setOnClickListener

            selectedPath = config.rootPath
            // 仅用于连接测试（此 ViewModel 实例与 MainActivity 不共享）
            photoViewModel.connect(config)
        }

        binding.etServerIp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val ip = binding.etServerIp.text.toString().trim()
                if (ip.isNotBlank()) {
                    configViewModel.loadSavedCredentials(ip)?.let { (user, pass) ->
                        binding.etUsername.setText(user)
                        binding.etPassword.setText(pass)
                        binding.cbRememberPassword.isChecked = true
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
