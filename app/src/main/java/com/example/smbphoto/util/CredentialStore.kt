package com.example.smbphoto.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.smbphoto.data.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CredentialStore"
private const val PREFS_FILE = "smb_credentials"
private const val KEY_SAVED_CONFIG_PREFIX = "saved_cfg_"
private const val KEY_SERVER_CONFIGS = "server_configs_v2"

/**
 * 使用 EncryptedSharedPreferences + Android Keystore 安全存储 SMB 凭据和配置
 *
 * v2: 支持多服务器配置管理（JSON 数组持久化）
 * v1: 兼容旧的单配置 key 格式，自动迁移
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to plain", e)
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            }
        } else {
            Log.w(TAG, "API < 23, using plain SharedPreferences (no encryption)")
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    // ==================== 多服务器配置管理 (v2) ====================

    /** 获取所有已保存的服务器配置列表 */
    fun getServerConfigs(): List<ServerConfig> {
        // 先尝试读取 v2 JSON 格式
        val jsonStr = prefs.getString(KEY_SERVER_CONFIGS, null)
        if (!jsonStr.isNullOrBlank()) {
            return try {
                parseConfigJson(jsonStr)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse server configs JSON", e)
                emptyList()
            }
        }

        // 兼容旧 v1：从单配置 key 迁移
        migrateV1ToV2()?.let { return it }
        return emptyList()
    }

    /** 保存服务器配置列表（全量替换） */
    fun saveServerConfigs(configs: List<ServerConfig>) {
        val jsonArray = JSONArray()
        for (config in configs) {
            jsonArray.put(configToJson(config))
        }
        prefs.edit().putString(KEY_SERVER_CONFIGS, jsonArray.toString()).apply()
    }

    /** 添加一个服务器配置 */
    fun addServerConfig(config: ServerConfig): List<ServerConfig> {
        val configs = getServerConfigs().toMutableList()
        // 检查是否已存在相同 IP+Share 的配置，存在则替换
        val existingIndex = configs.indexOfFirst {
            it.serverIp == config.serverIp && it.shareName == config.shareName
        }
        if (existingIndex >= 0) {
            configs[existingIndex] = config
        } else {
            configs.add(config)
        }
        saveServerConfigs(configs)
        return configs
    }

    /** 删除指定 ID 的服务器配置 */
    fun removeServerConfig(id: Long): List<ServerConfig> {
        val configs = getServerConfigs().filter { it.id != id }
        saveServerConfigs(configs)
        return configs
    }

    /** 获取指定 ID 的配置 */
    fun getServerConfigById(id: Long): ServerConfig? =
        getServerConfigs().find { it.id == id }

    /** 获取第一个保存的配置（用于默认连接） */
    fun getFirstServerConfig(): ServerConfig? = getServerConfigs().firstOrNull()

    // ==================== 旧接口兼容（v1 单配置） ====================

    /** 保存完整的服务器配置（旧接口，内部调用 v2） */
    fun saveServerConfig(config: ServerConfig) {
        addServerConfig(config)
    }

    /** 读取已保存的服务器配置；未找到返回 null（兼容旧调用） */
    fun getSavedServerConfig(): ServerConfig? = getFirstServerConfig()

    /** 清除已保存的所有服务器配置 */
    fun clearSavedConfig() {
        saveServerConfigs(emptyList())
        // 也清理旧的 v1 key
        val editor = prefs.edit()
        val keys = prefs.all.keys.filter { it.startsWith(KEY_SAVED_CONFIG_PREFIX) }
        for (key in keys) { editor.remove(key) }
        editor.apply()
    }

    /** 是否已有保存的配置 */
    fun hasSavedConfig(): Boolean = getServerConfigs().isNotEmpty()

    // ==================== 凭据存储（保持不变） ====================

    /** 保存指定服务器的凭据 */
    fun saveCredentials(serverIp: String, username: String, password: String) {
        prefs.edit()
            .putString("${serverIp}_user", username)
            .putString("${serverIp}_pass", password)
            .apply()
    }

    /** 读取指定服务器的凭据；未找到返回 null */
    fun loadCredentials(serverIp: String): Pair<String, String>? {
        val user = prefs.getString("${serverIp}_user", null) ?: return null
        val pass = prefs.getString("${serverIp}_pass", "") ?: ""
        return Pair(user, pass)
    }

    /** 删除指定服务器的保存凭据 */
    fun removeCredentials(serverIp: String) {
        prefs.edit()
            .remove("${serverIp}_user")
            .remove("${serverIp}_pass")
            .apply()
    }

    // ==================== JSON 序列化/反序列化 ====================

    private fun configToJson(config: ServerConfig): JSONObject = JSONObject().apply {
        put("id", config.id)
        put("name", config.name)
        put("serverIp", config.serverIp)
        put("shareName", config.shareName)
        put("username", config.username)
        put("password", config.password)
        put("rootPath", config.rootPath)
        put("port", config.port)
    }

    private fun parseConfigJson(jsonStr: String): List<ServerConfig> {
        val array = JSONArray(jsonStr)
        val result = mutableListOf<ServerConfig>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                ServerConfig(
                    id = obj.optLong("id", System.currentTimeMillis() + i),
                    name = obj.optString("name", ""),
                    serverIp = obj.optString("serverIp", ""),
                    shareName = obj.optString("shareName", ""),
                    username = obj.optString("username", ""),
                    password = obj.optString("password", ""),
                    rootPath = obj.optString("rootPath", ""),
                    port = obj.optInt("port", 445)
                )
            )
        }
        return result
    }

    /** 将旧的 v1 单配置数据迁移到 v2 JSON 格式（一次性） */
    private fun migrateV1ToV2(): List<ServerConfig>? {
        val prefix = KEY_SAVED_CONFIG_PREFIX
        val ip = prefs.getString("${prefix}serverIp", null) ?: return null
        val shareName = prefs.getString("${prefix}shareName", null) ?: return null

        val config = ServerConfig(
            name = prefs.getString("${prefix}name", "") ?: "",
            serverIp = ip,
            shareName = shareName,
            username = prefs.getString("${prefix}username", "") ?: "",
            password = prefs.getString("${prefix}password", "") ?: "",
            rootPath = prefs.getString("${prefix}rootPath", "") ?: ""
        )

        saveServerConfigs(listOf(config))
        Log.i(TAG, "Migrated v1 config to v2 format")

        // 清理旧 key
        val editor = prefs.edit()
        val oldKeys = prefs.all.keys.filter { it.startsWith(KEY_SAVED_CONFIG_PREFIX) }
        for (key in oldKeys) { editor.remove(key) }
        editor.apply()

        return listOf(config)
    }
}
