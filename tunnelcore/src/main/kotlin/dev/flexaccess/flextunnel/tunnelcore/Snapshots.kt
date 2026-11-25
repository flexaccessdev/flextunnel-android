package dev.flexaccess.flextunnel.tunnelcore

import org.json.JSONObject

/** One destination in `status`' JSON. */
data class DestinationStatus(
    val name: String,
    /** idle, connecting, connected, backoff or failed. */
    val state: String,
    val connectedSecs: Long?,
    val openStreams: Int,
    val waiting: Int,
    val failedAttempts: Int,
    val nextAttemptSecs: Long?,
    val lastError: String?,
)

/** One forward in `forwardStatuses`' JSON. */
data class ForwardStatus(
    val id: String,
    /** starting, listening or failed. */
    val state: String,
    val error: String?,
    val active: Int,
    val lastConnError: String?,
)

/** One path in `connPath`'s JSON. */
data class ConnPath(val kind: String, val display: String, val selected: Boolean)

/** One custom relay's `/healthz` result in `connPath`'s JSON; [working] null when unchecked. */
data class RelayHealth(val url: String, val working: Boolean?, val error: String?)

data class ConnPathSnapshot(
    val paths: List<ConnPath>,
    val customRelays: List<RelayHealth>,
    val udpTxDatagrams: Long,
    val udpRxDatagrams: Long,
)

/** Decoders for the native library's snapshot JSON (shapes in flextunnel's `ios/flextunnel.h`). */
object Snapshots {
    fun status(json: String): List<DestinationStatus> {
        val array = JSONObject(json).optJSONArray("destinations") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val d = array.getJSONObject(i)
            DestinationStatus(
                name = d.getString("name"),
                state = d.getString("state"),
                connectedSecs = d.optLongOrNull("connected_secs"),
                openStreams = d.optInt("open_streams"),
                waiting = d.optInt("waiting"),
                failedAttempts = d.optInt("failed_attempts"),
                nextAttemptSecs = d.optLongOrNull("next_attempt_secs"),
                lastError = d.optStringOrNull("last_error"),
            )
        }
    }

    fun forwardStatuses(json: String): List<ForwardStatus> {
        val array = JSONObject(json).optJSONArray("forwards") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val f = array.getJSONObject(i)
            ForwardStatus(
                id = f.getString("id"),
                state = f.getString("state"),
                error = f.optStringOrNull("error"),
                active = f.optInt("active"),
                lastConnError = f.optStringOrNull("last_conn_error"),
            )
        }
    }

    fun connPath(json: String): ConnPathSnapshot {
        val obj = JSONObject(json)
        val paths = obj.optJSONArray("paths")
        val relays = obj.optJSONArray("custom_relays")
        return ConnPathSnapshot(
            paths = if (paths == null) emptyList() else (0 until paths.length()).map { i ->
                val p = paths.getJSONObject(i)
                ConnPath(p.optString("kind"), p.optString("display"), p.optBoolean("selected"))
            },
            customRelays = if (relays == null) emptyList() else (0 until relays.length()).map { i ->
                val r = relays.getJSONObject(i)
                RelayHealth(
                    url = r.optString("url"),
                    working = if (r.isNull("working")) null else r.optBoolean("working"),
                    error = r.optStringOrNull("error"),
                )
            },
            udpTxDatagrams = obj.optLong("udp_tx_datagrams"),
            udpRxDatagrams = obj.optLong("udp_rx_datagrams"),
        )
    }

    /** `fetchServerRoutes`' answer: (routed domains, routed CIDRs). */
    fun serverRoutes(json: String): Pair<List<String>, List<String>> {
        val obj = JSONObject(json)
        return obj.optJSONArray("routed_domains").strings() to obj.optJSONArray("routed_cidrs").strings()
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
