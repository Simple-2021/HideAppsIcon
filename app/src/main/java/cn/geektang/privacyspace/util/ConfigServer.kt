package cn.geektang.privacyspace.util

import android.app.ActivityThread
import android.app.IActivityManager
import android.content.pm.PackageManager
import android.os.Binder
import android.os.ServiceManager
import android.os.SystemProperties
import cn.geektang.privacyspace.BuildConfig
import cn.geektang.privacyspace.ConfigConstant
import cn.geektang.privacyspace.hook.HookMain
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

class ConfigServer : XC_MethodHook() {
    companion object {
        const val QUERY_SERVER_VERSION = "serverVersion"
        const val MIGRATE_OLD_CONFIG_FILE = "migrateOldConfigFile"
        const val QUERY_CONFIG = "queryConfig"
        const val UPDATE_CONFIG = "updateConfig:"
        const val REBOOT_THE_SYSTEM = "rebootTheSystem"
        const val FORCE_STOP = "forceStop:"

        const val EXEC_SUCCEED = "1"
        const val EXEC_FAILED = "0"
    }

    private var amsClass: Class<*>? = null
    private var pmsClass: Class<*>? = null

    fun start(classLoader: ClassLoader) {
        pmsClass = HookUtil.loadPms(classLoader)
        amsClass = try {
            classLoader.tryLoadClass("com.android.server.am.ActivityManagerService")
        } catch (e: ClassNotFoundException) {
            null
        }
        if (pmsClass == null) {
            XposedBridge.log("PackageManagerService not found, config server start failed.")
            return
        }

        XposedHelpers.findAndHookMethod(
            pmsClass,
            "getInstallerPackageName",
            String::class.java,
            this
        )

        XposedHelpers.findAndHookMethod(
            pmsClass,
            "checkUidPermission",
            String::class.java,
            Int::class.javaPrimitiveType,
            this
        )

        if (amsClass != null) {
            XposedHelpers.findAndHookMethod(
                amsClass,
                "checkPermission",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                this
            )
        }
    }

    override fun beforeHookedMethod(param: MethodHookParam) {
        when (param.method.name) {
            "getInstallerPackageName" -> {
                hookGetInstallerPackageName(param)
            }
            "checkUidPermission" -> {
                if (param.args[1] == getClientUid()) {
                    if (HookMain.enableLog) {
                        XposedBridge.log("Granted permission by hook.")
                    }
                    param.result = PackageManager.PERMISSION_GRANTED
                }
            }
            "checkPermission" -> {
                if (param.args[2] == getClientUid()) {
                    if (HookMain.enableLog) {
                        XposedBridge.log("Granted permission by hook.")
                    }
                    param.result = PackageManager.PERMISSION_GRANTED
                }
            }
            else -> {}
        }
    }

    private fun hookGetInstallerPackageName(param: MethodHookParam) {
        if (Binder.getCallingUid() != getClientUid() && !BuildConfig.DEBUG) {
            return
        }
        val firstArg = param.args.first()?.toString() ?: return
        when {
            firstArg == QUERY_SERVER_VERSION -> {
                param.result = BuildConfig.VERSION_CODE.toString()
            }
            firstArg == MIGRATE_OLD_CONFIG_FILE -> {
                tryMigrateOldConfig()
                param.result = ""
            }
            firstArg == QUERY_CONFIG -> {
                param.result = queryConfig()
            }
            firstArg == REBOOT_THE_SYSTEM -> {
                SystemProperties.set("sys.powerctl", "reboot")
                param.result = ""
            }
            firstArg.startsWith(FORCE_STOP) -> {
                val targetPackageName = firstArg.substring(FORCE_STOP.length)
                try {
                    val activityManager = ServiceManager.getService("activity") as IActivityManager
                    activityManager.forceStopPackage(targetPackageName, 0)
                    param.result = EXEC_SUCCEED
                } catch (e: Throwable) {
                    XposedBridge.log("Force stop '${targetPackageName}' error.")
                    XposedBridge.log(e)
                    param.result = EXEC_FAILED
                }
            }
            firstArg.startsWith(UPDATE_CONFIG) -> {
                val arg = firstArg.substring(UPDATE_CONFIG.length)
                updateConfig(arg)
                param.result = ""
            }
        }
    }

    private fun queryConfig(): String {
        val configFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        return try {
            configFile.readText()
        } catch (e: Exception) {
            ""
        }
    }

    private fun updateConfig(configJson: String) {
        val configFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        configFile.parentFile?.mkdirs()
        try {
            val configData = JsonHelper.getConfigAdapter().fromJson(configJson)
            if (null != configData) {
                HookMain.updateConfigData(configData)
                configFile.writeText(configJson)
            }
        } catch (e: Exception) {
            XposedBridge.log("Update config error.")
            XposedBridge.log(e)
        }
    }

    private fun getClientUid(): Int {
        return ActivityThread.getPackageManager()
            .getPackageUid(BuildConfig.APPLICATION_ID, 0, 0)
    }

    private fun tryMigrateOldConfig() {
        val originalFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER_ORIGINAL}${ConfigConstant.CONFIG_FILE_JSON}")
        val newConfigFile =
            File("${ConfigConstant.CONFIG_FILE_FOLDER}${ConfigConstant.CONFIG_FILE_JSON}")
        if (!newConfigFile.exists()) {
            newConfigFile.parentFile?.mkdirs()
            originalFile.copyTo(newConfigFile)
        }
    }
}