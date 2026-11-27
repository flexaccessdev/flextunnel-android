package dev.flexaccess.flextunnel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.flexaccess.flextunnel.tunnelcore.ConnPathSnapshot
import dev.flexaccess.flextunnel.tunnelcore.DestinationStatus
import dev.flexaccess.flextunnel.tunnelcore.FfiJson
import dev.flexaccess.flextunnel.tunnelcore.ForwardStatus
import dev.flexaccess.flextunnel.tunnelcore.Snapshots
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the one session is. */
sealed interface SessionState {
    data object Stopped : SessionState
    data object Starting : SessionState

    /** Serving: the SOCKS5 proxy on [socksPort] (null when off) and the enabled forwards. */
    data class Running(val socksPort: Int?) : SessionState

    /** The last start failed, or the session ended on its own; stopped since. */
    data class Failed(val message: String) : SessionState
}

/**
 * The app's one session and what it is made of, shared by the UI and
 * [TunnelService]. The service owns the native handle and reports here; the
 * UI starts and stops it through here, and changes the stores, after which
 * [applyForwards] or [restartIfRunning] carries the change into a running
 * session (a forward change applies in place; anything else restarts it).
 */
class SessionManager(private val context: Context) {
    private val secrets: SecretStore = KeystoreSecretStore(context)
    val profiles = ProfileStore(context, secrets)
    val authKeys = AuthKeyStore(secrets)

    private val _state = MutableStateFlow<SessionState>(SessionState.Stopped)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _destinationStatus = MutableStateFlow<List<DestinationStatus>>(emptyList())
    val destinationStatus: StateFlow<List<DestinationStatus>> = _destinationStatus.asStateFlow()

    private val _forwardStatus = MutableStateFlow<Map<String, ForwardStatus>>(emptyMap())
    val forwardStatus: StateFlow<Map<String, ForwardStatus>> = _forwardStatus.asStateFlow()

    /** The core's refusal of the last forward set, or null once one was accepted. */
    private val _forwardError = MutableStateFlow<String?>(null)
    val forwardError: StateFlow<String?> = _forwardError.asStateFlow()

    /** The running service, while there is one. */
    @Volatile
    internal var service: TunnelService? = null

    fun start() {
        val current = _state.value
        if (current is SessionState.Starting || current is SessionState.Running) return
        _state.value = SessionState.Starting
        ContextCompat.startForegroundService(
            context,
            Intent(context, TunnelService::class.java).setAction(TunnelService.ACTION_START),
        )
    }

    fun stop() {
        val running = service
        if (running != null) running.stopSession() else _state.value = SessionState.Stopped
    }

    /** After a forward was added, changed, toggled or deleted. */
    fun applyForwards() {
        service?.applyForwards()
    }

    /** After a destination or a setting changed: a running session restarts with it. */
    fun restartIfRunning() {
        if (_state.value is SessionState.Running) service?.restart()
    }

    /** The named destination's live path; null while no session runs. Blocks: off the main thread. */
    fun connPath(name: String): ConnPathSnapshot? = service?.connPath(name)?.let { Snapshots.connPath(it) }

    /**
     * Ask the server [serverNodeId] what it routes, with the session's key and
     * relays (no session needed). Blocks for seconds: off the main thread.
     */
    fun fetchServerRoutes(serverNodeId: String): Result<Pair<List<String>, List<String>>> = runCatching {
        val key = selectedSecret() ?: throw IllegalStateException(NO_KEY)
        val json = FlextunnelNative.fetchServerRoutes(
            FfiJson.routesRequest(key, serverNodeId.trim(), profiles.settings.value, profiles.relayToken()),
        )
        Snapshots.serverRoutes(json)
    }

    /** `start`'s config from the stores, or why there can't be a session yet. */
    internal fun buildConfig(): Result<String> {
        val destinations = profiles.destinations.value
        if (destinations.isEmpty()) return Result.failure(IllegalStateException("Add a destination first."))
        val key = selectedSecret() ?: return Result.failure(IllegalStateException(NO_KEY))
        return Result.success(FfiJson.config(key, profiles.settings.value, profiles.relayToken(), destinations))
    }

    internal fun forwardsJson(): String = FfiJson.forwards(profiles.forwards.value)

    private fun selectedSecret(): String? = profiles.settings.value.authKeyId?.let { authKeys.key(it)?.secret }

    internal fun reportState(state: SessionState) {
        _state.value = state
        if (state !is SessionState.Running) {
            _destinationStatus.value = emptyList()
            _forwardStatus.value = emptyMap()
            _forwardError.value = null
        }
    }

    internal fun reportStatus(destinations: List<DestinationStatus>, forwards: List<ForwardStatus>) {
        _destinationStatus.value = destinations
        _forwardStatus.value = forwards.associateBy { it.id }
    }

    internal fun reportForwardError(message: String?) {
        _forwardError.value = message
    }

    companion object {
        private const val NO_KEY = "Choose an auth key in Settings first."

        fun get(context: Context): SessionManager =
            (context.applicationContext as FlextunnelApplication).session
    }
}
