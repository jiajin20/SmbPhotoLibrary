package com.example.smbphoto.smb

import android.util.Log
import com.example.smbphoto.data.model.ServerConfig
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "SmbConnectionPool"

/**
 * SMB 连接池（单例）- 健壮性增强版
 *
 * 核心设计：
 * - 全局单例：整个应用共享一个 SMB 连接
 * - 引用计数：只有当没有任何组件使用时，才真正关闭连接
 * - 状态机管理：IDLE -> CONNECTING -> CONNECTED / FAILED_AUTH
 * - 熔断机制：认证失败时立即熔断，防止无限重试导致死锁
 * - 指数退避：网络错误时渐进式重试
 *
 * 状态机：
 * ```
 *    ┌─────────────────────────────────────────────────────────┐
 *    │                                                         │
 *    ▼                                                         │
 *  IDLE ──connect()──► CONNECTING ──success──► CONNECTED ◄────┘
 *    ▲                                      │       │
 *    │                                      │       │ disconnect/error
 *    │ resetAuthState()                     │       │
 *    │                                      │       ▼
 *    └────────────────── FAILED_AUTH ───────┼────► IDLE
 *                                             │
 *                                             │ (不可恢复，需用户介入)
 *                                             ▼
 *                                         (throw SmbAuthException)
 * ```
 *
 * 使用方式：
 * - 进入页面/开始加载：acquireConnection()
 * - 退出页面/加载结束：releaseConnection()
 * - 认证失败后重新输入密码：resetAuthState()
 *
 * 示例：
 * ```kotlin
 * // 相册列表页面
 * SmbConnectionPool.acquireConnection(config)
 * // ... 使用 SmbManager 读取数据 ...
 * SmbConnectionPool.releaseConnection()
 *
 * // 视频播放页面
 * SmbConnectionPool.acquireConnection(config)
 * // ... 使用 SmbManager 播放视频 ...
 * SmbConnectionPool.releaseConnection()
 * ```
 */
object SmbConnectionPool {

    /** 连接锁 */
    private val lock = Any()

    /** 底层 SMB 资源 */
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    /** 引用计数：记录当前有多少组件正在使用连接 */
    private val referenceCount = AtomicInteger(0)

    /** 当前连接的服务器配置（不含密码，用于显示和判断服务器身份） */
    private var currentConfig: ServerConfig? = null

    /** 含密码的完整配置（仅存储在内存，用于重连时认证） */
    private var fullConfigWithPassword: ServerConfig? = null

    // ========== 健壮性增强：状态机 ==========

    /**
     * 连接状态枚举
     */
    enum class State {
        /** 空闲状态：未连接，可以尝试连接 */
        IDLE,

        /** 连接中状态：正在建立连接 */
        CONNECTING,

        /** 已连接状态：连接可用 */
        CONNECTED,

        /** 认证失败状态：遇到不可恢复的认证错误，需要用户介入 */
        FAILED_AUTH
    }

    /** 当前连接状态（原子操作） */
    private val connectionState = AtomicReference(State.IDLE)

    // ========== 健壮性增强：熔断参数 ==========

    /** 熔断器触发后的冷却时间（毫秒） */
    private const val CIRCUIT_BREAKER_COOL_DOWN_MS = 30_000L // 30秒

    /** 最后一次熔断时间 */
    private var lastCircuitBreakTime = 0L

    // ========== 健壮性增强：重试参数（指数退避） ==========

    /** 最大重试间隔（毫秒） */
    private const val MAX_BACKOFF_MS = 30_000L

    /** 基础重试间隔（毫秒） */
    private const val BASE_BACKOFF_MS = 1_000L

    /** 当前重试次数 */
    private var retryCount = 0

    /** 最后重试时间 */
    private var lastRetryTime = 0L

    /**
     * 获取连接状态
     */
    fun getConnectionState(): State = connectionState.get()

    /**
     * 连接是否有效
     */
    val isConnected: Boolean
        get() = connectionState.get() == State.CONNECTED && diskShare?.isConnected == true

    /**
     * 检查是否是认证失败状态
     */
    fun isAuthFailed(): Boolean = connectionState.get() == State.FAILED_AUTH

    /**
     * 获取连接（引用计数 +1）
     *
     * 健壮性增强：
     * - 如果是 FAILED_AUTH 状态，直接抛出 SmbAuthException，不再尝试连接
     * - 使用状态机管理连接生命周期
     * - 指数退避机制防止频繁重试
     *
     * 调用场景：
     * - 进入相册列表/相簿详情页面
     * - 开始视频播放
     * - 开始图片预览
     * - 开始缩略图加载
     *
     * @param config 服务器配置
     * @return DiskShare 实例
     * @throws SmbAuthException 认证失败时抛出（不可恢复）
     * @throws Exception 其他连接失败时抛出
     */
    @Throws(SmbAuthException::class, Exception::class)
    fun acquireConnection(config: ServerConfig): DiskShare {
        synchronized(lock) {
            // ========== 健壮性核心：熔断检查 ==========
            // 如果处于认证失败状态，直接抛出异常，不再尝试连接
            if (connectionState.get() == State.FAILED_AUTH) {
                Log.e(TAG, "Connection in FAILED_AUTH state, refusing to connect")
                throw SmbAuthException("SMB 认证已失效，请重新输入账号密码")
            }

            // 如果冷却期未过，也拒绝连接
            val now = System.currentTimeMillis()
            if (lastCircuitBreakTime > 0 && now - lastCircuitBreakTime < CIRCUIT_BREAKER_COOL_DOWN_MS) {
                val remaining = CIRCUIT_BREAKER_COOL_DOWN_MS - (now - lastCircuitBreakTime)
                Log.w(TAG, "Circuit breaker cooling down, ${remaining}ms remaining")
                throw SmbAuthException("连接过于频繁，请 ${remaining / 1000} 秒后重试")
            }

            // ========== 引用计数 +1 ==========
            referenceCount.incrementAndGet()
            Log.d(TAG, "acquireConnection: refCount=${referenceCount.get()}, state=${connectionState.get()}")

            // 如果已连接到同一个服务器，直接返回
            if (isConnected &&
                currentConfig?.serverIp == config.serverIp &&
                currentConfig?.shareName == config.shareName) {
                Log.d(TAG, "Reusing existing connection to ${config.serverIp}\\${config.shareName}")
                return diskShare!!
            }

            // 连接不同服务器或连接已断开，先关闭旧连接
            if (diskShare != null) {
                Log.w(TAG, "Different server or disconnected, closing old connection first")
                closeInternal()
            }

            // 建立新连接
            Log.i(TAG, "Creating new SMB connection to ${config.serverIp}\\${config.shareName}")
            return try {
                establishConnection(config)
                // 重置重试计数
                retryCount = 0
                lastRetryTime = 0
                diskShare!!
            } catch (e: SmbAuthException) {
                // 认证异常直接向上传递
                referenceCount.decrementAndGet()
                throw e
            } catch (e: Exception) {
                // 连接失败，减少引用计数
                referenceCount.decrementAndGet()
                throw e
            }
        }
    }

    /**
     * 释放连接（引用计数 -1）
     *
     * 调用场景：
     * - 退出相册列表/相簿详情页面
     * - 视频播放结束
     * - 图片预览关闭
     * - 缩略图加载完成
     *
     * 只有当引用计数归零时，才会真正关闭物理连接
     */
    fun releaseConnection() {
        synchronized(lock) {
            val newCount = referenceCount.decrementAndGet()
            Log.d(TAG, "releaseConnection: refCount=$newCount")

            if (newCount <= 0) {
                Log.d(TAG, "Reference count is $newCount, closing connection")
                closeInternal()
            }
        }
    }

    /**
     * 获取当前引用的数量（调试用）
     */
    fun getReferenceCount(): Int = referenceCount.get()

    /**
     * 获取当前连接的服务器配置
     */
    fun getCurrentConfig(): ServerConfig? = currentConfig

    /**
     * 获取已存在的连接（不增加引用计数）
     *
     * 专门供 SmbDataFetcher 使用。DataFetcher 不应该管理连接生命周期，
     * 它只需要在已有连接的基础上读取数据。如果连接已断开或不存在，
     * 抛出 ConnectionClosedException，由调用方决定是否重连。
     *
     * @return DiskShare 实例
     * @throws SmbAuthException 认证失败时抛出
     * @throws ConnectionClosedException 连接不存在或已断开时抛出
     */
    @Throws(SmbAuthException::class, Exception::class)
    fun acquireExistingConnection(config: ServerConfig): DiskShare {
        // FAILED_AUTH 状态直接拒绝
        if (connectionState.get() == State.FAILED_AUTH) {
            Log.e(TAG, "Connection in FAILED_AUTH state")
            throw SmbAuthException("SMB 认证已失效，请重新输入账号密码")
        }

        // 如果已连接到同一个服务器，直接返回（不增加引用计数）
        if (isConnected &&
            currentConfig?.serverIp == config.serverIp &&
            currentConfig?.shareName == config.shareName) {
            return diskShare!!
        }

        // 未连接或服务器不同，抛出断开异常
        throw PoolConnectionClosedException("SMB 连接已断开，无法读取文件")
    }

    /**
     * 连接断开异常（用于 SmbConnectionPool 内部，映射到 SmbManager.ConnectionClosedException）
     */
    class PoolConnectionClosedException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 内部建立连接
     *
     * 健壮性增强：
     * - 状态机管理：CONNECTING -> CONNECTED / FAILED_AUTH
     * - 认证错误立即熔断
     * - 网络错误允许重试
     */
    @Throws(SmbAuthException::class, Exception::class)
    private fun establishConnection(config: ServerConfig) {
        try {
            // 设置连接状态为 CONNECTING
            connectionState.set(State.CONNECTING)

            val smbConfig = SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build()

            client = SMBClient(smbConfig)
            connection = client!!.connect(config.serverIp)

            val authContext = if (config.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    config.username,
                    config.password.toCharArray(),
                    null
                )
            }

            session = connection!!.authenticate(authContext)
            diskShare = session!!.connectShare(config.shareName) as? DiskShare
                ?: throw Exception("目标共享目录不是 DiskShare 类型: ${config.shareName}")

            // 保存配置（含密码用于重连，不含密码用于显示/判断身份）
            fullConfigWithPassword = config
            currentConfig = config.copy(password = "")

            // 连接成功，设置为 CONNECTED 状态
            connectionState.set(State.CONNECTED)
            Log.i(TAG, "Connected to ${config.serverIp}\\${config.shareName}")

        } catch (e: Exception) {
            // ========== 健壮性核心：检测认证错误并立即熔断 ==========
            if (isAuthException(e)) {
                Log.e(TAG, "Authentication failed (${e.message}), setting FAILED_AUTH state")
                connectionState.set(State.FAILED_AUTH)
                lastCircuitBreakTime = System.currentTimeMillis()
                throw SmbAuthException.fromSmbException(e)
            }

            // 其他异常，设置为 IDLE 允许重试
            Log.w(TAG, "Connection error: ${e.message}")
            connectionState.set(State.IDLE)
            throw e
        }
    }

    /**
     * 检测异常是否为认证相关（不可恢复）
     */
    private fun isAuthException(e: Throwable): Boolean {
        val msg = e.message ?: ""
        val causeMsg = e.cause?.message ?: ""
        val className = e.javaClass.name

        // SMBApiException 或认证相关的错误
        return className.contains("SMBApiException") ||
               className.contains("SMBException") ||
               msg.contains("LOGON_FAILURE", ignoreCase = true) ||
               msg.contains("STATUS_LOGON_FAILURE", ignoreCase = true) ||
               msg.contains("Access denied", ignoreCase = true) ||
               msg.contains("STATUS_ACCESS_DENIED", ignoreCase = true) ||
               msg.contains("authentication", ignoreCase = true) ||
               causeMsg.contains("LOGON_FAILURE", ignoreCase = true) ||
               causeMsg.contains("Access denied", ignoreCase = true)
    }

    /**
     * 内部关闭连接
     */
    private fun closeInternal() {
        // 先关闭磁盘共享
        diskShare?.let {
            try { it.close() } catch (_: Exception) {}
        }
        diskShare = null

        // 关闭会话
        session?.let {
            try { it.close() } catch (_: Exception) {}
        }
        session = null

        // 关闭连接
        connection?.let {
            try { it.close() } catch (_: Exception) {};
        }
        connection = null

        // 关闭客户端
        client?.let {
            try { it.close() } catch (_: Exception) {}
        }
        client = null

        // 注意：保留 currentConfig，因为可能需要用它来重连
        // 只有认证失败时才清除
        if (connectionState.get() != State.FAILED_AUTH) {
            // 正常关闭，设置为 IDLE
            connectionState.set(State.IDLE)
        }
        // referenceCount 由调用方管理
        referenceCount.set(0)

        Log.i(TAG, "Connection closed")
    }

    /**
     * 强制关闭所有连接（用于清理）
     */
    fun forceClose() {
        synchronized(lock) {
            closeInternal()
        }
    }

    /**
     * 重置认证状态（供用户重新输入密码后调用）
     *
     * 调用场景：
     * - 用户在登录页面输入新的账号密码后
     * - 需要重新建立连接时
     */
    fun resetAuthState() {
        synchronized(lock) {
            Log.i(TAG, "Resetting auth state from $connectionState to IDLE")
            connectionState.set(State.IDLE)
            lastCircuitBreakTime = 0L
            retryCount = 0
            lastRetryTime = 0L
            fullConfigWithPassword = null  // 清空密码，强制用户重新输入
            // 关闭现有连接，重新开始
            closeInternal()
        }
    }

    /**
     * 尝试重连
     *
     * 健壮性增强：
     * - 指数退避：每次重连失败后，等待时间翻倍
     * - 最大重试间隔限制
     * - 认证失败时不重连，抛出 SmbAuthException
     *
     * @return true=重连成功，false=重连失败
     * @throws SmbAuthException 认证失败时抛出
     */
    @Throws(SmbAuthException::class)
    fun tryReconnect(): Boolean {
        synchronized(lock) {
            // 优先使用含密码的完整配置，其次使用当前配置
            val config = fullConfigWithPassword ?: currentConfig ?: return false

            // 检查是否是认证失败状态
            if (connectionState.get() == State.FAILED_AUTH) {
                Log.w(TAG, "Cannot reconnect: authentication failed")
                throw SmbAuthException("SMB 认证已失效，请重新输入账号密码")
            }

            if (isConnected) {
                Log.d(TAG, "Already connected, no need to reconnect")
                return true
            }

            // ========== 健壮性增强：指数退避 ==========
            val now = System.currentTimeMillis()
            if (lastRetryTime > 0) {
                val backoff = minOf(
                    BASE_BACKOFF_MS * (1 shl retryCount),
                    MAX_BACKOFF_MS
                )
                if (now - lastRetryTime < backoff) {
                    Log.d(TAG, "Backoff: waiting ${backoff}ms before retry")
                    // 不等待，直接返回失败
                    return false
                }
            }

            return try {
                Log.w(TAG, "Connection lost, attempting to reconnect (attempt ${retryCount + 1})...")
                closeInternal()
                establishConnection(config)
                // 重连成功，重置重试计数
                retryCount = 0
                lastRetryTime = 0
                Log.i(TAG, "Reconnection successful")
                true
            } catch (e: SmbAuthException) {
                // 认证异常直接向上传递
                retryCount = 0
                throw e
            } catch (e: Exception) {
                // 重连失败，增加重试计数
                retryCount++
                lastRetryTime = System.currentTimeMillis()
                Log.e(TAG, "Reconnection failed (attempt ${retryCount}): ${e.message}", e)
                false
            }
        }
    }
}
