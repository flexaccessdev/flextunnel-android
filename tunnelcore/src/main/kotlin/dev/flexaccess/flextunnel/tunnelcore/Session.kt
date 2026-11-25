package dev.flexaccess.flextunnel.tunnelcore

import org.json.JSONArray
import org.json.JSONObject

/**
 * One flextunnel server this client reaches. [name] is one DNS label, unique
 * within the client: `<name>.localhost.flextunnel.internal:<port>` through the
 * session is that server's own loopback. [routedDomains] / [routedCidrs] are
 * further targets the SOCKS5 proxy (and the forwards) send through it — the
 * server must route them too; its own routed set is the allowlist.
 */
data class Destination(
    val id: String,
    val name: String,
    val serverNodeId: String,
    val routedDomains: List<String> = emptyList(),
    val routedCidrs: List<String> = emptyList(),
) {
    /** The host that reaches this server's loopback. */
    val loopbackHost: String get() = "$name.$LOOPBACK_SUFFIX"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("server_node_id", serverNodeId)
        .put("routed_domains", JSONArray(routedDomains))
        .put("routed_cidrs", JSONArray(routedCidrs))

    companion object {
        const val LOOPBACK_SUFFIX = "localhost.flextunnel.internal"

        fun fromJson(obj: JSONObject): Destination = Destination(
            id = obj.getString("id"),
            name = obj.getString("name"),
            serverNodeId = obj.getString("server_node_id"),
            routedDomains = obj.optJSONArray("routed_domains").strings(),
            routedCidrs = obj.optJSONArray("routed_cidrs").strings(),
        )
    }
}

/**
 * A local TCP port on the device's loopback (`127.0.0.1` and `::1`) whose
 * connections go to [remoteHost]:[remotePort] through the destination that
 * routes [remoteHost]. A forward never connects directly: a host no
 * destination routes is invalid.
 */
data class PortForward(
    val id: String,
    val label: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("local_port", localPort)
        .put("remote_host", remoteHost)
        .put("remote_port", remotePort)
        .put("enabled", enabled)

    companion object {
        fun fromJson(obj: JSONObject): PortForward = PortForward(
            id = obj.getString("id"),
            label = obj.optString("label", ""),
            localPort = obj.getInt("local_port"),
            remoteHost = obj.getString("remote_host"),
            remotePort = obj.getInt("remote_port"),
            enabled = obj.optBoolean("enabled", true),
        )
    }
}

/**
 * Session-wide settings. The SOCKS5 proxy listens on `127.0.0.1:[socksPort]`
 * — a fixed port, so a browser or extension configured once keeps working —
 * while [socksEnabled]; the port must be free when the session starts.
 * [relayUrls] empty means FlexTunnel's default relays.
 */
data class SessionSettings(
    val socksEnabled: Boolean = true,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val relayUrls: List<String> = emptyList(),
    val authKeyId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("socks_enabled", socksEnabled)
        .put("socks_port", socksPort)
        .put("relay_urls", JSONArray(relayUrls))
        .put("auth_key_id", authKeyId ?: JSONObject.NULL)

    companion object {
        const val DEFAULT_SOCKS_PORT = 1080

        fun fromJson(obj: JSONObject): SessionSettings = SessionSettings(
            socksEnabled = obj.optBoolean("socks_enabled", true),
            socksPort = obj.optInt("socks_port", DEFAULT_SOCKS_PORT),
            relayUrls = obj.optJSONArray("relay_urls").strings(),
            authKeyId = if (obj.isNull("auth_key_id")) null else obj.optString("auth_key_id"),
        )
    }
}

/** The JSON documents the native library takes (see flextunnel's `ios/flextunnel.h`). */
object FfiJson {
    /** `start`'s config. */
    fun config(
        authKey: String,
        settings: SessionSettings,
        relayAuthToken: String?,
        destinations: List<Destination>,
    ): String = JSONObject()
        .put("auth_key", authKey)
        .put("socks_port", if (settings.socksEnabled) settings.socksPort else JSONObject.NULL)
        .put("relay_urls", JSONArray(settings.relayUrls))
        .put("relay_auth_token", relayAuthToken?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        .put(
            "destinations",
            JSONArray(
                destinations.map {
                    JSONObject()
                        .put("name", it.name)
                        .put("server_node_id", it.serverNodeId)
                        .put("routed_domains", JSONArray(it.routedDomains))
                        .put("routed_cidrs", JSONArray(it.routedCidrs))
                },
            ),
        )
        .toString()

    /** `setForwards`' array: every forward, the disabled ones included. */
    fun forwards(forwards: List<PortForward>): String = JSONArray(
        forwards.map {
            JSONObject()
                .put("id", it.id)
                .put("local_port", it.localPort)
                .put("remote_host", it.remoteHost)
                .put("remote_port", it.remotePort)
                .put("enabled", it.enabled)
        },
    ).toString()

    /** `fetchServerRoutes`' request. */
    fun routesRequest(authKey: String, serverNodeId: String, relayUrls: List<String>, relayAuthToken: String?): String =
        JSONObject()
            .put("auth_key", authKey)
            .put("server_node_id", serverNodeId)
            .put("relay_urls", JSONArray(relayUrls))
            .put("relay_auth_token", relayAuthToken?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .toString()
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
