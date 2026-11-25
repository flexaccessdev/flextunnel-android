package dev.flexaccess.flextunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.flexaccess.flextunnel.tunnelcore.Snapshots
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The session's foreground service. While it runs, the SOCKS5 proxy on its
 * fixed loopback port and the enabled port forwards serve other apps on the
 * device; the ongoing notification says so and carries Stop. Android keeps a
 * foreground service's process alive and on the network (Doze included), so
 * nothing more is needed to stay up in the background.
 *
 * Every native call runs on one thread ([session]), which is what makes the
 * library's handle contract hold: `stop` never races another call. The service
 * reports to [SessionManager]; the UI talks to it only through there.
 *
 * Alongside the handle it keeps: the default-network callback
 * (`setNetworkAvailable`), the app's visibility (`setBackground`: the
 * heartbeat slows while no screen of the app is showing), and a Wi-Fi
 * MulticastLock for iroh's mDNS discovery of servers on the same LAN.
 */
class TunnelService : Service() {
    private val session = Executors.newSingleThreadExecutor { Thread(it, "flextunnel-session") }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var manager: SessionManager

    /** The native handle; touched only on [session]. */
    private var handle = 0L

    /** Whether a screen of the app is showing; main thread. */
    private var visible = false

    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> setVisible(true)
            Lifecycle.Event.ON_STOP -> setVisible(false)
            else -> {}
        }
    }

    private val pollTick = Runnable { onSession { poll() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = SessionManager.get(this)
        manager.service = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the SOCKS5 proxy and port forwards are on."
            },
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            else -> {
                // Foreground within the start's deadline, whatever happens next.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification("Starting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                onSession { startSession() }
            }
        }
        // A session the system ended stays ended: the user starts it again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(pollTick)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        // Before the session thread goes: no network callback should be left to
        // hand it work (one already in flight is dropped by onSession).
        releaseSystemHooks()
        // Synchronous: the process may go right after.
        runCatching { session.submit { stopNative() }.get() }
        session.shutdown()
        // Ended without stopSession or fail (the system stopped it): don't leave
        // the UI at Starting/Running. A terminal state already reported stays.
        val state = manager.state.value
        if (state is SessionState.Starting || state is SessionState.Running) manager.reportState(SessionState.Stopped)
        if (manager.service === this) manager.service = null
        super.onDestroy()
    }

    /** Stop the session and the service. */
    fun stopSession() {
        onSession {
            stopNative()
            main.post {
                releaseSystemHooks()
                manager.reportState(SessionState.Stopped)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Stop and start again, with the stores as they are now. */
    fun restart() {
        onSession {
            stopNative()
            main.post { manager.reportState(SessionState.Starting) }
            startSession()
        }
    }

    /** Hand the current forward set to the running session. */
    fun applyForwards() {
        onSession {
            if (handle == 0L) return@onSession
            pushForwards()
            poll(reschedule = false)
            val running = SessionState.Running(manager.profiles.settings.value.takeIf { it.socksEnabled }?.socksPort)
            main.post { updateNotification(running) }
        }
    }

    /** The named destination's path JSON, or null while no session runs. Blocks the caller. */
    fun connPath(name: String): String? = runCatching {
        session.submit<String?> { if (handle == 0L) null else FlextunnelNative.connPath(handle, name) }.get()
    }.getOrNull()

    // --- On the session thread -------------------------------------------

    /**
     * Run [block] on the session thread. Whatever it throws (the library's
     * IllegalStateException, a snapshot that doesn't decode) ends the session
     * through [fail] rather than escaping and killing the process. Work
     * handed over after [onDestroy] shut the thread down is dropped: there is
     * no session left for it.
     */
    private fun onSession(block: () -> Unit) {
        try {
            session.execute {
                try {
                    block()
                } catch (e: Exception) {
                    fail("The session failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun startSession() {
        if (handle != 0L) return
        val config = manager.buildConfig().getOrElse { return fail(it.message ?: "Can't start.") }
        handle = try {
            FlextunnelNative.start(config)
        } catch (e: IllegalStateException) {
            return fail(e.message ?: "The session didn't start.")
        }
        FlextunnelNative.setBackground(handle, !visible)
        pushForwards()
        val settings = manager.profiles.settings.value
        val running = SessionState.Running(if (settings.socksEnabled) settings.socksPort else null)
        main.post {
            acquireSystemHooks()
            manager.reportState(running)
            updateNotification(running)
        }
        poll()
    }

    private fun pushForwards() {
        val error = try {
            FlextunnelNative.setForwards(handle, manager.forwardsJson())
            null
        } catch (e: IllegalStateException) {
            e.message ?: "The forwards were refused."
        }
        main.post { manager.reportForwardError(error) }
    }

    /**
     * Health always; the destinations' and forwards' state while a screen of
     * the app shows them. Every second while visible, every 15 seconds while
     * not — health only matters for the rare listener that is lost for good.
     */
    private fun poll(reschedule: Boolean = true) {
        if (handle == 0L) return
        if (!FlextunnelNative.isServing(handle)) {
            return fail("The session ended: a local listener was lost (its port was taken).")
        }
        if (visible) {
            val destinations = Snapshots.status(FlextunnelNative.status(handle))
            val forwards = Snapshots.forwardStatuses(FlextunnelNative.forwardStatuses(handle))
            main.post { manager.reportStatus(destinations, forwards) }
        }
        if (reschedule) {
            main.removeCallbacks(pollTick)
            main.postDelayed(pollTick, if (visible) 1_000L else 15_000L)
        }
    }

    private fun stopNative() {
        main.removeCallbacks(pollTick)
        if (handle == 0L) return
        val h = handle
        handle = 0L
        FlextunnelNative.stop(h)
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        // Already failing: a throwing stop must not escape the session thread.
        runCatching { stopNative() }
        main.post {
            releaseSystemHooks()
            manager.reportState(SessionState.Failed(message))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --- On the main thread -----------------------------------------------

    private fun setVisible(value: Boolean) {
        visible = value
        onSession {
            if (handle != 0L) FlextunnelNative.setBackground(handle, !value)
            if (value) poll()
        }
    }

    private fun acquireSystemHooks() {
        if (multicastLock == null) {
            multicastLock = getSystemService(WifiManager::class.java)
                ?.createMulticastLock("flextunnel-mdns")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (networkCallback == null) {
            val cm = getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = report(true)

                override fun onLost(network: Network) = report(cm.activeNetwork != null)

                private fun report(available: Boolean) = onSession {
                    if (handle != 0L) FlextunnelNative.setNetworkAvailable(handle, available)
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun releaseSystemHooks() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun updateNotification(state: SessionState.Running) {
        val forwards = manager.profiles.forwards.value.count { it.enabled }
        val parts = buildList {
            state.socksPort?.let { add("SOCKS5 127.0.0.1:$it") }
            add(if (forwards == 1) "1 forward" else "$forwards forwards")
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(parts.joinToString(" · ")))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Flextunnel is on")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.flexaccess.flextunnel.START"
        const val ACTION_STOP = "dev.flexaccess.flextunnel.STOP"
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "flextunnel"
    }
}
