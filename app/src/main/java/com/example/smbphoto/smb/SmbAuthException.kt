package com.example.smbphoto.smb

/**
 * SMB 认证异常（不可恢复）
 *
 * 当遇到认证失败（如账号密码错误）时抛出此异常。
 * 与网络波动异常不同，认证异常不应该触发重试，因为重试也不会成功。
 *
 * 特点：
 * - 不可恢复：需要用户重新输入正确的账号密码
 * - 触发熔断：连接池会标记 FAILED_AUTH 状态，防止无限重试
 * - 友好提示：UI 层应提示用户重新登录，而不是显示技术性错误
 *
 * 使用场景：
 * - 账号或密码错误
 * - 用户权限不足
 * - 认证令牌过期
 */
class SmbAuthException @JvmOverloads constructor(
    message: String = "SMB 认证失败，请检查账号密码",
    cause: Throwable? = null
) : Exception(message, cause) {

    companion object {
        private const val serialVersionUID = 1L

        /**
         * 从 SMBApiException 中提取认证失败的错误信息
         */
        fun fromSmbException(e: Throwable): SmbAuthException {
            val msg = e.message ?: "SMB 认证失败"
            return when {
                msg.contains("LOGON_FAILURE", ignoreCase = true) ||
                msg.contains("Access denied", ignoreCase = true) ->
                    SmbAuthException("账号或密码错误，请重新输入", e)
                msg.contains("permission", ignoreCase = true) ||
                msg.contains("privilege", ignoreCase = true) ->
                    SmbAuthException("权限不足，无法访问共享目录", e)
                msg.contains("timeout", ignoreCase = true) ->
                    SmbAuthException("连接超时，请检查网络", e)
                else ->
                    SmbAuthException("SMB 认证失败: $msg", e)
            }
        }
    }
}
