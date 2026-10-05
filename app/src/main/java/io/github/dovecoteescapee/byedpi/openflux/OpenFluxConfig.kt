package io.github.dovecoteescapee.byedpi.openflux

import org.json.JSONObject
import java.util.UUID

data class OpenFluxConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mode: String = "classic", // "classic", "session", "stream"
    val transportType: String = "yandex", // "yandex", "vyandex", "boards", "mailru", "cupsonline", "oneme", "direct"
    val documentUrl: String = "",
    val encryptionSecret: String = "",
    val codec: String = "batched", // "batched" or "legacy"
    val maxToken: String = "",
    val maxUid: String = "",
    val listenPort: Int = 10885,
    val specsJson: String = "",
    val routingMode: String = "vpn", // "vpn" or "proxy_only"
    val bypassDomains: String = "",
    val rawUri: String = ""
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("mode", mode)
        obj.put("transportType", transportType)
        obj.put("documentUrl", documentUrl)
        obj.put("encryptionSecret", encryptionSecret)
        obj.put("codec", codec)
        obj.put("maxToken", maxToken)
        obj.put("maxUid", maxUid)
        obj.put("listenPort", listenPort)
        obj.put("specsJson", specsJson)
        obj.put("routingMode", routingMode)
        obj.put("bypassDomains", bypassDomains)
        obj.put("rawUri", rawUri)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): OpenFluxConfig {
            return OpenFluxConfig(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "OpenFLUX"),
                mode = obj.optString("mode", "classic"),
                transportType = obj.optString("transportType", "yandex"),
                documentUrl = obj.optString("documentUrl", ""),
                encryptionSecret = obj.optString("encryptionSecret", ""),
                codec = obj.optString("codec", "batched"),
                maxToken = obj.optString("maxToken", ""),
                maxUid = obj.optString("maxUid", ""),
                listenPort = obj.optInt("listenPort", 10885),
                specsJson = obj.optString("specsJson", ""),
                routingMode = obj.optString("routingMode", "vpn"),
                bypassDomains = obj.optString("bypassDomains", ""),
                rawUri = obj.optString("rawUri", "")
            )
        }
    }
}
