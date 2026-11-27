package dev.flexaccess.flextunnel

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.flexaccess.flextunnel.E2e.CIDR
import dev.flexaccess.flextunnel.E2e.CIDR_RANGE
import dev.flexaccess.flextunnel.E2e.CONNECT_TIMEOUT
import dev.flexaccess.flextunnel.E2e.HOME_TEXT
import dev.flexaccess.flextunnel.E2e.PLAIN
import dev.flexaccess.flextunnel.E2e.SITE
import dev.flexaccess.flextunnel.E2e.assertAnswered
import dev.flexaccess.flextunnel.E2e.clientStatus
import dev.flexaccess.flextunnel.E2e.getLocal
import dev.flexaccess.flextunnel.E2e.loopback
import dev.flexaccess.flextunnel.E2e.manager
import dev.flexaccess.flextunnel.E2e.retry
import dev.flexaccess.flextunnel.E2e.sitePort
import dev.flexaccess.flextunnel.E2e.stopAndWait
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's screens against the real servers: the destination editor's
 * checks and its fetch from a server, the forward editor's routing check, the
 * relay settings, and starting and stopping from the home screen while the
 * destinations' rows follow the session. See [E2e] for the servers.
 */
@RunWith(AndroidJUnit4::class)
class ScreensE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        E2e.assumeServers()
    }

    @After
    fun tearDown() {
        stopAndWait()
    }

    /** The home screen lists the destinations, and the editor checks a new one against the others before saving it. */
    @Test
    fun addingADestinationValidatesItAgainstTheOthers() {
        E2e.reset(SITE, PLAIN)
        compose.onNodeWithTag("destination-$SITE").assertExists()
        compose.onNodeWithTag("destination-$PLAIN").assertExists()

        compose.onNodeWithTag("add-Destinations").performClick()
        compose.onNodeWithTag("nodeId").performTextInput(E2e.cidrNodeId)
        // A name another destination already has.
        compose.onNodeWithTag("name").performTextInput(SITE)
        compose.onNodeWithTag("save").performClick()
        assertError("Another destination is already named $SITE.")

        // Typing on makes it a name of its own.
        compose.onNodeWithTag("name").performTextInput("-lab")
        compose.onNodeWithTag("cidrs").performTextInput(CIDR_RANGE)
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5_000) { exists("destination-$SITE-lab") }
        val added = manager.profiles.destinations.value.single { it.name == "$SITE-lab" }
        assertEquals(E2e.cidrNodeId, added.serverNodeId)
        assertEquals(listOf(CIDR_RANGE), added.routedCidrs)
    }

    /**
     * The editor fills a destination's routes from the server it names: the
     * server is asked before the destination exists and with no session
     * running, and what it routes lands in the fields to be saved.
     */
    @Test
    fun fetchFromServerFillsInWhatTheServerRoutes() {
        E2e.reset(SITE, PLAIN)
        compose.onNodeWithTag("add-Destinations").performClick()
        compose.onNodeWithTag("fetchRoutes").assertIsNotEnabled()
        compose.onNodeWithTag("nodeId").performTextInput(E2e.cidrNodeId)
        compose.onNodeWithTag("fetchRoutes").assertIsEnabled().performClick()

        compose.waitUntil(CONNECT_TIMEOUT) {
            check(!exists("errorMessage")) { "the fetch failed: ${text("errorMessage")}" }
            text("cidrs") == CIDR_RANGE
        }
        assertEquals("", text("domains"))
        compose.onNodeWithTag("name").performTextInput(CIDR)
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5_000) { exists("destination-$CIDR") }
        assertEquals(listOf(CIDR_RANGE), manager.profiles.destinations.value.single { it.name == CIDR }.routedCidrs)
        assertTrue(manager.state.value !is SessionState.Running)
    }

    /**
     * A forward to a host no destination routes is refused, saying so; one to
     * a destination's loopback name saves, and serves once the session is
     * started from the home screen, whose rows then show the forward
     * listening and the destination connected. Stop ends it.
     */
    @Test
    fun forwardsAreCheckedForARouteAndServeOnceStarted() {
        E2e.reset(SITE, PLAIN)
        assertEquals("Off", text("sessionState"))
        compose.onNodeWithTag("add-Port forwards").performClick()
        compose.onNodeWithTag("localPort").performTextInput("$FORWARD_PORT")
        compose.onNodeWithTag("remoteHost").performTextInput("unrouted.example.com")
        compose.onNodeWithTag("remotePort").performTextInput("$sitePort")
        compose.onNodeWithTag("enabled").assertIsOn()
        compose.onNodeWithTag("save").performClick()
        assertError("No destination routes unrouted.example.com.", prefixOnly = true)

        compose.onNodeWithTag("remoteHost").performTextClearance()
        compose.onNodeWithTag("remoteHost").performTextInput(loopback(PLAIN))
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5_000) { exists("start") }
        val forward = manager.profiles.forwards.value.single()

        compose.onNodeWithTag("start").performClick()
        compose.waitUntil(20_000) { text("sessionState") == "On" }
        compose.waitUntil(10_000) { exists("forwardStatus-${forward.id}") && text("forwardStatus-${forward.id}").startsWith("Listening") }
        assertEquals("Idle — connects on first use", text("destinationState-$PLAIN"))

        retry(CONNECT_TIMEOUT) { assertAnswered(HOME_TEXT, getLocal(FORWARD_PORT, loopback(PLAIN))) }
        compose.waitUntil(10_000) { text("destinationState-$PLAIN").startsWith("Connected") }
        assertTrue(text("destinationState-$SITE").startsWith("Idle"))

        compose.onNodeWithTag("stop").performClick()
        compose.waitUntil(20_000) { text("sessionState") == "Off" }
    }

    /**
     * The QUIC address discovery switch is for custom relays only: off-limits
     * without one, on by default with one. Turned off and saved, it reaches
     * the session started next.
     */
    @Test
    fun quicAddrDiscoverySwitchFollowsTheCustomRelaysAndReachesTheSession() {
        E2e.reset(SITE, PLAIN)
        compose.onNodeWithTag("settings").performClick()
        val discovery = compose.onNodeWithTag("relayQuicAddrDiscovery")
        discovery.performScrollTo().assertIsNotEnabled().assertIsOn()

        compose.onNodeWithTag("relayUrls").performScrollTo().performTextInput(RELAY)
        discovery.performScrollTo().assertIsEnabled().assertIsOn().performClick()
        discovery.assertIsOff()
        compose.onNodeWithText("Turn it off for relays behind a Cloudflare Tunnel", substring = true).assertExists()
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5_000) { exists("start") }
        val saved = manager.profiles.settings.value
        assertEquals(listOf(RELAY), saved.relayUrls)
        assertFalse(saved.relayQuicAddrDiscovery)

        compose.onNodeWithTag("start").performClick()
        compose.waitUntil(20_000) { text("sessionState") == "On" }
        val relays = clientStatus().getValue(PLAIN).getJSONArray("relay_urls")
        assertEquals(RELAY, relays.getString(0).trimEnd('/'))

        // Back to the defaults: the switch is off-limits again, and keeps its value for next time.
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("relayUrls").performScrollTo().performTextClearance()
        discovery.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5_000) { exists("start") || exists("stop") }
        assertEquals(emptyList<String>(), manager.profiles.settings.value.relayUrls)
        // The running session restarted with the default relays.
        compose.waitUntil(20_000) {
            manager.state.value is SessionState.Running &&
                runCatching { clientStatus().getValue(PLAIN).getJSONArray("relay_urls").length() == 0 }.getOrDefault(false)
        }
    }

    // --- Helpers ------------------------------------------------------------

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTagCount(tag) > 0

    /** A text field's input, or a text's words. */
    private fun text(tag: String): String {
        val config = node(tag).fetchSemanticsNode().config
        if (SemanticsProperties.EditableText in config) return config[SemanticsProperties.EditableText].text
        return config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }
    }

    /** The error dialog says [message] (or starts with it); dismissed. */
    private fun assertError(message: String, prefixOnly: Boolean = false) {
        compose.waitUntil(5_000) { exists("errorMessage") }
        val shown = text("errorMessage")
        assertTrue("error dialog says: $shown", if (prefixOnly) shown.startsWith(message) else shown == message)
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { !exists("errorMessage") }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private companion object {
        const val FORWARD_PORT = 18083
        const val RELAY = "https://relay.e2e.flextunnel.test"
    }
}
