package dev.flexaccess.flextunnel

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.flexaccess.flextunnel.tunnelcore.Destination
import dev.flexaccess.flextunnel.tunnelcore.PortForward
import dev.flexaccess.flextunnel.tunnelcore.SessionSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The session end to end, on a device, against a real server: the SOCKS5
 * proxy on its fixed port and a port forward, each fetching a page from an
 * HTTP server on that server's loopback, over whatever path iroh finds (the
 * development emulator is relay-only). Driven by scripts/e2e.sh, which sets
 * up the throwaway server and passes its details as instrumentation
 * arguments: `serverNodeId`, `authKey` (an authorized `ed25519-sec:` key),
 * `targetPort` (the HTTP server's port on the server's loopback). Without them
 * every test is skipped, so a plain `connectedAndroidTest` passes vacuously.
 */
@RunWith(AndroidJUnit4::class)
class E2eTest {
    private val args = InstrumentationRegistry.getArguments()
    private val serverNodeId: String? = args.getString("serverNodeId")
    private val authKey: String? = args.getString("authKey")
    private val targetPort: Int = args.getString("targetPort")?.toIntOrNull() ?: 0
    private lateinit var manager: SessionManager
    private var activity: ActivityScenario<MainActivity>? = null

    private val targetHost = "e2e.${Destination.LOOPBACK_SUFFIX}"

    @Before
    fun setUp() {
        assumeTrue("scripts/e2e.sh passes the server", serverNodeId != null && authKey != null && targetPort > 0)
        // In the foreground, as a user starting the session would be: Android
        // refuses to start a foreground service from the background.
        activity = ActivityScenario.launch(MainActivity::class.java)
        manager = SessionManager.get(InstrumentationRegistry.getInstrumentation().targetContext)
        stopAndWait()
        val keys = manager.authKeys
        val public = AuthKey.publicKey(authKey!!)
        val key = keys.keys.value.firstOrNull { it.publicKey == public }
            ?: keys.add("e2e-${System.currentTimeMillis()}", authKey).getOrThrow()
        val profiles = manager.profiles
        profiles.forwards.value.forEach { profiles.deleteForward(it.id) }
        profiles.destinations.value.forEach { profiles.deleteDestination(it.id) }
        assertNull(profiles.saveSettings(SessionSettings(socksEnabled = true, socksPort = SOCKS_PORT, authKeyId = key.id), null))
        assertNull(profiles.saveDestination(Destination("e2e", "e2e", serverNodeId!!)))
        assertNull(profiles.saveForward(PortForward("e2e", "e2e", FORWARD_PORT, targetHost, targetPort)))
    }

    @After
    fun tearDown() {
        if (::manager.isInitialized) stopAndWait()
        activity?.close()
    }

    @Test
    fun socksAndForwardReachTheServersLoopback() {
        startAndWait()
        // The first request connects the destination (through a relay on the
        // emulator), so it gets the long timeout.
        val viaForward = retry(90_000) { httpGet(Socket("127.0.0.1", FORWARD_PORT)) }
        assertTrue("forward answer: $viaForward", viaForward.startsWith("HTTP/1.0 200"))
        val viaSocks = retry(30_000) { httpGet(socks5Connect(targetHost, targetPort)) }
        assertTrue("SOCKS5 answer: $viaSocks", viaSocks.startsWith("HTTP/1.0 200"))
        val running = manager.state.value
        assertEquals(SessionState.Running(SOCKS_PORT), running)
    }

    @Test
    fun aTakenSocksPortFailsTheStart() {
        ServerSocket().use { blocker ->
            blocker.reuseAddress = true
            blocker.bind(InetSocketAddress("127.0.0.1", SOCKS_PORT))
            runOnMain { manager.start() }
            val state = waitFor(20_000) { manager.state.value.takeIf { it is SessionState.Failed || it is SessionState.Running } }
            assertTrue("expected a failed start, got $state", state is SessionState.Failed)
        }
    }

    // --- Helpers ------------------------------------------------------------

    private fun startAndWait() {
        runOnMain { manager.start() }
        val state = waitFor(20_000) { manager.state.value.takeIf { it is SessionState.Failed || it is SessionState.Running } }
        assertTrue("session didn't start: $state", state is SessionState.Running)
    }

    private fun stopAndWait() {
        runOnMain { manager.stop() }
        waitFor(20_000) { manager.state.value.takeIf { it !is SessionState.Running && it !is SessionState.Starting } }
    }

    private fun runOnMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T : Any> waitFor(timeoutMs: Long, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            probe()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "timed out after ${timeoutMs}ms (state ${manager.state.value})" }
            Thread.sleep(200)
        }
    }

    private fun <T> retry(timeoutMs: Long, attempt: () -> T): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                return attempt()
            } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(1_000)
            }
        }
    }

    /** A raw HTTP/1.0 GET on [socket]; returns the whole response. */
    private fun httpGet(socket: Socket): String = socket.use { s ->
        s.soTimeout = 30_000
        s.getOutputStream().write("GET / HTTP/1.0\r\nHost: $targetHost\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().decodeToString()
    }

    /** A socket through the session's SOCKS5 proxy to [host]:[port], by name (the proxy resolves it). */
    private fun socks5Connect(host: String, port: Int): Socket {
        val s = Socket("127.0.0.1", SOCKS_PORT)
        try {
            s.soTimeout = 60_000
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            val choice = ByteArray(2).also { input.readFully(it) }
            check(choice[0] == 5.toByte() && choice[1] == 0.toByte()) { "no-auth refused: ${choice.toList()}" }
            val name = host.toByteArray()
            out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
            val reply = ByteArray(4).also { input.readFully(it) }
            check(reply[1] == 0.toByte()) { "CONNECT failed with code ${reply[1]}" }
            val addressLength = when (reply[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> input.readUnsignedByte()
                else -> error("bad address type ${reply[3]}")
            }
            input.readFully(ByteArray(addressLength + 2))
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    private companion object {
        const val SOCKS_PORT = 1080
        const val FORWARD_PORT = 18080
    }
}
