package cn.geektang.privacyspace.util

import android.content.Context
import android.util.Log
import cn.geektang.privacyspace.bean.ConfigData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConfigClient(context: Context) {
    private val packageManager = context.packageManager

    fun serverVersion(): Int {
        return connectServer(ConfigServer.QUERY_SERVER_VERSION)?.toIntOrNull() ?: -1
    }

    fun rebootTheSystem() {
        connectServer(ConfigServer.REBOOT_THE_SYSTEM)
    }

    suspend fun migrateOldConfig() {
        withContext(Dispatchers.IO) {
            connectServer(ConfigServer.MIGRATE_OLD_CONFIG_FILE)
        }
    }

    suspend fun queryConfig(): ConfigData? {
        return withContext(Dispatchers.IO) {
            val configJson = connectServer(ConfigServer.QUERY_CONFIG)
            if (configJson.isNullOrBlank()) {
                return@withContext null
            }
            return@withContext try {
                JsonHelper.getConfigAdapter().fromJson(configJson)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.d("PrivacySpace", "Config is invalid.")
                null
            }
        }
    }

    suspend fun updateConfig(configData: ConfigData) {
        withContext(Dispatchers.IO) {
            val configJson = JsonHelper.getConfigAdapter().toJson(configData)
            connectServer("${ConfigServer.UPDATE_CONFIG}$configJson")
        }
    }

    fun forceStop(packageName: String): Boolean {
        return connectServer("${ConfigServer.FORCE_STOP}$packageName") == ConfigServer.EXEC_SUCCEED
    }

    private fun connectServer(methodName: String): String? {
        return try {
            packageManager.getInstallerPackageName(methodName)
        } catch (e: Exception) {
            null
        }
    }
}