package com.example.smbphoto.streaming

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.smbphoto.R
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.databinding.ActivityStreamingPlayerBinding
import com.example.smbphoto.smb.SmbManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 流式视频播放器（边加载边播，播完自动清理缓存）
 *
 * 架构：
 * 1. 启动本地 SmbProxyServer（HTTP 代理，后台线程运行）
 * 2. ExoPlayer 通过 http://localhost:<port>/video 请求视频
 * 3. 代理从 SMB 拉流，一边给 ExoPlayer，一边写到本地缓存文件
 * 4. ExoPlayer 收到足够数据后自动开始播放（边下边播）
 * 5. 拖进度条：ExoPlayer 发 Range 请求，代理响应已缓存范围
 * 6. 退出/播完：关闭 ExoPlayer，关闭代理，删除缓存文件
 */
@AndroidEntryPoint
class StreamingVideoPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StreamingPlayer"
        private const val EXTRA_VIDEO_FILE = "extra_video_file"
        private const val BUFFER_TIMEOUT_MS = 30_000L  // 30秒无缓冲则提示

        fun createIntent(context: android.content.Context, videoFile: SmbImageFile): Intent =
            Intent(context, StreamingVideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_FILE, videoFile)
            }
    }

    private lateinit var binding: ActivityStreamingPlayerBinding

    @Inject lateinit var smbManager: SmbManager

    private var exoPlayer: ExoPlayer? = null
    private var proxyServer: SmbProxyServer? = null
    private var proxyUrl: String? = null
    private var hasReceivedData = false  // 是否收到过数据
    private var bufferStartTime = 0L     // 开始缓冲的时间

    // 进度更新（每 1s 刷新一次）
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateBufferingProgress()
            checkBufferTimeout()
            if (exoPlayer?.isPlaying == true || exoPlayer?.playbackState == Player.STATE_BUFFERING) {
                progressHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamingPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoFile: SmbImageFile? = try {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_VIDEO_FILE) as? SmbImageFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video file", e)
            null
        }

        if (videoFile == null) {
            Toast.makeText(this, "视频信息无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnRetry.setOnClickListener { restartWithVideo(videoFile) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        hideSystemUi()
        window.decorView.setOnSystemUiVisibilityChangeListener { hideSystemUi() }

        lifecycleScope.launch { startPlayback(videoFile) }
    }

    private suspend fun startPlayback(videoFile: SmbImageFile) {
        withContext(Dispatchers.Main) {
            binding.loadingOverlay.visibility = View.VISIBLE
            binding.errorOverlay.visibility = View.GONE
            binding.tvLoadingStatus.text = "正在连接服务器..."
            binding.tvLoadingProgress.text = ""
        }

        // Step 1：启动本地代理
        val cacheDir = File(cacheDir, "video_cache").also { it.mkdirs() }
        proxyServer = SmbProxyServer(smbManager, videoFile, cacheDir)

        try {
            proxyUrl = proxyServer!!.start()
            Log.i(TAG, "Proxy started: $proxyUrl")

            // 给代理一点启动时间，避免 ExoPlayer 抢在 accept 之前
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.delay(300)
            }

            withContext(Dispatchers.Main) {
                binding.tvLoadingStatus.text = "正在加载视频..."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            withContext(Dispatchers.Main) {
                showError("无法连接服务器：${e.message}")
            }
            return
        }

        // Step 2：初始化 ExoPlayer
        withContext(Dispatchers.Main) {
            initExoPlayer(videoFile, proxyUrl!!)
        }
    }

    private fun initExoPlayer(videoFile: SmbImageFile, url: String) {
        bufferStartTime = System.currentTimeMillis()
        hasReceivedData = false

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this))
            .build()
            .apply {
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(url))
                    .build()
                setMediaItem(mediaItem)

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.i(TAG, "Playback state: $playbackState")
                        when (playbackState) {
                            Player.STATE_READY -> {
                                hasReceivedData = true
                                bufferStartTime = 0L
                                binding.loadingOverlay.animate()
                                    .alpha(0f).setDuration(300)
                                    .withEndAction { binding.loadingOverlay.visibility = View.GONE }
                                    .start()
                                progressHandler.post(progressRunnable)
                            }
                            Player.STATE_BUFFERING -> {
                                if (!hasReceivedData) {
                                    binding.loadingOverlay.visibility = View.VISIBLE
                                    binding.tvLoadingStatus.text = "正在缓冲..."
                                }
                                if (bufferStartTime == 0L) bufferStartTime = System.currentTimeMillis()
                                updateBufferingProgress()
                            }
                            Player.STATE_ENDED -> {
                                Log.i(TAG, "Playback ended")
                                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} / ${error.message}")
                        val msg = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                                "网络连接失败，请检查网络后重试"
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                                "视频文件未找到"
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                                "视频格式不支持解码"
                            else -> "播放失败：${error.message ?: error.errorCodeName}"
                        }
                        showError(msg)
                    }
                })

                prepare()
                playWhenReady = true
            }

        binding.playerView.player = exoPlayer
    }

    /** 检测缓冲超时 */
    private fun checkBufferTimeout() {
        if (!hasReceivedData && bufferStartTime > 0) {
            val elapsed = System.currentTimeMillis() - bufferStartTime
            if (elapsed > BUFFER_TIMEOUT_MS) {
                Log.w(TAG, "Buffer timeout after ${elapsed}ms")
                showError("视频加载超时（网络较慢或文件较大），请重试或等待缓冲完成")
            }
        }
    }

    private fun updateBufferingProgress() {
        val player = exoPlayer ?: return
        val buffered = player.bufferedPosition
        val current = player.currentPosition
        val duration = player.duration

        if (buffered > 0 && duration > 0 && duration != Long.MAX_VALUE) {
            val pct = ((buffered.toFloat() / duration) * 100).toInt().coerceIn(0, 100)
            binding.progressBar.progress = pct
            binding.tvLoadingProgress.text = "${formatDuration(current)} / ${formatDuration(duration)}（已缓冲 ${pct}%）"
        } else if (!hasReceivedData) {
            binding.tvLoadingProgress.text = "正在连接服务器..."
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0 || ms == Long.MAX_VALUE) return "--:--"
        val s = ms / 1000
        val m = s / 60
        val sec = s % 60
        return String.format("%02d:%02d", m, sec)
    }

    private fun showError(message: String) {
        binding.loadingOverlay.visibility = View.GONE
        binding.errorOverlay.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
    }

    private fun restartWithVideo(videoFile: SmbImageFile) {
        releaseAll()
        lifecycleScope.launch { startPlayback(videoFile) }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onPause() { super.onPause(); exoPlayer?.pause() }
    override fun onResume() { super.onResume(); exoPlayer?.play() }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacksAndMessages(null)
        releaseAll()
        Log.i(TAG, "Activity destroyed")
    }

    private fun releaseAll() {
        try { exoPlayer?.release(); exoPlayer = null } catch (_: Exception) {}
        try { proxyServer?.shutdown(); proxyServer = null } catch (_: Exception) {}
    }
}
