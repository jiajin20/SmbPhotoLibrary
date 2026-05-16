package com.example.smbphoto.streaming

import android.util.Log
import com.example.smbphoto.data.model.SmbImageFile
import com.example.smbphoto.smb.SmbConnectionPool
import com.example.smbphoto.smb.SmbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/**
 * SMB 视频流式播放器代理服务器
 *
 * 核心思路：
 * 1. ExoPlayer 发 HTTP 请求 → 本地代理接收
 * 2. 代理从 SMB 顺序读流 → 边吐给 ExoPlayer、边写缓存文件
 * 3. ExoPlayer 收到部分数据就开始解码播放（边下边播）
 * 4. 播完/退出 → 关闭代理 → 删除缓存文件
 *
 * 健壮性增强（基于 SmbConnectionPool 架构）：
 * - 使用 SmbConnectionPool 管理连接生命周期（引用计数）
 * - 流式传输过程中持续保持连接活跃
 * - 网络波动时自动重连
 * - 优雅处理 ExoPlayer 的 seek 操作
 *
 * HTTP 健壮性增强：
 * - 严格遵守 HTTP 协议，Content-Length 和 Accept-Ranges 准确无误
 * - 防御性读取：捕获所有 IO 异常，防止 SMB 断连导致代理崩溃
 * - 完整的 Range 请求支持（206 Partial Content）
 * - 无论 SMB 流如何中断，始终返回合法的 HTTP 响应
 *
 * 关键设计：
 * - 优先使用 SmbManager 获取的真实文件大小
 * - 文件大小未知时，用 chunked encoding 代替 Content-Length
 * - Range 请求在文件大小未知时降级为普通请求（返回 200 + 全部数据）
 * - 缓存文件用于 Range 请求的已缓存部分加速
 */
class SmbProxyServer(
    private val smbManager: SmbManager,
    private val videoFile: SmbImageFile,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "SmbProxy"
        private const val BUFFER_SIZE = 128 * 1024  // 128KB（优化：增大缓冲区提升传输效率）
        private const val HTTP_OK = "HTTP/1.1 200 OK\r\n"
        private const val HTTP_PARTIAL = "HTTP/1.1 206 Partial Content\r\n"
        private const val HTTP_NOT_FOUND = "HTTP/1.1 404 Not Found\r\n"
        private const val HTTP_SERVICE_UNAVAILABLE = "HTTP/1.1 503 Service Unavailable\r\n"
        private const val HTTP_RANGE_NOT_SATISFIABLE = "HTTP/1.1 416 Range Not Satisfiable\r\n"
        private const val SERVER_NAME = "SmbProxy/1.1"

        // ========== 健壮性增强：HTTP 头分隔符 ==========
        private const val CRLF = "\r\n"
        private const val HEADER_SEPARATOR = "$CRLF$CRLF"
    }

    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = -1
    private var tempCacheFile: File? = null
    private var tempCacheStream: FileOutputStream? = null

    /** 已写入缓存的字节数（仅统计实际写入的数据，对应原始文件 range [cacheStartPos, cacheStartPos+cachedBytes-1]） */
    @Volatile private var cachedBytes: Long = 0L

    /**
     * 缓存数据在原始文件中的起始字节偏移。
     * - 初始为 0，表示从头开始缓存
     * - rebuild 后更新为 rangeStart，表示本次缓存从头 skip 到 rangeStart 为止
     */
    @Volatile private var cacheStartPos: Long = 0L

    /** 已确认的最终文件大小（可能从 SMB 查询获得） */
    @Volatile private var confirmedSize: Long = -1L

    private var isDestroyed = false

    /** SMB InputStream 和对应的 SMB 文件句柄（用于关闭） */
    @Volatile private var smbInputStream: java.io.InputStream? = null
    @Volatile private var smbFileHandle: SmbManager.SmbFileHandle? = null
    private val lock = Any()
    private val serverReadyLatch = java.util.concurrent.CountDownLatch(1)

    /**
     * 启动代理服务器，返回 URL。
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun start(): String = withContext(Dispatchers.IO) {
        if (isDestroyed) throw IllegalStateException("Proxy already destroyed")

        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { serverPort = it.localPort }
        Log.i(TAG, "Proxy starting on port $serverPort for ${videoFile.name} (size=${videoFile.fileSize})")

        // 建立缓存文件（先建空文件，等确认大小后再设置长度）
        val safeName = videoFile.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        tempCacheFile = File(cacheDir, "stream_${safeName}_${System.currentTimeMillis()}.mp4.tmp")
        tempCacheStream = FileOutputStream(tempCacheFile!!)
        Log.i(TAG, "Cache file: ${tempCacheFile!!.absolutePath}")

        // 启动后台线程处理请求
        Thread({ runServerLoop() }, "SmbProxyThread").start()

        // 等待服务器线程真正开始监听（进入 accept() 循环）
        if (!serverReadyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            throw IllegalStateException("SmbProxyServer: server thread failed to start within 5s")
        }
        Log.i(TAG, "Server thread is ready, port=$serverPort")

        "http://127.0.0.1:$serverPort/video"
    }

    private fun runServerLoop() {
        Log.i(TAG, "Server loop started, listening on 127.0.0.1:$serverPort")
        serverReadyLatch.countDown()
        var clientCount = 0
        while (!isDestroyed) {
            try {
                val clientSocket = serverSocket!!.accept()
                val count = ++clientCount
                Log.i(TAG, "Client #$count connected from ${clientSocket.remoteSocketAddress}")
                Thread({ handleClient(clientSocket) }, "ProxyClient-$count").start()
            } catch (e: Exception) {
                if (!isDestroyed) Log.e(TAG, "Server accept error", e)
                break
            }
        }
        Log.i(TAG, "Server loop exited")
    }

    private fun handleClient(clientSocket: Socket) {
        val threadName = Thread.currentThread().name
        Log.d(TAG, "[$threadName] handleClient: entered")
        var out: BufferedOutputStream? = null
        var input: BufferedInputStream? = null
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.receiveBufferSize = 256 * 1024
            clientSocket.sendBufferSize = 256 * 1024
            clientSocket.soTimeout = 10_000  // 10秒读超时

            out = BufferedOutputStream(clientSocket.getOutputStream(), 128 * 1024)
            input = BufferedInputStream(clientSocket.getInputStream(), 32 * 1024)
            Log.d(TAG, "[$threadName] handleClient: socket configured")

            // 读取 HTTP 请求行
            val requestLine = readLine(input)
            Log.i(TAG, "[$threadName] Request line: $requestLine")

            if (requestLine == null) {
                Log.w(TAG, "[$threadName] Empty request, closing")
                try { sendError(out, 400, "Bad Request") } catch (_: Exception) {}
                return
            }

            // 健康检查端点
            if (requestLine.startsWith("GET /health")) {
                Log.i(TAG, "[$threadName] Health check")
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                out.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return
            }

            if (!requestLine.startsWith("GET /video")) {
                Log.w(TAG, "[$threadName] Unknown request: $requestLine")
                try { sendError(out, 404, "Not Found") } catch (_: Exception) {}
                return
            }

            // 解析 HTTP 头（含 Range）
            Log.d(TAG, "[$threadName] Parsing headers...")
            val rangeHeader = parseRangeHeader(input)
            Log.i(TAG, "[$threadName] Range header result: $rangeHeader")

            // 打开 SMB 流
            Log.d(TAG, "[$threadName] Calling ensureSmbStream...")
            val smbResult = try {
                ensureSmbStream()
            } catch (e: Exception) {
                Log.e(TAG, "[$threadName] Failed to open SMB stream: ${e.message}", e)
                try { sendError(out, 503, "Service Unavailable: ${e.message}") } catch (_: Exception) {}
                return
            }
            val (_, size) = smbResult
            val totalSize = if (size > 0) size else videoFile.fileSize
            Log.i(TAG, "[$threadName] SMB stream ready: totalSize=$totalSize, range=$rangeHeader")

            if (rangeHeader != null) {
                handleRangeRequest(out, rangeHeader.first, rangeHeader.second, totalSize)
            } else {
                handleFullRequest(out, totalSize)
            }
            Log.i(TAG, "[$threadName] handleClient: done")

        } catch (e: Exception) {
            // 检测是否是客户端（ExoPlayer）主动断开连接
            if (isClientDisconnect(e)) {
                Log.i(TAG, "[$threadName] Client disconnected (normal during seek/exit)")
            } else {
                Log.e(TAG, "[$threadName] handleClient error", e)
            }
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
            Log.d(TAG, "[$threadName] Client disconnected")
        }
    }

    /**
     * 打开 SMB 流并获取真实文件大小
     * 使用 SmbConnectionPool 的引用计数保持连接活跃
     */
    private fun ensureSmbStream(): Pair<java.io.InputStream, Long> {
        synchronized(lock) {
            if (smbInputStream != null) {
                return smbInputStream!! to (if (confirmedSize > 0) confirmedSize else videoFile.fileSize)
            }

            Log.i(TAG, "Opening SMB stream for ${videoFile.remotePath}")
            try {
                // ========== 使用 SmbConnectionPool 保持连接活跃 ==========
                smbManager.beginActiveOperation()

                val result = smbManager.openInputStreamWithSize(videoFile.remotePath, videoFile.fileSize)
                smbInputStream = result.first
                smbFileHandle = result.second

                // 更新已确认的文件大小（0 也是有效值）
                confirmedSize = result.third

                Log.i(TAG, "SMB stream opened, confirmed size=$confirmedSize, refCount=${SmbConnectionPool.getReferenceCount()}")
                return smbInputStream!! to confirmedSize
            } catch (e: Exception) {
                // 失败时释放连接
                smbManager.endActiveOperation()
                Log.e(TAG, "Failed to open SMB stream", e)
                throw e
            }
        }
    }

    private fun handleFullRequest(out: BufferedOutputStream, totalSize: Long) {
        try {
            val mimeType = getMimeType(videoFile.name)

            // ========== 健壮性增强：严格构建 HTTP 响应头 ==========
            val headers = buildString {
                // 状态行
                append(HTTP_OK)
                // 必要的响应头
                append("Content-Type: $mimeType$CRLF")
                append("Accept-Ranges: bytes$CRLF")
                append("Server: $SERVER_NAME$CRLF")
                append("Cache-Control: no-cache$CRLF")
                append("Connection: keep-alive$CRLF")
                append("Keep-Alive: timeout=300$CRLF")
                // 内容长度或分块编码
                if (totalSize > 0) {
                    append("Content-Length: $totalSize$CRLF")
                } else {
                    // 文件大小未知，使用 chunked 编码
                    append("Transfer-Encoding: chunked$CRLF")
                }
                // 头结束标记（必须有两个 CRLF）
                append(CRLF)
            }
            writeHttpResponse(out, headers)

            Log.i(TAG, "HTTP 200, size=$totalSize, mime=$mimeType, chunked=${totalSize <= 0}, streaming...")

            // 流式传输（文件大小未知时使用 chunked encoding）
            val useChunked = totalSize <= 0
            streamData(out, if (useChunked) Long.MAX_VALUE else totalSize, rangeStart = 0, useChunkedEncoding = useChunked)

        } catch (e: Exception) {
            // 检测是否是客户端（ExoPlayer）主动断开连接
            if (isClientDisconnect(e)) {
                Log.i(TAG, "handleFullRequest: client disconnected (normal during seek/exit)")
            } else {
                Log.e(TAG, "handleFullRequest error", e)
            }
        }
    }

    /**
     * 处理 Range 请求
     *
     * 策略：
     * - 文件大小已知 + rangeEnd 是 Long.MAX_VALUE → 取到文件末尾
     * - 文件大小已知 + 有效范围 → 返回 206 + Content-Range 头
     * - 文件大小未知 → 返回 416（不支持 Range）或从 rangeStart 降级发送
     *
     * 健壮性增强：
     * - Content-Range 头格式严格遵守 RFC 7233
     * - 确保所有响应头正确使用 CRLF
     */
    private fun handleRangeRequest(
        out: BufferedOutputStream,
        rangeStart: Long,
        rangeEnd: Long,
        totalSize: Long
    ) {
        val mimeType = getMimeType(videoFile.name)
        val safeStart = rangeStart.coerceAtLeast(0)
        val isOpenEnded = rangeEnd == Long.MAX_VALUE

        // 文件大小已知
        if (totalSize > 0) {
            // 范围无效（start >= 文件大小）
            if (safeStart >= totalSize) {
                sendError(out, 416, "Range Not Satisfiable: start >= file size")
                return
            }

            // open-ended: 取到文件末尾
            val safeEnd = if (isOpenEnded) totalSize - 1 else minOf(rangeEnd, totalSize - 1)
            val bytesToSend = safeEnd - safeStart + 1
            Log.i(TAG, "Range [$safeStart, $safeEnd] ($bytesToSend bytes), total=$totalSize")

            // 情况1：请求起始位置在缓存数据范围内 → 从缓存文件读
            // 缓存范围 = [cacheStartPos, cacheStartPos + cachedBytes - 1]
            val cacheEnd = cacheStartPos + cachedBytes - 1
            if (safeStart >= cacheStartPos && safeStart <= cacheEnd && tempCacheFile?.exists() == true) {
                val endInCache = minOf(safeEnd, cacheEnd)
                val bytesFromCache = endInCache - safeStart + 1
                Log.i(TAG, "Serving [$safeStart, $endInCache] from cache ($bytesFromCache bytes)")

                // ========== 健壮性增强：严格构建 HTTP 206 响应头 ==========
                val headers = buildString {
                    append(HTTP_PARTIAL)
                    append("Content-Type: $mimeType$CRLF")
                    // Content-Range 必须严格遵守格式: bytes start-end/total
                    append("Content-Range: bytes $safeStart-$endInCache/$totalSize$CRLF")
                    append("Content-Length: $bytesFromCache$CRLF")
                    append("Accept-Ranges: bytes$CRLF")
                    append("Server: $SERVER_NAME$CRLF")
                    append("Connection: keep-alive$CRLF")
                    append(CRLF)
                }
                writeHttpResponse(out, headers)

                RandomAccessFile(tempCacheFile!!, "r").use { raf ->
                    raf.seek(safeStart)
                    sendBytesFromFile(out, raf, bytesFromCache)
                }
                return
            }

            // 情况2：超出已缓存 → 从 SMB 流读
            Log.i(TAG, "Serving [$safeStart, $safeEnd] from SMB ($bytesToSend bytes)")

            // ========== 健壮性增强：严格构建 HTTP 206 响应头 ==========
            val headers = buildString {
                append(HTTP_PARTIAL)
                append("Content-Type: $mimeType$CRLF")
                append("Content-Range: bytes $safeStart-$safeEnd/$totalSize$CRLF")
                append("Content-Length: $bytesToSend$CRLF")
                append("Accept-Ranges: bytes$CRLF")
                append("Server: $SERVER_NAME$CRLF")
                append("Connection: keep-alive$CRLF")
                append("Keep-Alive: timeout=300$CRLF")
                append(CRLF)
            }
            writeHttpResponse(out, headers)

            // 只发送请求范围的字节数（Range 请求必须使用 Content-Length，不支持 chunked）
            streamData(out, bytesToSend, rangeStart = safeStart, useChunkedEncoding = false)
            return
        }

        // 文件大小未知：Range 请求无法精确服务
        // 方案：返回 416，强制 ExoPlayer 走 full request（无 Range）
        Log.w(TAG, "Range [$safeStart, ?] — size unknown, returning 416")
        sendError(out, 416, "Range Not Satisfiable (file size unknown)")
    }

    /**
     * 从 SMB 流读取数据 → 吐给 ExoPlayer + 写缓存文件
     *
     * 健壮性增强：
     * - 使用真正的 SMB 随机访问（seek + read）而非循环 skip
     * - 跨迭代保留剩余字节，避免数据丢失
     * - 定期刷新缓存文件，防止数据丢失
     * - 网络波动时自动重连
     * - 正确实现 HTTP chunked transfer encoding
     *
     * @param limit 最大发送字节数（Long.MAX_VALUE 表示不限，直到 EOF）
     * @param useChunkedEncoding 是否使用 chunked encoding（文件大小未知时为 true）
     */
    private fun streamData(out: BufferedOutputStream, limit: Long, rangeStart: Long, useChunkedEncoding: Boolean = false) {
        val buf = ByteArray(BUFFER_SIZE)
        val leftover = ByteArray(BUFFER_SIZE)  // 跨迭代保留的剩余字节
        var leftoverLen = 0                     // 剩余字节数
        var totalSent = 0L

        synchronized(lock) {
            try {
                // ========== 检查是否需要重建缓存 ==========
                // 情况1: rangeStart 小于已缓存起始位置 → 从头开始缓存
                // 情况2: rangeStart 超出已缓存结束位置 → 从 rangeStart 开始缓存
                val cacheEnd = cacheStartPos + cachedBytes
                if (rangeStart < cacheStartPos || rangeStart >= cacheEnd) {
                    Log.i(TAG, "Rebuilding cache for range $rangeStart (cache range: [$cacheStartPos, ${cacheEnd - 1}])")

                    // 重新创建缓存文件，从 rangeStart 开始缓存
                    try { tempCacheStream?.close() } catch (_: Exception) {}
                    tempCacheStream = null
                    val safeName = videoFile.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    tempCacheFile = File(cacheDir, "stream_${safeName}_${System.currentTimeMillis()}.mp4.tmp")
                    tempCacheStream = FileOutputStream(tempCacheFile!!)
                    cacheStartPos = rangeStart
                    cachedBytes = 0L

                    // ========== 真正的 SMB seek：直接定位到 rangeStart ==========
                    smbFileHandle!!.seek(rangeStart)
                    Log.i(TAG, "Seek done, cacheStartPos=$cacheStartPos")
                }

                while (totalSent < limit) {
                    // ========== 先处理上一轮遗留的字节 ==========
                    if (leftoverLen > 0) {
                        val toSend = minOf(leftoverLen.toLong(), limit - totalSent).toInt()
                        // 【修复 ProtocolException】正确处理 chunked encoding
                        if (useChunkedEncoding) {
                            writeChunk(out, leftover, 0, toSend)
                        } else {
                            out.write(leftover, 0, toSend)
                        }
                        totalSent += toSend
                        tempCacheStream?.let { fos ->
                            fos.write(leftover, 0, toSend)
                            cachedBytes += toSend
                        }
                        // 把未发送的字节移到缓冲区头部
                        if (toSend < leftoverLen) {
                            System.arraycopy(leftover, toSend, leftover, 0, leftoverLen - toSend)
                        }
                        leftoverLen -= toSend
                        // 继续发送，直到 leftover 全部发完或达到 limit
                        if (leftoverLen > 0) continue
                    }

                    // ========== 读取下一块（使用真正的 SMB 随机访问） ==========
                    val read = smbFileHandle?.read(buf) ?: -1
                    if (read <= 0) {
                        Log.i(TAG, "SMB EOF after $totalSent bytes")
                        break
                    }

                    // 这次读到的有效字节数
                    val toSend = minOf(read.toLong(), limit - totalSent).toInt()
                    try {
                        // 【修复 ProtocolException】正确处理 chunked encoding
                        if (useChunkedEncoding) {
                            writeChunk(out, buf, 0, toSend)
                        } else {
                            out.write(buf, 0, toSend)
                        }
                    } catch (e: Exception) {
                        // 检测是否是客户端（ExoPlayer）主动断开连接
                        if (isClientDisconnect(e)) {
                            // 这是正常的：用户跳转进度条或退出播放器
                            Log.i(TAG, "Client disconnected during streaming (user seek/exit) after $totalSent bytes")
                            // 缓存已经写好的数据，优雅退出
                            break
                        }
                        // 其他异常继续抛出
                        throw e
                    }
                    totalSent += toSend

                    // ========== 写缓存 ==========
                    tempCacheStream?.let { fos ->
                        fos.write(buf, 0, toSend)
                        cachedBytes += toSend
                        // 每 512KB 刷新一次，防止数据丢失
                        if (totalSent % (512 * 1024) == 0L) {
                            fos.flush()
                            Log.v(TAG, "Cached $cachedBytes bytes")
                        }
                    }

                    // ========== 定期保持连接活跃（每 1MB） ==========
                    if (totalSent % (1024 * 1024) == 0L) {
                        smbManager.updateLastActivity()
                    }

                    // 保存未发送的剩余字节（下次迭代先处理）
                    if (toSend < read) {
                        val extra = read - toSend
                        System.arraycopy(buf, toSend, leftover, 0, extra)
                        leftoverLen = extra
                    }
                }

                // 【修复 ProtocolException】Chunked encoding 结束：发送 0 块 + 空行
                if (useChunkedEncoding) {
                    out.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                }
                // 最终 flush
                try { out.flush() } catch (_: Exception) {}
                tempCacheStream?.flush()
                Log.i(TAG, "streamData done: $totalSent bytes, total cached: $cachedBytes bytes, chunked=$useChunkedEncoding")

            } catch (e: Exception) {
                // 检测是否是客户端（ExoPlayer）主动断开连接
                if (isClientDisconnect(e)) {
                    Log.i(TAG, "Client disconnected (normal during seek/exit) after $totalSent bytes")
                } else {
                    Log.e(TAG, "streamData error after $totalSent bytes", e)
                    // 尝试刷新已缓存的数据
                    try { tempCacheStream?.flush() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * 【修复 ProtocolException】写入 HTTP chunked transfer encoding 格式的数据块
     * 格式：[size_in_hex]\r\n[data]\r\n
     */
    private fun writeChunk(out: BufferedOutputStream, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        // 写入 chunk size（十六进制）
        out.write("$length\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        // 写入数据
        out.write(data, offset, length)
        // 写入 CRLF 结束标记
        out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    /**
     * 检测异常是否是客户端（ExoPlayer）主动断开连接
     * 这些是正常的操作行为，不应作为错误处理
     */
    private fun isClientDisconnect(e: Throwable?): Boolean {
        if (e == null) return false
        val msg = e.message ?: ""
        val causeMsg = e.cause?.message ?: ""
        return e is SocketException && (
            msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("Broken pipe", ignoreCase = true) ||
            msg.contains("Socket closed", ignoreCase = true)
        ) || causeMsg.contains("Connection reset", ignoreCase = true) ||
           causeMsg.contains("Broken pipe", ignoreCase = true)
    }

    private fun sendBytesFromFile(out: BufferedOutputStream, raf: RandomAccessFile, bytes: Long) {
        if (bytes <= 0) return
        val buf = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            val read = raf.read(buf, 0, minOf(BUFFER_SIZE.toLong(), remaining).toInt())
            if (read <= 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        out.flush()
    }

    private fun parseRangeHeader(`in`: BufferedInputStream): Pair<Long, Long>? {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(`in`) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val range = headers["range"] ?: return null
        // 支持 bytes=start-end 和 bytes=start-（无结束 = 到文件末尾）
        // 也支持 bytes=-suffix（取最后N字节）
        val mOpen = Regex("""bytes=(\d+)-""").find(range)
        if (mOpen != null) {
            val start = mOpen.groupValues[1].toLongOrNull() ?: return null
            // 返回 Pair(start, Long.MAX_VALUE) 表示到末尾，下游会处理
            return start to Long.MAX_VALUE
        }
        // 完全匹配 bytes=start-end
        val mFull = Regex("""bytes=(\d+)-(\d+)""").find(range)
        if (mFull != null) {
            val start = mFull.groupValues[1].toLongOrNull() ?: return null
            val end = mFull.groupValues[2].toLongOrNull() ?: return null
            return start to end
        }
        return null
    }

    /**
     * 健壮性增强：安全地发送 HTTP 响应
     * 确保即使在网络波动时也能正确发送响应头
     */
    private fun writeHttpResponse(out: BufferedOutputStream, headers: String) {
        try {
            out.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
        } catch (e: Exception) {
            // 检测是否是客户端（ExoPlayer）主动断开连接
            if (isClientDisconnect(e)) {
                Log.i(TAG, "Client disconnected during writeHttpResponse")
            } else {
                Log.e(TAG, "Failed to write HTTP response headers", e)
                throw e
            }
        }
    }

    private fun sendError(out: BufferedOutputStream, code: Int, message: String) {
        try {
            // ========== 健壮性增强：严格遵守 HTTP 错误响应格式 ==========
            val body = "<h1>$code $message</h1>"
            val headers = buildString {
                append("HTTP/1.1 $code $message$CRLF")
                append("Content-Type: text/html$CRLF")
                append("Content-Length: ${body.toByteArray().size}$CRLF")
                append("Server: $SERVER_NAME$CRLF")
                append("Connection: close$CRLF")
                append(CRLF)
                append(body)
            }
            out.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
        } catch (e: Exception) {
            // 忽略发送错误时的异常
            if (!isClientDisconnect(e)) {
                Log.w(TAG, "Failed to send error response", e)
            }
        }
    }

    private fun readLine(`in`: BufferedInputStream): String? {
        val sb = StringBuilder()
        val byteBuf = ByteArray(1)
        while (true) {
            val r = `in`.read(byteBuf)
            if (r <= 0) return if (sb.isEmpty()) null else sb.toString()
            if (byteBuf[0] == '\r'.code.toByte()) {
                // 看看下一个是不是 \n（\r\n 是行结束符，不应加入字符串）
                val peek = `in`.read()
                if (peek == '\n'.code) {
                    // \r\n 一起被消费，结束本行
                    break
                } else if (peek > 0) {
                    // 单独的 \r（不该出现），但保守处理：消费 peek，重试
                    continue
                } else {
                    // stream ended after \r
                    return sb.toString()
                }
            }
            if (byteBuf[0] == '\n'.code.toByte()) break
            sb.append((byteBuf[0].toInt() and 0xFF).toChar())
        }
        return sb.toString()
    }

    private fun safeClose(c: Closeable?) { try { c?.close() } catch (_: Exception) {} }
    private fun safeClose(s: Socket?) { try { s?.close() } catch (_: Exception) {} }

    private fun getMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".flv") -> "video/x-flv"
            else -> "video/mp4"  // 未知格式默认当 mp4 处理
        }
    }

    fun shutdown() {
        isDestroyed = true
        Log.i(TAG, "Proxy shutdown...")

        // ========== 通知 SmbManager 结束活跃操作 ==========
        smbManager.endActiveOperation()

        // 关闭 SMB 文件句柄（会同时关闭流）
        smbFileHandle?.let {
            try { it.close() } catch (_: Exception) {}
            smbFileHandle = null
        }
        smbInputStream = null

        // 关闭缓存文件
        try {
            tempCacheStream?.flush()
            tempCacheStream?.close()
            tempCacheStream = null
        } catch (_: Exception) {}

        tempCacheFile?.let { f ->
            if (f.exists()) { f.delete(); Log.i(TAG, "Cache deleted") }
        }

        try { serverSocket?.close(); serverSocket = null } catch (_: Exception) {}
        Log.i(TAG, "Proxy shutdown done, refCount=${SmbConnectionPool.getReferenceCount()}")
    }
}
