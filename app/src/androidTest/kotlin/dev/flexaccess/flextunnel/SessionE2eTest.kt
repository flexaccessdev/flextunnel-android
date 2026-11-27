package dev.flexaccess.flextunnel

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.flexaccess.flextunnel.E2e.CIDR
import dev.flexaccess.flextunnel.E2e.CIDR_RANGE
import dev.flexaccess.flextunnel.E2e.HOME_TEXT
import dev.flexaccess.flextunnel.E2e.PAGE_TWO_TEXT
import dev.flexaccess.flextunnel.E2e.PLAIN
import dev.flexaccess.flextunnel.E2e.SITE
import dev.flexaccess.flextunnel.E2e.SITE_HOST
import dev.flexaccess.flextunnel.E2e.SOCKS_PORT
import dev.flexaccess.flextunnel.E2e.assertAnswered
import dev.flexaccess.flextunnel.E2e.clientStatus
import dev.flexaccess.flextunnel.E2e.getLocal
import dev.flexaccess.flextunnel.E2e.getUntil
import dev.flexaccess.flextunnel.E2e.loopback
import dev.flexaccess.flextunnel.E2e.manager
import dev.flexaccess.flextunnel.E2e.retry
import dev.flexaccess.flextunnel.E2e.runOnMain
import dev.flexaccess.flextunnel.E2e.sitePort
import dev.flexaccess.flextunnel.E2e.startAndWait
import dev.flexaccess.flextunnel.E2e.stopAndWait
import dev.flexaccess.flextunnel.E2e.waitFor
import dev.flexaccess.flextunnel.tunnelcore.PortForward
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The session end to end, beyond what the SOCKS5 proxy does with a request:
 * the port forwards, changes applied to a running session, starts that must
 * fail, asking a server what it routes, and custom relays. See [E2e] for the
 * servers.
 */
@RunWith(AndroidJUnit4::class)
class SessionE2eTest {
    private var activity: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        E2e.assumeServers()
        // In the foreground: Android refuses to start a foreground service from
        // the background, and the app polls forward status only while visible.
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (activity != null) stopAndWait()
        activity?.close()
    }

    /**
     * A forward goes through the destination that routes its host — a
     * destination's loopback name, or a name on its routes — and a change to
     * the forwards applies to the running session in place: one switched off
     * stops listening, one added starts, and the session is not restarted.
     */
    @Test
    fun forwardsGoThroughTheDestinationThatRoutesTheirHostAndChangeInPlace() {
        E2e.reset(SITE, PLAIN)
        val profiles = manager.profiles
        assertNull(profiles.saveForward(PortForward("loop", "plain loopback", FORWARD_A, loopback(PLAIN), sitePort)))
        assertNull(profiles.saveForward(PortForward("alias", "site alias", FORWARD_B, SITE_HOST, sitePort)))
        startAndWait()

        // The first use connects the destination: allow for discovery.
        retry(E2e.CONNECT_TIMEOUT) { assertAnswered(HOME_TEXT, getLocal(FORWARD_A, loopback(PLAIN))) }
        retry(E2e.CONNECT_TIMEOUT) { assertAnswered(PAGE_TWO_TEXT, getLocal(FORWARD_B, SITE_HOST, "/page2.html")) }
        waitFor(10_000, "both forwards listening") {
            manager.forwardStatus.value.takeIf { s -> listOf("loop", "alias").all { s[it]?.state == "listening" } }
        }
        val connected = clientStatus()
        assertEquals("connected", connected.getValue(PLAIN).getString("state"))
        assertEquals("connected", connected.getValue(SITE).getString("state"))

        // Off: the port closes; the other forward and the connections stay.
        assertNull(profiles.setForwardEnabled("alias", false))
        runOnMain { manager.applyForwards() }
        retry(10_000) { assertRefused(FORWARD_B) }
        // Checked before PLAIN is used again, which would connect a restarted session's.
        assertNotRestarted(PLAIN, SITE)
        assertAnswered(HOME_TEXT, getLocal(FORWARD_A, loopback(PLAIN)))

        // Added while running: it serves without a restart.
        assertNull(profiles.saveForward(PortForward("late", "", FORWARD_C, loopback(SITE), sitePort)))
        runOnMain { manager.applyForwards() }
        retry(10_000) { assertAnswered(HOME_TEXT, getLocal(FORWARD_C, loopback(SITE))) }
        assertNotRestarted(PLAIN)
    }

    /**
     * The session still runs on its port, and [unused] destinations — not
     * used since they connected — are still connected: a restarted session
     * would have them idle until next used.
     */
    private fun assertNotRestarted(vararg unused: String) {
        assertEquals(SessionState.Running(SOCKS_PORT), manager.state.value)
        val status = clientStatus()
        unused.forEach { assertEquals("the session restarted: $it is no longer connected", "connected", status.getValue(it).getString("state")) }
    }

    /**
     * A forward whose port another app holds, on both loopbacks, fails on its
     * own, saying why; the session and the other forwards serve. (Held on one
     * loopback only, the forward listens on the other.)
     */
    @Test
    fun aForwardOnATakenPortFailsAloneAndTheRestServe() {
        E2e.reset(SITE, PLAIN)
        val profiles = manager.profiles
        val held = listOf(hold("127.0.0.1", FORWARD_B), hold("::1", FORWARD_B))
        try {
            assertNull(profiles.saveForward(PortForward("taken", "", FORWARD_B, loopback(PLAIN), sitePort)))
            assertNull(profiles.saveForward(PortForward("free", "", FORWARD_A, loopback(PLAIN), sitePort)))
            startAndWait()
            val failed = waitFor(10_000, "the taken forward to fail") {
                manager.forwardStatus.value["taken"]?.takeIf { it.state == "failed" }
            }
            assertEquals("port $FORWARD_B is in use", failed.error)
            retry(E2e.CONNECT_TIMEOUT) { assertAnswered(HOME_TEXT, getLocal(FORWARD_A, loopback(PLAIN))) }
            getUntil(SITE_HOST, sitePort, "/", HOME_TEXT)
            assertTrue(manager.state.value is SessionState.Running)
        } finally {
            held.forEach { it.close() }
        }
    }

    /** A settings change restarts a running session with it: the proxy moves to its new port. */
    @Test
    fun aSettingsChangeRestartsTheRunningSession() {
        E2e.reset(SITE, PLAIN)
        startAndWait()
        getUntil(SITE_HOST, sitePort, "/", HOME_TEXT)

        val moved = manager.profiles.settings.value.copy(socksPort = SOCKS_PORT + 1)
        assertNull(manager.profiles.saveSettings(moved, null))
        runOnMain { manager.restartIfRunning() }
        waitFor(20_000, "the session on its new port") {
            manager.state.value.takeIf { it == SessionState.Running(SOCKS_PORT + 1) }
        }
        getUntil(SITE_HOST, sitePort, "/", HOME_TEXT, socksPort = SOCKS_PORT + 1)
        retry(10_000) { assertRefused(SOCKS_PORT) }
    }

    @Test
    fun aTakenSocksPortFailsTheStart() {
        E2e.reset(SITE, PLAIN)
        ServerSocket().use { blocker ->
            blocker.reuseAddress = true
            blocker.bind(InetSocketAddress("127.0.0.1", SOCKS_PORT))
            runOnMain { manager.start() }
            val state = waitFor(20_000, "the start to end") {
                manager.state.value.takeIf { it is SessionState.Failed || it is SessionState.Running }
            }
            assertTrue("expected a failed start, got $state", state is SessionState.Failed)
        }
        // Free again, the next start serves.
        startAndWait()
    }

    /** The server is asked what it routes, with no session running and before any destination names it. */
    @Test
    fun fetchServerRoutesAsksTheServerWithNoSession() {
        E2e.reset()
        assertTrue(manager.state.value !is SessionState.Running)
        val (siteDomains, siteCidrs) = retry(E2e.CONNECT_TIMEOUT) { manager.fetchServerRoutes(E2e.siteNodeId).getOrThrow() }
        assertTrue(siteDomains.toString(), siteDomains.containsAll(listOf(SITE_HOST, "*.$SITE_HOST", "localhost")))
        assertEquals(emptyList<String>(), siteCidrs)
        val (cidrDomains, cidrCidrs) = retry(E2e.CONNECT_TIMEOUT) { manager.fetchServerRoutes(E2e.cidrNodeId).getOrThrow() }
        assertEquals(emptyList<String>(), cidrDomains)
        assertEquals(listOf(CIDR_RANGE), cidrCidrs)
        val (plainDomains, plainCidrs) = retry(E2e.CONNECT_TIMEOUT) { manager.fetchServerRoutes(E2e.plainNodeId).getOrThrow() }
        assertEquals(emptyList<String>(), plainDomains + plainCidrs)
        assertTrue(manager.state.value !is SessionState.Running)
    }

    /**
     * Custom relays reach the session, with their QUIC address discovery off
     * or on: the config the service hands the core carries the setting, and
     * the core starts with it. (The core takes an omitted setting as on and
     * doesn't report it back, so the config is where it can be checked.)
     * Nothing is used: the session starts without contacting a relay or a
     * server.
     */
    @Test
    fun customRelaysAndTheirQuicAddrDiscoveryReachTheSession() {
        for (discovery in listOf(false, true)) {
            E2e.reset(SITE, PLAIN, CIDR) {
                it.copy(relayUrls = listOf(RELAY), relayQuicAddrDiscovery = discovery)
            }
            val config = JSONObject(manager.buildConfig().getOrThrow())
            assertEquals(discovery, config.getBoolean("relay_quic_addr_discovery"))
            startAndWait()
            val status = clientStatus()
            assertEquals(setOf(SITE, PLAIN, CIDR), status.keys)
            status.values.forEach { d ->
                val relays = d.getJSONArray("relay_urls").let { a -> (0 until a.length()).map { a.getString(it).trimEnd('/') } }
                assertEquals("relays of ${d.getString("name")}, discovery $discovery", listOf(RELAY), relays)
            }
            stopAndWait()
        }
    }

    private fun hold(host: String, port: Int) = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(host, port))
    }

    private fun assertRefused(port: Int) {
        val refused = runCatching { Socket("127.0.0.1", port).close() }.exceptionOrNull()
        assertTrue("127.0.0.1:$port still accepts", refused is ConnectException)
    }

    private companion object {
        const val FORWARD_A = 18080
        const val FORWARD_B = 18081
        const val FORWARD_C = 18082
        const val RELAY = "https://relay.e2e.flextunnel.test"
    }
}
