package io.github.dovecoteescapee.byedpi.openflux

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object OpenFluxManager {
    private const val PREFS_NAME = "openflux_preferences"
    private const val KEY_PROFILES = "openflux_profiles_json"
    private const val KEY_SELECTED_ID = "openflux_selected_profile_id"
    private const val KEY_ROUTING_MODE = "openflux_routing_mode" // "vpn" or "proxy_only"

    fun getConfigs(context: Context): List<OpenFluxConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PROFILES, null)
        if (jsonStr.isNullOrBlank()) {
            val defaults = createDefaultConfigs()
            saveConfigs(context, defaults)
            if (getSelectedId(context) == null && defaults.isNotEmpty()) {
                setSelectedId(context, defaults[0].id)
            }
            return defaults
        }

        val list = mutableListOf<OpenFluxConfig>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(OpenFluxConfig.fromJson(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun createDefaultConfigs(): List<OpenFluxConfig> {
        return listOf(
            OpenFluxConfig(
                id = UUID.randomUUID().toString(),
                name = "Яндекс.Документы (Б/С)",
                mode = "classic",
                transportType = "yandex",
                documentUrl = "",
                encryptionSecret = "",
                codec = "batched"
            ),
            OpenFluxConfig(
                id = UUID.randomUUID().toString(),
                name = "Яндекс.Волга (vyandex)",
                mode = "classic",
                transportType = "vyandex",
                documentUrl = "",
                encryptionSecret = "",
                codec = "batched"
            ),
            OpenFluxConfig(
                id = UUID.randomUUID().toString(),
                name = "Mail.ru Документы (Б/С)",
                mode = "classic",
                transportType = "mailru",
                documentUrl = "",
                encryptionSecret = "",
                codec = "batched"
            ),
            OpenFluxConfig(
                id = UUID.randomUUID().toString(),
                name = "Cups.online (Centrifugo)",
                mode = "classic",
                transportType = "cupsonline",
                documentUrl = "",
                encryptionSecret = "",
                codec = "batched"
            ),
            OpenFluxConfig(
                id = UUID.randomUUID().toString(),
                name = "MAX Messenger (OneMe)",
                mode = "classic",
                transportType = "oneme",
                maxToken = "",
                maxUid = "",
                codec = "batched"
            ),
            OpenFluxConfig(
                id = UUID.randomUUID().toString(),
                name = "Без сервера (PHP-хостинг)",
                mode = "stream",
                transportType = "cupsonline",
                documentUrl = "",
                codec = "batched"
            )
        )
    }

    fun saveConfigs(context: Context, configs: List<OpenFluxConfig>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        configs.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, jsonArray.toString()).apply()
    }

    fun addConfig(context: Context, config: OpenFluxConfig) {
        val list = getConfigs(context).toMutableList()
        list.add(config)
        saveConfigs(context, list)
        if (getSelectedId(context) == null) {
            setSelectedId(context, config.id)
        }
    }

    fun updateConfig(context: Context, config: OpenFluxConfig) {
        val list = getConfigs(context).toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        if (index != -1) {
            list[index] = config
            saveConfigs(context, list)
        }
    }

    fun deleteConfig(context: Context, id: String) {
        val list = getConfigs(context).toMutableList()
        list.removeAll { it.id == id }
        saveConfigs(context, list)
        if (getSelectedId(context) == id) {
            val newSelected = list.firstOrNull()?.id
            setSelectedId(context, newSelected)
        }
    }

    fun getSelectedId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_ID, null)
    }

    fun setSelectedId(context: Context, id: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_ID, id).apply()
    }

    fun getRoutingMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ROUTING_MODE, "vpn") ?: "vpn"
    }

    fun setRoutingMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ROUTING_MODE, mode).apply()
    }

    fun getSelectedConfig(context: Context): OpenFluxConfig? {
        val list = getConfigs(context)
        val selectedId = getSelectedId(context)
        return list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    }

    fun parseAndAddLink(context: Context, rawLink: String): OpenFluxConfig? {
        val link = rawLink.trim()
        if (!link.startsWith("openflux://")) {
            return null
        }

        try {
            val specsJson = NativeOpenFlux.shareSessionSpecs(link)
            val readJson = NativeOpenFlux.readShareLink(link)
            var profileName = "OpenFLUX профиль"

            if (readJson.isNotBlank()) {
                val obj = JSONObject(readJson)
                val configObj = obj.optJSONObject("config")
                if (configObj != null) {
                    val name = configObj.optString("name", "")
                    if (name.isNotBlank()) profileName = name
                    val secret = configObj.optString("secret", "")
                    val codec = configObj.optString("codec", "batched")

                    val newConfig = OpenFluxConfig(
                        name = profileName,
                        mode = if (specsJson.isNotBlank()) "session" else "classic",
                        encryptionSecret = secret,
                        codec = codec,
                        specsJson = specsJson,
                        rawUri = link
                    )
                    addConfig(context, newConfig)
                    setSelectedId(context, newConfig.id)
                    return newConfig
                }
            }

            // Fallback config from specs
            val newConfig = OpenFluxConfig(
                name = profileName,
                mode = "session",
                specsJson = specsJson,
                rawUri = link
            )
            addConfig(context, newConfig)
            setSelectedId(context, newConfig.id)
            return newConfig
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
