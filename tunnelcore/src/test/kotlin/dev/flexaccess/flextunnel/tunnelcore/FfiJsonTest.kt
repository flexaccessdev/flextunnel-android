package dev.flexaccess.flextunnel.tunnelcore

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfiJsonTest {
    private val dest = Destination("1", "m1", "a".repeat(64), listOf("*.corp"), listOf("10.0.0.0/8"))

    @Test fun configCarriesTheFixedSocksPortOrNull() {
        val on = JSONObject(FfiJson.config("ed25519-sec:x", SessionSettings(socksPort = 1085), null, listOf(dest)))
        assertEquals(1085, on.getInt("socks_port"))
        assertTrue(on.isNull("relay_auth_token"))
        assertEquals("m1", on.getJSONArray("destinations").getJSONObject(0).getString("name"))
        val off = JSONObject(FfiJson.config("ed25519-sec:x", SessionSettings(socksEnabled = false), "t", listOf(dest)))
        assertTrue(off.isNull("socks_port"))
        assertEquals("t", off.getString("relay_auth_token"))
    }

    @Test fun forwardsKeepDisabledEntries() {
        val json = JSONArray(FfiJson.forwards(listOf(PortForward("f", "ssh", 2222, "h", 22, enabled = false))))
        assertEquals(false, json.getJSONObject(0).getBoolean("enabled"))
    }

    @Test fun modelRoundTrips() {
        assertEquals(dest, Destination.fromJson(dest.toJson()))
        val f = PortForward("f", "ssh", 2222, "h", 22, enabled = false)
        assertEquals(f, PortForward.fromJson(f.toJson()))
        val s = SessionSettings(false, 1090, listOf("https://r/"), "k")
        assertEquals(s, SessionSettings.fromJson(s.toJson()))
        assertEquals(SessionSettings(authKeyId = null), SessionSettings.fromJson(SessionSettings().toJson()))
    }

    @Test fun decodesSnapshots() {
        val status = Snapshots.status(
            """{"idle_timeout_secs":300,"destinations":[{"name":"m1","server_node_id":"x","state":"backoff",
            "connected_secs":null,"open_streams":0,"waiting":2,"client_node_id":null,"failed_attempts":3,
            "next_attempt_secs":7,"last_error":"timed out","routed_domains":[],"routed_cidrs":[],"relay_urls":[]}]}""",
        )
        assertEquals(DestinationStatus("m1", "backoff", null, 0, 2, 3, 7, "timed out"), status.single())
        val fwd = Snapshots.forwardStatuses("""{"forwards":[{"id":"f","state":"failed","error":"in use","active":0,"last_conn_error":null}]}""")
        assertEquals(ForwardStatus("f", "failed", "in use", 0, null), fwd.single())
        val path = Snapshots.connPath(
            """{"paths":[{"kind":"relay","display":"Relay https://r/","selected":true}],
            "custom_relays":[{"url":"https://r/","working":null,"error":null}],"udp_tx_datagrams":5,"udp_rx_datagrams":4}""",
        )
        assertEquals(ConnPath("relay", "Relay https://r/", true), path.paths.single())
        assertEquals(RelayHealth("https://r/", null, null), path.customRelays.single())
        assertEquals(listOf("*.corp") to listOf("10.0.0.0/8"), Snapshots.serverRoutes("""{"routed_domains":["*.corp"],"routed_cidrs":["10.0.0.0/8"]}"""))
    }
}
