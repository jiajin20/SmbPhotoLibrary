package com.example.smbphoto.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.example.smbphoto.data.model.SaveResult
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.data.model.UiState
import com.example.smbphoto.databinding.ActivityTvMainBinding
import com.example.smbphoto.ui.adapter.TvPhotoAdapter
import com.example.smbphoto.ui.viewmodel.PhotoViewModel
import com.example.smbphoto.ui.viewmodel.ServerConfigViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Android TV 主界面
 *
 * 启动时自动尝试使用已保存的配置连接，无需手动打开配置页。
 * 遥控器菜单键可重新打开服务器配置。
 */
@AndroidEntryPoint
class TvMainActivity : AppCompatActivity() {

    private var _binding: ActivityTvMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PhotoViewModel by viewModels()
    private val configViewModel: ServerConfigViewModel by viewModels()
    private lateinit var tvAdapter: TvPhotoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            _binding = ActivityTvMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupRecyclerView()
            observeViewModel()

            // 启动时尝试自动连接
            if (savedInstanceState == null) {
                attemptAutoConnect()
            }
        } catch (e: Exception) {
            android.util.Log.e("TvMainActivity", "onCreate failed", e)
            finish()
        }
    }

    /**
     * 尝试自动重连已保存的配置
     */
    private fun attemptAutoConnect() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val savedConfig = configViewModel.getSavedConfig()
                if (savedConfig != null) {
                    android.util.Log.i("TvMainActivity", "Auto-connecting to ${savedConfig.serverIp}")
                    viewModel.connect(savedConfig)
                } else {
                    // 无保存的配置，打开配置页
                    launch {
                        kotlinx.coroutines.delay(300)
                        if (!isFinishing && !isDestroyed) {
                            openTvServerConfig()
                        }
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        tvAdapter = TvPhotoAdapter(
            onItemClick = { image -> openPhotoDetail(image) }
        )
        binding.rvTvPhotos.apply {
            layoutManager = GridLayoutManager(this@TvMainActivity, 7)
            adapter = tvAdapter
            setHasFixedSize(true)
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        _binding?.let { b ->
                            when (state) {
                                is UiState.Loading -> {
                                    b.tvTvStatus.text = "正在连接服务器…"
                                    b.tvTvStatus.visibility = View.VISIBLE
                                    b.progressBar.visibility = View.VISIBLE
                                }
                                is UiState.Success -> {
                                    b.tvTvStatus.text = ""
                                    b.tvTvStatus.visibility = View.GONE
                                    b.progressBar.visibility = View.GONE
                                }
                                is UiState.Error -> {
                                    b.tvTvStatus.text = "${state.message}（按菜单键重试）"
                                    b.tvTvStatus.visibility = View.VISIBLE
                                    b.progressBar.visibility = View.GONE
                                }
                                else -> Unit
                            }
                        }
                    }
                }

                launch {
                    viewModel.photoList.collect { state ->
                        _binding?.let { b ->
                            when (state) {
                                is UiState.Loading -> {
                                    b.progressBar.visibility = View.VISIBLE
                                }
                                is UiState.Success -> {
                                    b.progressBar.visibility = View.GONE
                                    b.tvEmpty.visibility = View.GONE
                                    tvAdapter.submitList(state.data)
                                    b.rvTvPhotos.post {
                                        b.rvTvPhotos.requestFocus()
                                    }
                                }
                                is UiState.Empty -> {
                                    b.progressBar.visibility = View.GONE
                                    b.tvEmpty.visibility = View.VISIBLE
                                    b.tvEmpty.text = "该目录下没有图片\n按 菜单键 更换目录"
                                }
                                is UiState.Error -> {
                                    b.progressBar.visibility = View.GONE
                                    b.tvEmpty.text = "加载失败：${state.message}\n按 菜单键 重新设置"
                                    b.tvEmpty.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.saveResult.collect { result ->
                        if (result == null) return@collect
                        when (result) {
                            is SaveResult.Success ->
                                Toast.makeText(this@TvMainActivity, "已保存：${result.displayName}", Toast.LENGTH_SHORT).show()
                            is SaveResult.Failure ->
                                Toast.makeText(this@TvMainActivity, "保存失败：${result.message}", Toast.LENGTH_LONG).show()
                        }
                        viewModel.consumeSaveResult()
                    }
                }
            }
        }
    }

    /** 遥控器按键：菜单键打开配置 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                openTvServerConfig()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun openPhotoDetail(image: SmbImageFile) {
        val intent = Intent(this, PhotoDetailActivity::class.java).apply {
            putExtra(PhotoDetailActivity.EXTRA_REMOTE_PATH, image.remotePath)
            putExtra(PhotoDetailActivity.EXTRA_FILE_NAME, image.name)
        }
        startActivity(intent)
    }

    private fun openTvServerConfig() {
        startActivity(Intent(this, TvServerConfigActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
