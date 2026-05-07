package com.example.smbphoto.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.smbphoto.data.model.UiState
import com.example.smbphoto.databinding.ActivityTvServerConfigBinding
import com.example.smbphoto.ui.viewmodel.PhotoViewModel
import com.example.smbphoto.ui.viewmodel.ServerConfigViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * TV 服务器配置界面
 *
 * 大字体、D-pad 友好的输入布局。
 * 连接成功后自动保存配置并返回主界面。
 */
@AndroidEntryPoint
class TvServerConfigActivity : AppCompatActivity() {

    private var _binding: ActivityTvServerConfigBinding? = null
    private val binding get() = _binding!!
    private val configViewModel: ServerConfigViewModel by viewModels()
    private val photoViewModel: PhotoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            _binding = ActivityTvServerConfigBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 尝试填充已保存的配置
            loadSavedConfig()

            binding.btnConnect.setOnClickListener { attemptConnect() }
            observeViewModel()
        } catch (e: Exception) {
            android.util.Log.e("TvServerConfigActivity", "onCreate failed", e)
            finish()
        }
    }

    private fun loadSavedConfig() {
        configViewModel.getSavedConfig()?.let { config ->
            binding.etServerIp.setText(config.serverIp)
            binding.etShareName.setText(config.shareName)
            binding.etUsername.setText(config.username)
            configViewModel.loadSavedCredentials(config.serverIp)?.let { (user, pass) ->
                binding.etUsername.setText(user)
                binding.etPassword.setText(pass)
            }
        }
    }

    private fun attemptConnect() {
        val ip = binding.etServerIp.text?.toString()?.trim() ?: ""
        val share = binding.etShareName.text?.toString()?.trim() ?: ""
        val user = binding.etUsername.text?.toString()?.trim() ?: ""
        val pass = binding.etPassword.text?.toString() ?: ""

        if (ip.isBlank()) {
            Toast.makeText(this, "请输入服务器 IP 地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (share.isBlank()) {
            Toast.makeText(this, "请输入共享目录名", Toast.LENGTH_SHORT).show()
            return
        }

        val config = configViewModel.buildConfig(
            name = ip,
            serverIp = ip,
            shareName = share,
            username = user,
            password = pass,
            rootPath = "",
            rememberPassword = true
        ) ?: return

        photoViewModel.connect(config)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    configViewModel.validationError.collect { error ->
                        if (error != null) {
                            Toast.makeText(this@TvServerConfigActivity, error, Toast.LENGTH_SHORT).show()
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
                                is UiState.Success -> {
                                    // 保存配置供下次自动连接
                                    val savedConfig = configViewModel.buildConfig(
                                        name = b.etServerIp.text.toString().trim(),
                                        serverIp = b.etServerIp.text.toString().trim(),
                                        shareName = b.etShareName.text.toString().trim(),
                                        username = b.etUsername.text.toString().trim(),
                                        password = b.etPassword.text.toString(),
                                        rootPath = "",
                                        rememberPassword = true
                                    )
                                    savedConfig?.let { configViewModel.saveConfig(it) }

                                    Toast.makeText(this@TvServerConfigActivity, "✓ 连接成功！", Toast.LENGTH_SHORT).show()
                                    setResult(RESULT_OK, Intent())
                                    finish()
                                }
                                is UiState.Error -> {
                                    b.btnConnect.isEnabled = true
                                    b.btnConnect.text = "连 接"
                                    Toast.makeText(this@TvServerConfigActivity, state.message, Toast.LENGTH_LONG).show()
                                }
                                else -> {
                                    b.btnConnect.isEnabled = true
                                    b.btnConnect.text = "连 接"
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
