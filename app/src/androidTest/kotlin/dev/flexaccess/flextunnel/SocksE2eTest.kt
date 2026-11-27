package dev.flexaccess.flextunnel

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.flexaccess.flextunnel.E2e.CIDR
import dev.flexaccess.flextunnel.E2e.DIRECT_TEXT
import dev.flexaccess.flextunnel.E2e.DeviceSite
import dev.flexaccess.flextunnel.E2e.HOME_TEXT
import dev.flexaccess.flextunnel.E2e.OUTAGE_TIMEOUT
import dev.flexaccess.flextunnel.E2e.PAGE_TWO_TEXT
import dev.flexaccess.flextunnel.E2e.PLAIN
import dev.flexaccess.flextunnel.E2e.SITE
import dev.flexaccess.flextunnel.E2e.SITE_HOST
import dev.flexaccess.flextunnel.E2e.assertAnswered
import dev.flexaccess.flextunnel.E2e.bringBack
import dev.flexaccess.flextunnel.E2e.clientStatus
import dev.flexaccess.flextunnel.E2e.destinationState
import dev.flexaccess.flextunnel.E2e.get
import dev.flexaccess.flextunnel.E2e.getUntil
import dev.flexaccess.flextunnel.E2e.loopback
import dev.flexaccess.flextunnel.E2e.manager
import dev.flexaccess.flextunnel.E2e.serverLog
import dev.flexaccess.flextunnel.E2e.sitePort
import dev.flexaccess.flextunnel.E2e.socks
import dev.flexaccess.flextunnel.E2e.startAndWait
import dev.flexaccess.flextunnel.E2e.stopAndWait
import dev.flexaccess.flextunnel.E2e.takeDown
import dev.flexaccess.flextunnel.E2e.waitFor
import dev.flexaccess.flextunnel.E2e.waitForDestinationState
import dev.flexaccess.flextunnel.E2e.waitForServerLog
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * The SOCKS5 proxy end to end, as a browser or another app on the phone uses
 * it: which destination each target goes through, that a routed target never
 * falls back to a direct connection, the client's and the servers' status
 * pages, and destinations connecting on first use. Requests go by name, as
 * `socks5h://` sends them. See [E2e] for the servers.
 */
@RunWith(AndroidJUnit4::class)
class SocksE2eTest {
    private var activity: ActivityScenario<MainActivity>? = null
    private val stopped = mutableSetOf<String>()

    @Before
    fun setUp() {
        E2e.assumeServers()
        // In the foreground, as a user starting the session would be: Android
        // refuses to start a foreground service from the background.
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        // A test that failed with a server down leaves it up for the next.
        if (stopped.isNotEmpty() && manager.state.value is SessionState.Running) stopped.forEach { runCatching { bringBack(it) } }
        if (activity != null) stopAndWait()
        activity?.close()
    }

    /**
     * Starting a session connects nothing; a destination's server is connected
     * by the first request to it — here to the destination's loopback name,
     * which is the server's loopback whatever that server routes. Using a
     * second destination connects it alongside the first.
     */
    @Test
    fun destinationConnectsWhenFirstUsedAndItsLoopbackNameIsTheServersLoopback() {
        E2e.reset(SITE, PLAIN)
        startAndWait()
        assertEquals("idle", destinationState(SITE))
        assertEquals("idle", destinationState(PLAIN))

        // The fixture site listens only on the servers' machine's loopback:
        // the plain server's own, a server that routes nothing at all.
        getUntil(loopback(PLAIN), sitePort, "/", HOME_TEXT)
        assertEquals("connected", destinationState(PLAIN))
        assertEquals("a destination nothing used was connected", "idle", destinationState(SITE))
        val path = manager.connPath(PLAIN)
        assertNotNull("no connection path for $PLAIN", path)
        assertTrue("no selected path: $path", path!!.paths.any { it.selected })

        // The site's host alias resolves only on the site server.
        getUntil(SITE_HOST, sitePort, "/page2.html", PAGE_TWO_TEXT)
        assertEquals("connected", destinationState(SITE))
        assertEquals("connected", destinationState(PLAIN))

        // And the first is still usable, on the connection it already has.
        assertAnswered(HOME_TEXT, get(loopback(PLAIN), sitePort))
    }

    /**
     * `flextunnel.internal` is the client's own status page, answered by the
     * proxy without connecting anything; a destination server's page is
     * fetched through it, which connects that destination.
     */
    @Test
    fun statusPagesAreAnsweredByTheClientAndFetchedFromAServer() {
        E2e.reset(SITE, PLAIN)
        startAndWait()
        assertAnswered("flextunnel client status", get("flextunnel.internal", 80))
        val status = clientStatus()
        assertEquals(setOf(SITE, PLAIN), status.keys)
        assertEquals(E2e.siteNodeId, status.getValue(SITE).getString("server_node_id"))
        assertTrue(
            "reading the client's status connected a destination",
            status.values.all { it.getString("state") == "idle" },
        )

        val plain = JSONObject(getUntil("flextunnel.internal", 80, "/servers/$PLAIN/status.json", "server_node_id"))
        assertEquals("the page is not the plain server's", E2e.plainNodeId, plain.getString("server_node_id"))
        assertEquals("connected", destinationState(PLAIN))

        val site = JSONObject(getUntil("flextunnel.internal", 80, "/servers/$SITE/status.json", "server_node_id"))
        assertEquals(E2e.siteNodeId, site.getString("server_node_id"))
        assertTrue(site.toString(), site.getJSONArray("routed_domains").toString().contains(SITE_HOST))
        val aliases = site.getJSONArray("host_aliases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertTrue(site.toString(), aliases.any { it.getString("name") == SITE_HOST && it.getString("target") == "127.0.0.1" })
    }

    /**
     * `localhost` is routed through the site destination, and the device
     * answers it directly too (on the site's port, [DeviceSite]). Through the
     * session it is the servers' machine's; while that server is down it
     * fails rather than falling back to the device, and a host on no route
     * keeps going direct; once the server is back, trying again reaches it.
     */
    @Test
    fun routedHostNeverFallsBackDirectWhileItsServerIsDown() {
        E2e.reset(SITE, PLAIN)
        DeviceSite(sitePort).use { device ->
            startAndWait()
            getUntil("localhost", sitePort, "/", HOME_TEXT)
            assertEquals("the routed localhost was answered on the device", 0, device.accepted)

            // On no route (an IP literal, and no destination routes a network): direct.
            assertAnswered(DIRECT_TEXT, get("127.0.0.1", sitePort))
            assertEquals(1, device.accepted)

            stopped += SITE
            takeDown(SITE)
            assertAnswered(DIRECT_TEXT, get("127.0.0.1", sitePort, "/page2.html"))
            val direct = device.accepted

            val during = runCatching { get("localhost", sitePort, "/?during-outage") }
            assertTrue("the routed host answered while its server was down: $during", during.getOrNull()?.let { HOME_TEXT in it.body } != true)
            assertFalse("the routed host was answered on the device", during.getOrNull()?.body?.contains(DIRECT_TEXT) == true)
            assertEquals("the routed host fell back to a direct connection", direct, device.accepted)
            assertTrue(destinationState(SITE) != "connected")

            // Nothing reconnects a server on its own: the destination is tried
            // again when it is next used.
            bringBack(SITE)
            stopped -= SITE
            getUntil("localhost", sitePort, "/", HOME_TEXT, OUTAGE_TIMEOUT)
            assertEquals("connected", destinationState(SITE))
            assertEquals("the routed host fell back to a direct connection", direct, device.accepted)
        }
    }

    /**
     * A destination routing a network tunnels the addresses in it, and still
     * leaves hosts on no route direct, its server down or not. An address in
     * the range is held for its destination while the server is down: the
     * server's log shows it arriving once the server is back. (The range is
     * TEST-NET-3, which nothing answers, so the log is the evidence of the
     * path.)
     */
    @Test
    fun networkRouteTunnelsItsIpRangeAndKeepsOtherHostsDirect() {
        E2e.reset(PLAIN, CIDR)
        DeviceSite(sitePort).use { device ->
            startAndWait()
            // No destination routes `localhost` here: the device answers it.
            assertAnswered(DIRECT_TEXT, get("localhost", sitePort))
            assertEquals(1, device.accepted)

            val tunneled = "203.0.113.9"
            assertFalse(serverLog(CIDR).contains(tunneled))

            stopped += CIDR
            takeDown(CIDR)
            assertAnswered(DIRECT_TEXT, get("localhost", sitePort, "/page2.html"))
            assertEquals(2, device.accepted)

            // Held for the destination: answered (or failed) only once it
            // connects, which it can only once the server is back.
            val held = thread(isDaemon = true) { runCatching { socks(tunneled, 80).close() } }
            Thread.sleep(2_000)
            bringBack(CIDR)
            stopped -= CIDR
            waitForServerLog(CIDR, tunneled, OUTAGE_TIMEOUT)
            held.join(OUTAGE_TIMEOUT)
            waitForDestinationState(CIDR, "connected")
        }
    }

    /** A name no destination routes and nothing resolves fails, rather than going anywhere else. */
    @Test
    fun aHostOnNoRouteThatDoesNotResolveIsRefused() {
        E2e.reset(SITE, PLAIN)
        startAndWait()
        val refused = runCatching { socks("nowhere.invalid", 80, timeoutMs = 30_000).close() }.exceptionOrNull()
        assertTrue("expected a refused CONNECT, got $refused", refused is E2e.SocksRefused)
        assertTrue(clientStatus().values.all { it.getString("state") == "idle" })
    }

    /** Restarting the session with the stores as they are serves again at once. */
    @Test
    fun startingAgainRightAfterStopConnects() {
        E2e.reset(SITE, PLAIN)
        startAndWait()
        getUntil(SITE_HOST, sitePort, "/", HOME_TEXT)
        stopAndWait()
        // Straight back in: the old session may still be tearing down, and the
        // core runs one instance at a time, so the new start must wait for that
        // rather than be refused.
        startAndWait()
        getUntil(SITE_HOST, sitePort, "/", HOME_TEXT)
        waitFor(5_000, "the session to stay up") { manager.state.value.takeIf { it is SessionState.Running } }
    }
}
