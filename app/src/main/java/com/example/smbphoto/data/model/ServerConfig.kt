package com.example.smbphoto.data.model

/**
 * 服务器连接配置
 */
data class ServerConfig(
    val id: Long = System.currentTimeMillis(),
    val name: String,          // 用户自定义名称，如"家里的NAS"
    val serverIp: String,      // 服务器IP地址
    val shareName: String,     // 共享目录名称
    val username: String,      // 用户名（可为空，表示匿名访问）
    val password: String = "", // 密码（不持久化，运行时传入）
    val rootPath: String = "", // 起始浏览路径
    val port: Int = 445        // SMB端口，默认445
)
