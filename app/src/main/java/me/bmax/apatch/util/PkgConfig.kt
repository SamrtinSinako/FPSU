package me.bmax.apatch.util

import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import me.bmax.apatch.APApplication
import me.bmax.apatch.Natives
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import kotlin.concurrent.thread

object PkgConfig {
    private const val TAG = "PkgConfig"

    private const val CSV_HEADER = "pkg,exclude,allow,uid,to_uid,sctx"

    @Immutable
    @Parcelize
    @Keep
    data class Config(
        var pkg: String = "", var exclude: Int = 0, var allow: Int = 0, var profile: Natives.Profile
    ) : Parcelable {
        companion object {
            fun fromLine(line: String): Config {
                val sp = line.split(",")
                val profile = Natives.Profile(sp[3].toInt(), sp[4].toInt(), sp[5])
                return Config(sp[0], sp[1].toInt(), sp[2].toInt(), profile)
            }

            fun fromJson(obj: JSONObject): Config {
                val pkg = obj.optString("pkg", "")
                val exclude = obj.optInt("exclude", 0)
                val allow = obj.optInt("allow", 0)
                val uid = obj.optInt("uid", 0)
                val toUid = obj.optInt("toUid", 0)
                val scontext = obj.optString("scontext", APApplication.DEFAULT_SCONTEXT)
                val profile = Natives.Profile(uid, toUid, scontext)
                return Config(pkg, exclude, allow, profile)
            }

            fun toJson(config: Config): JSONObject {
                return JSONObject().apply {
                    put("pkg", config.pkg)
                    put("exclude", config.exclude)
                    put("allow", config.allow)
                    put("uid", config.profile.uid)
                    put("toUid", config.profile.toUid)
                    put("scontext", config.profile.scontext)
                }
            }
        }

        fun isDefault(): Boolean {
            return allow == 0 && exclude == 0
        }
    }

    fun readConfigs(): HashMap<Int, Config> {
        val configs = HashMap<Int, Config>()
        val file = File(APApplication.PACKAGE_CONFIG_FILE)
        if (!file.exists()) return configs
        val content = file.readText().trim()
        if (content.isEmpty()) return configs

        if (content.startsWith("[")) {
            readConfigsJson(content, configs)
        } else {
            readConfigsCsv(content, configs)
        }
        return configs
    }

    private fun readConfigsJson(content: String, configs: HashMap<Int, Config>) {
        try {
            val jsonArray = JSONArray(content)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val config = Config.fromJson(obj)
                if (!config.isDefault()) {
                    configs[config.profile.uid] = config
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON config", e)
        }
    }

    // 旧版 CSV 回退 —— 仅用于升级前的老用户。
    // 首次 writeConfigs() 写入 JSON 后，此路径不再触发。
    // 保留至少一个完整大版本周期后再删除。
    private fun readConfigsCsv(content: String, configs: HashMap<Int, Config>) {
        val lines = content.lines()
        val dataLines = if (lines.firstOrNull() == CSV_HEADER) lines.drop(1) else lines
        dataLines.filter { it.isNotBlank() }.forEach { line ->
            try {
                val config = Config.fromLine(line)
                if (!config.isDefault()) {
                    configs[config.profile.uid] = config
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed CSV line: $line", e)
            }
        }
    }

    private fun writeConfigs(configs: HashMap<Int, Config>) {
        val file = File(APApplication.PACKAGE_CONFIG_FILE)
        if (!file.parentFile?.exists()!!) file.parentFile?.mkdirs()
        val tmpFile = File.createTempFile("package_config", ".tmp", file.parentFile)
        try {
            val jsonArray = JSONArray()
            configs.values.forEach {
                if (!it.isDefault()) {
                    jsonArray.put(Config.toJson(it))
                }
            }
            val content = jsonArray.toString(2)
            FileWriter(tmpFile, false).use { w -> w.write(content) }
            if (!tmpFile.renameTo(file)) {
                throw IllegalStateException("Failed to rename temp file to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    fun changeConfig(config: Config) {
        synchronized(PkgConfig.javaClass) {
            Natives.su()
            val configs = readConfigs()
            val uid = config.profile.uid
            if (config.allow == 1) {
                config.exclude = 0
            }
            if (config.isDefault() && configs[uid] != null) {
                configs.remove(uid)
            } else {
                Log.d(TAG, "change config: $config")
                configs[uid] = config
            }
            writeConfigs(configs)
        }
    }

    fun batchChangeConfigs(newConfigs: List<Config>) {
        thread {
            updateConfigs(newConfigs)
        }
    }

    fun updateConfigs(newConfigs: List<Config>) {
        synchronized(PkgConfig.javaClass) {
            Natives.su()
            val configs = readConfigs()

            newConfigs.forEach { config ->
                val uid = config.profile.uid
                // Root App should not be excluded
                if (config.allow == 1) {
                    config.exclude = 0
                }

                if (config.isDefault() && configs[uid] != null) {
                    configs.remove(uid)
                } else {
                    configs[uid] = config
                }
            }
            writeConfigs(configs)
        }
    }

    fun overwriteConfigs(newConfigs: List<Config>) {
        synchronized(PkgConfig.javaClass) {
            val configMap = HashMap<Int, Config>()
            newConfigs.forEach { config ->
                // Root App should not be excluded
                if (config.allow == 1) {
                    config.exclude = 0
                }
                if (!config.isDefault()) {
                    configMap[config.profile.uid] = config
                }
            }
            writeConfigs(configMap)
        }
    }
}
