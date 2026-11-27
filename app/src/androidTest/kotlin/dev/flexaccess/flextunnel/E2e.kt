package dev.flexaccess.flextunnel

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.flexaccess.flextunnel.tunnelcore.Destination
import dev.flexaccess.flextunnel.tunnelcore.SessionSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What scripts/e2e.sh hands the end-to-end tests, and what they share.
 *
 * The script runs three flextunnel servers on its machine, all with that
 * machine's loopback as their own, and passes their ids in:
 *  - [SITE] routes the fixture site's host alias [SITE_HOST] (a name only it
 *    resolves, so a page that loads came through it) and `localhost`;
 *  - [PLAIN] routes nothing: its loopback name still reaches its loopback;
 *  - [CIDR] routes [CIDR_RANGE] (TEST-NET-3, which nothing answers) and logs
 *    each stream's target.
 * The fixture site listens on that loopback at [sitePort]. A control endpoint
 * there, at [controlPort], stops and restarts [SITE] and [CIDR] and serves
 * their logs; the device cannot see that machine's loopback, so the tests
 * reach it through [PLAIN], which is never stopped — every test that uses it
 * has [PLAIN] among its destinations.
 *
 * Everything goes through the app as another app on the phone would use it:
 * its SOCKS5 proxy at `127.0.0.1:`[SOCKS_PORT] and its forwards. Without the
 * script's arguments every test is skipped, so a plain `connectedAndroidTest`
 * passes vacuously.
 */
object E2e {
    const val SOCKS_PORT = 1080
    const val SITE = "site"
    const val PLAIN = "plain"
    const val CIDR = "cidr"
    const val SITE_HOST = "e2e.flextunnel.test"
    const val CIDR_RANGE = "203.0.113.0/24"
    private const val KEY_NAME = "e2e"

    /** The fixture pages' texts (scripts/e2e/fixtures). */
    const val HOME_TEXT = "flextunnel e2e home"
    const val PAGE_TWO_TEXT = "flextunnel e2e page two"

    /** What [DeviceSite] answers: a request that got it went direct, not through a server. */
    const val DIRECT_TEXT = "answered on the device"

    /** A destination's first use connects its server through iroh discovery. */
    const val CONNECT_TIMEOUT = 90_000L

    /**
     * The core holds a routed request up to 45 s for its destination to
     * connect before failing it; a restarted server is found again by
     * discovery.
     */
    const val OUTAGE_TIMEOUT = 120_000L

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()

    val siteNodeId: String get() = args.getString("siteNodeId")!!
    val plainNodeId: String get() = args.getString("plainNodeId")!!
    val cidrNodeId: String get() = args.getString("cidrNodeId")!!
    val authKey: String get() = args.getString("authKey")!!
    val sitePort: Int get() = args.getString("sitePort")!!.toInt()
    val controlPort: Int get() = args.getString("controlPort")!!.toInt()

    val manager: SessionManager get() = SessionManager.get(instrumentation.targetContext)

    fun assumeServers() = assumeTrue(
        "scripts/e2e.sh passes the servers",
        listOf("siteNodeId", "plainNodeId", "cidrNodeId", "authKey", "sitePort", "controlPort").all { args.getString(it) != null },
    )

    fun loopback(name: String) = "$name.${Destination.LOOPBACK_SUFFIX}"

    /** The destination for one of the script's servers, routing what that server routes. */
    fun destination(name: String): Destination = when (name) {
        SITE -> Destination(SITE, SITE, siteNodeId, routedDomains = listOf(SITE_HOST, "localhost"))
        PLAIN -> Destination(PLAIN, PLAIN, plainNodeId)
        CIDR -> Destination(CIDR, CIDR, cidrNodeId, routedCidrs = listOf(CIDR_RANGE))
        else -> error("no server $name")
    }

    /**
     * No session, the test key selected, [names]' destinations, no forwards,
     * and the SOCKS5 proxy on [SOCKS_PORT] with the default relays — then
     * [settings] applied to that.
     */
    fun reset(vararg names: String, settings: (SessionSettings) -> SessionSettings = { it }) {
        grantPermissions()
        stopAndWait()
        val keys = manager.authKeys
        val public = AuthKey.publicKey(authKey)
        // A run's key is fresh: one left by an earlier run holds the name.
        keys.keys.value.filter { it.name == KEY_NAME && it.publicKey != public }.forEach { assertNull(keys.delete(it.id)) }
        val key = keys.keys.value.firstOrNull { it.publicKey == public }
            ?: keys.add(KEY_NAME, authKey).getOrThrow()
        val profiles = manager.profiles
        profiles.forwards.value.forEach { assertNull(profiles.deleteForward(it.id)) }
        profiles.destinations.value.forEach { assertNull(profiles.deleteDestination(it.id)) }
        assertNull(profiles.saveSettings(settings(SessionSettings(socksPort = SOCKS_PORT, authKeyId = key.id)), null))
        names.forEach { assertNull(profiles.saveDestination(destination(it))) }
    }

    /** What the app asks before a start, granted up front so no system dialog stands in the way. */
    private fun grantPermissions() {
        val automation = instrumentation.uiAutomation
        val pkg = instrumentation.targetContext.packageName
        automation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) automation.grantRuntimePermission(pkg, Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    // --- The session ------------------------------------------------------

    fun runOnMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    fun startAndWait(): SessionState.Running {
        runOnMain { manager.start() }
        return waitForRunning()
    }

    /** Waits for the session to be serving, failing on a failed start. */
    fun waitForRunning(): SessionState.Running {
        val state = waitFor(20_000, "the session to start") {
            manager.state.value.takeIf { it is SessionState.Failed || it is SessionState.Running }
        }
        assertTrue("the session didn't start: $state", state is SessionState.Running)
        return state as SessionState.Running
    }

    fun stopAndWait() {
        runOnMain { manager.stop() }
        waitFor(20_000, "the session to stop") {
            manager.state.value.takeIf { it is SessionState.Stopped || it is SessionState.Failed }
        }
    }

    fun <T : Any> waitFor(timeoutMs: Long, what: String, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            probe()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "timed out after ${timeoutMs}ms waiting for $what (session ${manager.state.value})" }
            Thread.sleep(250)
        }
    }

    /** [attempt] until it returns, for up to [timeoutMs]; then its last failure, a failed assertion included. */
    fun <T> retry(timeoutMs: Long, attempt: () -> T): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                return attempt()
            } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) throw e
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > deadline) throw e
            }
            Thread.sleep(1_000)
        }
    }

    // --- SOCKS5 and HTTP --------------------------------------------------

    class SocksRefused(val code: Int) : IOException("SOCKS5 CONNECT refused with reply code $code")

    /**
     * A connection to [host]:[port] through the SOCKS5 proxy: by name (the
     * proxy resolves it, as `socks5h://` does) or, for an IPv4 literal, by
     * address. Throws [SocksRefused] when the proxy answers the CONNECT with
     * a failure.
     */
    fun socks(host: String, port: Int, socksPort: Int = SOCKS_PORT, timeoutMs: Int = 60_000): Socket {
        val s = Socket("127.0.0.1", socksPort)
        try {
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            val choice = ByteArray(2).also { input.readFully(it) }
            check(choice[0] == 5.toByte() && choice[1] == 0.toByte()) { "no-auth refused: ${choice.toList()}" }
            val ipv4 = host.split('.').takeIf { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }
            val address = if (ipv4 != null) {
                byteArrayOf(1) + ipv4.map { it.toInt().toByte() }.toByteArray()
            } else {
                val name = host.toByteArray()
                byteArrayOf(3, name.size.toByte()) + name
            }
            out.write(byteArrayOf(5, 1, 0) + address + byteArrayOf((port shr 8).toByte(), port.toByte()))
            val reply = ByteArray(4).also { input.readFully(it) }
            if (reply[1] != 0.toByte()) throw SocksRefused(reply[1].toInt())
            val boundLength = when (reply[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> input.readUnsignedByte()
                else -> error("bad address type ${reply[3]}")
            }
            input.readFully(ByteArray(boundLength + 2))
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    data class Response(val status: Int, val body: String)

    /** One HTTP/1.0 request on [socket], which it closes; the whole response. */
    fun http(socket: Socket, host: String, path: String = "/", method: String = "GET"): Response = socket.use { s ->
        s.soTimeout = 60_000
        s.getOutputStream().write("$method $path HTTP/1.0\r\nHost: $host\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val raw = s.getInputStream().readBytes().decodeToString()
        val status = raw.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("not an HTTP response: ${raw.take(200)}")
        Response(status, raw.substringAfter("\r\n\r\n", ""))
    }

    /** GET [path] from [host]:[port] through the SOCKS5 proxy. */
    fun get(host: String, port: Int, path: String = "/", socksPort: Int = SOCKS_PORT): Response =
        http(socks(host, port, socksPort), if (port == 80) host else "$host:$port", path)

    /** GET through the SOCKS5 proxy, again and again until it answers 200 with [text] in its body. */
    fun getUntil(host: String, port: Int, path: String, text: String, timeoutMs: Long = CONNECT_TIMEOUT, socksPort: Int = SOCKS_PORT): String =
        retry(timeoutMs) {
            val response = get(host, port, path, socksPort)
            check(response.status == 200 && text in response.body) { "$host:$port$path answered ${response.status}: ${response.body.take(200)}" }
            response.body
        }

    /** GET straight from a local port: a forward's. */
    fun getLocal(port: Int, host: String, path: String = "/"): Response = http(Socket("127.0.0.1", port), host, path)

    // --- What the client and the servers say ------------------------------

    /** The client's own status (`flextunnel.internal/status.json`), by destination name. Connects nothing. */
    fun clientStatus(): Map<String, JSONObject> {
        val body = getUntil("flextunnel.internal", 80, "/status.json", "destinations", timeoutMs = 10_000)
        val array = JSONObject(body).getJSONArray("destinations")
        return (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("name") }
    }

    fun destinationState(name: String): String? = clientStatus()[name]?.getString("state")

    fun waitForDestinationState(name: String, state: String, timeoutMs: Long = 20_000) {
        waitFor(timeoutMs, "$name to be $state") { destinationState(name)?.takeIf { it == state } }
    }

    /** Sends [method] [path] to the control endpoint, through [PLAIN]; the body of its 200. */
    private fun control(method: String, path: String): String = retry(CONNECT_TIMEOUT) {
        val response = http(socks(loopback(PLAIN), controlPort), "127.0.0.1:$controlPort", path, method)
        check(response.status == 200) { "control $method $path: ${response.status} ${response.body}" }
        response.body
    }

    /** Stops [server] (it closes its endpoint) and waits for the client to see its destination drop. */
    fun takeDown(server: String) {
        control("POST", "/$server/stop")
        waitFor(30_000, "$server to drop") { destinationState(server)?.takeIf { it != "connected" } }
    }

    /** Starts [server] again. Nothing connects to it until it is next used. */
    fun bringBack(server: String) {
        control("POST", "/$server/start")
    }

    fun serverLog(server: String): String = control("GET", "/$server/log")

    fun waitForServerLog(server: String, text: String, timeoutMs: Long) {
        waitFor(timeoutMs, "$server's log to show $text") { serverLog(server).takeIf { text in it } }
    }

    /**
     * An HTTP server on the device itself at [port] (both loopbacks), answering
     * [DIRECT_TEXT] and counting what it accepts: what reaches it went direct.
     * Given the site's port, a request for `localhost` there is told apart by
     * where it was answered.
     */
    class DeviceSite(port: Int) : Closeable {
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        private val count = AtomicInteger()
        val accepted: Int get() = count.get()

        init {
            thread(isDaemon = true, name = "e2e-device-site") {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    count.incrementAndGet()
                    thread(isDaemon = true) {
                        runCatching {
                            client.use { c ->
                                val reader = c.getInputStream().bufferedReader()
                                while (!reader.readLine().isNullOrEmpty()) Unit
                                c.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\n$DIRECT_TEXT\n".toByteArray())
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    fun assertAnswered(expected: String, response: Response) {
        assertEquals("status of ${response.body.take(200)}", 200, response.status)
        assertTrue("expected \"$expected\" in: ${response.body.take(200)}", expected in response.body)
    }
}
