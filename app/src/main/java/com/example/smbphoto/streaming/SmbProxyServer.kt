package com.example.smbphoto.streaming

import android.util.Log
import com.example.smbphoto.data.model.SmbImageFile
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
        private const val BUFFER_SIZE = 64 * 1024  // 64KB
        private const val HTTP_OK = "HTTP/1.1 200 OK\r\n"
        private const val HTTP_PARTIAL = "HTTP/1.1 206 Partial Content\r\n"
        private const val HTTP_NOT_FOUND = "HTTP/1.1 404 Not Found\r\n"
        private const val SERVER_NAME = "SmbProxy/1.0"
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
            clientSocket.soTimeout = 10_000  // 10秒读超时（原为60秒太长）

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
            Log.e(TAG, "[$threadName] handleClient error", e)
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
            Log.d(TAG, "[$threadName] Client disconnected")
        }
    }

    /**
     * 打开 SMB 流并获取真实文件大小
     */
    private fun ensureSmbStream(): Pair<java.io.InputStream, Long> {
        synchronized(lock) {
            if (smbInputStream != null) {
                return smbInputStream!! to (if (confirmedSize > 0) confirmedSize else videoFile.fileSize)
            }

            Log.i(TAG, "Opening SMB stream for ${videoFile.remotePath}")
            try {
                val result = smbManager.openInputStreamWithSize(videoFile.remotePath, videoFile.fileSize)
                smbInputStream = result.first
                smbFileHandle = result.second

                // 更新已确认的文件大小（0 也是有效值）
                confirmedSize = result.third

                // 注意：不预分配缓存文件大小！缓存文件大小 == 实际写入字节数，
                // 这样当 RandomAccessFile.seek() 超出实际数据时会发生 IOException，
                // 自然回退到 SMB 流（而非读出一堆 0 导致 ExoPlayer 静默失败）。
                Log.i(TAG, "SMB stream opened, confirmed size=$confirmedSize")
                return smbInputStream!! to confirmedSize
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open SMB stream", e)
                throw e
            }
        }
    }

    private fun handleFullRequest(out: BufferedOutputStream, totalSize: Long) {
        try {
            val mimeType = getMimeType(videoFile.name)
            val headers = buildString {
                append(HTTP_OK)
                append("Content-Type: $mimeType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Server: $SERVER_NAME\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: keep-alive\r\n")
                append("Keep-Alive: timeout=300\r\n")
                if (totalSize > 0) {
                    append("Content-Length: $totalSize\r\n")
                } else {
                    // 文件大小未知，使用 chunked 编码
                    append("Transfer-Encoding: chunked\r\n")
                }
                append("\r\n")
            }
            out.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            Log.i(TAG, "HTTP 200, size=$totalSize, mime=$mimeType, streaming...")

            // 流式传输
            streamData(out, if (totalSize > 0) totalSize else Long.MAX_VALUE, rangeStart = 0)

        } catch (e: Exception) {
            Log.e(TAG, "handleFullRequest error", e)
        }
    }

    /**
     * 处理 Range 请求
     *
     * 策略：
     * - 文件大小已知 + rangeEnd 是 Long.MAX_VALUE → 取到文件末尾
     * - 文件大小已知 + 有效范围 → 返回 206 + Content-Range 头
     * - 文件大小未知 → 返回 416（不支持 Range）或从 rangeStart 降级发送
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
                sendError(out, 416, "Range Not Satisfiable")
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

                val headers = buildString {
                    append(HTTP_PARTIAL)
                    append("Content-Type: $mimeType\r\n")
                    append("Content-Range: bytes $safeStart-$endInCache/$totalSize\r\n")
                    append("Content-Length: $bytesFromCache\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Server: $SERVER_NAME\r\n")
                    append("Connection: keep-alive\r\n")
                    append("\r\n")
                }
                out.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()

                RandomAccessFile(tempCacheFile!!, "r").use { raf ->
                    raf.seek(safeStart)
                    sendBytesFromFile(out, raf, bytesFromCache)
                }
                return
            }

            // 情况2：超出已缓存 → 从 SMB 流读
            Log.i(TAG, "Serving [$safeStart, $safeEnd] from SMB ($bytesToSend bytes)")
            val headers = buildString {
                append(HTTP_PARTIAL)
                append("Content-Type: $mimeType\r\n")
                append("Content-Range: bytes $safeStart-$safeEnd/$totalSize\r\n")
                append("Content-Length: $bytesToSend\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Server: $SERVER_NAME\r\n")
                append("Connection: keep-alive\r\n")
                append("Keep-Alive: timeout=300\r\n")
                append("\r\n")
            }
            out.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()

            // 只发送请求范围的字节数
            streamData(out, bytesToSend, rangeStart = safeStart)
            return
        }

        // 文件大小未知：Range 请求无法精确服务
        // 方案：返回 416，强制 ExoPlayer 走 full request（无 Range）
        Log.w(TAG, "Range [$safeStart, ?] — size unknown, returning 416")
        sendError(out, 416, "Range Not Satisfiable (file size unknown)")
    }

    /**
     * 从 SMB 流读取数据 → 吐给 ExoPlayer + 写缓存文件
     * @param limit 最大发送字节数（Long.MAX_VALUE 表示不限，直到 EOF）
     */
    private fun streamData(out: BufferedOutputStream, limit: Long, rangeStart: Long) {
        val buf = ByteArray(BUFFER_SIZE)
        val leftover = ByteArray(BUFFER_SIZE)  // 跨迭代保留的剩余字节
        var leftoverLen = 0                     // 剩余字节数
        var totalSent = 0L

        synchronized(lock) {
            try {
                // 如果 rangeStart 超出已缓存范围，使用真正 seek 定位（废除傻跳）
                val cacheEnd = cacheStartPos + cachedBytes
                if (rangeStart >= cacheEnd) {
                    Log.i(TAG, "Seek to $rangeStart (cache range: [$cacheStartPos, ${cacheEnd - 1}])")

                    // 重新创建缓存文件，从 rangeStart 开始缓存
                    try { tempCacheStream?.close() } catch (_: Exception) {}
                    tempCacheStream = null
                    tempCacheFile = File(cacheDir, "stream_${videoFile.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}_${System.currentTimeMillis()}.mp4.tmp")
                    tempCacheStream = FileOutputStream(tempCacheFile!!)
                    cacheStartPos = rangeStart
                    cachedBytes = 0L

                    // 真正的 SMB seek：直接定位到 rangeStart，不涉及任何网络 IO 读取丢弃
                    smbFileHandle!!.seek(rangeStart)
                    Log.i(TAG, "Seek done, cacheStartPos=$cacheStartPos")
                }

                while (totalSent < limit) {
                    // 先处理上一轮遗留的字节
                    if (leftoverLen > 0) {
                        val toSend = minOf(leftoverLen.toLong(), limit - totalSent).toInt()
                        out.write(leftover, 0, toSend)
                        out.flush()
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
                        if (leftoverLen > 0) continue  // 还有剩余，继续发
                    }

                    // 读取下一块（使用真正的 SMB 随机访问，无需傻跳）
                    val read = smbFileHandle?.read(buf) ?: -1
                    if (read <= 0) {
                        Log.i(TAG, "SMB EOF after $totalSent bytes")
                        break
                    }

                    // 这次读到的有效字节数
                    val toSend = minOf(read.toLong(), limit - totalSent).toInt()
                    out.write(buf, 0, toSend)
                    out.flush()
                    totalSent += toSend

                    // 写缓存
                    tempCacheStream?.let { fos ->
                        fos.write(buf, 0, toSend)
                        cachedBytes += toSend
                        if (totalSent % (512 * 1024) == 0L) {
                            fos.flush()
                            Log.v(TAG, "Cached $cachedBytes bytes")
                        }
                    }

                    // 保存未发送的剩余字节（下次迭代先处理）
                    if (toSend < read) {
                        val extra = read - toSend
                        System.arraycopy(buf, toSend, leftover, 0, extra)
                        leftoverLen = extra
                    }
                }

                Log.i(TAG, "streamData done: $totalSent bytes")

            } catch (e: Exception) {
                Log.e(TAG, "streamData error after $totalSent bytes", e)
            }
        }
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

    private fun sendError(out: BufferedOutputStream, code: Int, message: String) {
        try {
            val body = "<h1>$code $message</h1>"
            val resp = "HTTP/1.1 $code $message\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Server: $SERVER_NAME\r\n" +
                "Connection: close\r\n" +
                "\r\n" + body
            out.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {}
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
                    // 下一轮循环会在 peek 位置继续读
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
        Log.i(TAG, "Proxy shutdown done")
    }
}
