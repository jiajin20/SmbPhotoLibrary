package com.example.smbphoto.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.smbphoto.data.model.ServerConfig
import com.example.smbphoto.util.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 服务器配置界面的 ViewModel
 *
 * v2: 支持多服务器配置管理（增删改查）
 */
@HiltViewModel
class ServerConfigViewModel @Inject constructor(
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()

    /**
     * 校验输入并构建 ServerConfig
     *
     * @return 校验通过返回 ServerConfig，否则返回 null 并更新 validationError
     */
    fun buildConfig(
        name: String,
        serverIp: String,
        shareName: String,
        username: String,
        password: String,
        rootPath: String,
        rememberPassword: Boolean
    ): ServerConfig? {
        when {
            serverIp.isBlank() -> {
                _validationError.value = "请输入服务器 IP 地址"
                return null
            }
            shareName.isBlank() -> {
                _validationError.value = "请输入共享目录名称"
                return null
            }
        }

        if (rememberPassword && username.isNotBlank()) {
            credentialStore.saveCredentials(serverIp, username, password)
        }

        _validationError.value = null
        return ServerConfig(
            name = name.ifBlank { serverIp },
            serverIp = serverIp.trim(),
            shareName = shareName.trim(),
            username = username.trim(),
            password = password,
            rootPath = rootPath.trim()
        )
    }

    // ==================== 多配置管理 (v2) ====================

    /** 获取所有已保存的服务器配置 */
    fun getAllConfigs(): List<ServerConfig> = credentialStore.getServerConfigs()

    /** 保存/更新一个服务器配置 */
    fun saveConfig(config: ServerConfig) {
        credentialStore.addServerConfig(config)
    }

    /** 删除指定 ID 的服务器配置 */
    fun removeConfig(id: Long) {
        credentialStore.removeServerConfig(id)
    }

    /** 获取指定 ID 的配置 */
    fun getConfigById(id: Long): ServerConfig? = credentialStore.getServerConfigById(id)

    // ==================== 旧接口兼容 ====================

    /** 读取第一个已保存的完整配置（用于自动重连） */
    fun getSavedConfig(): ServerConfig? = credentialStore.getFirstServerConfig()

    /** 是否有已保存的配置 */
    fun hasSavedConfig(): Boolean = credentialStore.hasSavedConfig()

    /** 清除所有已保存的配置 */
    fun clearSavedConfig() {
        credentialStore.clearSavedConfig()
    }

    /**
     * 尝试加载已保存的凭据
     */
    fun loadSavedCredentials(serverIp: String): Pair<String, String>? =
        credentialStore.loadCredentials(serverIp)
}
