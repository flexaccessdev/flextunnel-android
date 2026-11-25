package dev.flexaccess.flextunnel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flexaccess.flextunnel.BuildConfig
import dev.flexaccess.flextunnel.SessionManager
import dev.flexaccess.flextunnel.SessionState
import dev.flexaccess.flextunnel.tunnelcore.Destination
import dev.flexaccess.flextunnel.tunnelcore.DestinationStatus
import dev.flexaccess.flextunnel.tunnelcore.ForwardStatus
import dev.flexaccess.flextunnel.tunnelcore.PortForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    manager: SessionManager,
    onStart: () -> Unit,
    onEditDestination: (String?) -> Unit,
    onEditForward: (String?) -> Unit,
    onSettings: () -> Unit,
    onBrowserHelp: () -> Unit,
) {
    val state by manager.state.collectAsStateWithLifecycle()
    val destinations by manager.profiles.destinations.collectAsStateWithLifecycle()
    val forwards by manager.profiles.forwards.collectAsStateWithLifecycle()
    val settings by manager.profiles.settings.collectAsStateWithLifecycle()
    val destinationStatus by manager.destinationStatus.collectAsStateWithLifecycle()
    val forwardStatus by manager.forwardStatus.collectAsStateWithLifecycle()
    val forwardError by manager.forwardError.collectAsStateWithLifecycle()
    val keys by manager.authKeys.keys.collectAsStateWithLifecycle()
    var pathFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flextunnel") },
                actions = {
                    IconButton(onClick = onBrowserHelp, modifier = Modifier.testTag("browserHelp")) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Using the proxy")
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding) {
            item {
                SessionCard(
                    state = state,
                    socksPort = if (settings.socksEnabled) settings.socksPort else null,
                    hasKey = settings.authKeyId?.let { id -> keys.any { it.id == id } } == true,
                    hasDestination = destinations.isNotEmpty(),
                    onStart = onStart,
                    onStop = { manager.stop() },
                    onSettings = onSettings,
                    onBrowserHelp = onBrowserHelp,
                )
            }
            item {
                Header("Destinations") { onEditDestination(null) }
            }
            if (destinations.isEmpty()) {
                item { Footnote("A destination is one flextunnel server: its name and the node id it prints.") }
            }
            items(destinations, key = { "d" + it.id }) { destination ->
                DestinationRow(
                    destination = destination,
                    status = destinationStatus.firstOrNull { it.name == destination.name },
                    onEdit = { onEditDestination(destination.id) },
                    onPath = { pathFor = destination.name },
                )
                HorizontalDivider()
            }
            item {
                Header("Port forwards", enabled = destinations.isNotEmpty()) { onEditForward(null) }
            }
            if (forwards.isEmpty()) {
                item {
                    Footnote(
                        "A forward is a local port other apps connect to, carried to a host and port a " +
                            "destination routes — e.g. 2222 → <name>.localhost.flextunnel.internal:22.",
                    )
                }
            }
            forwardError?.let { item { Footnote("Forwards refused: $it", MaterialTheme.colorScheme.error) } }
            items(forwards, key = { "f" + it.id }) { forward ->
                ForwardRow(
                    forward = forward,
                    status = forwardStatus[forward.id].takeIf { state is SessionState.Running },
                    onEdit = { onEditForward(forward.id) },
                    onToggle = { enabled ->
                        scope.launch {
                            // Applied with the write, so leaving the screen can't skip it.
                            val message = withContext(Dispatchers.IO) {
                                manager.profiles.setForwardEnabled(forward.id, enabled).also { if (it == null) manager.applyForwards() }
                            }
                            if (message != null) error = message
                        }
                    },
                )
                HorizontalDivider()
            }
            item {
                Footnote("Flextunnel ${BuildConfig.VERSION_NAME} · core ${BuildConfig.FLEXTUNNEL_CORE_VERSION} · alpha")
            }
        }
    }

    pathFor?.let { name -> ConnPathSheet(manager, name, onDismiss = { pathFor = null }) }
    ErrorDialog(error) { error = null }
}

@Composable
private fun Header(title: String, enabled: Boolean = true, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(title, Modifier.weight(1f))
        IconButton(onClick = onAdd, enabled = enabled, modifier = Modifier.testTag("add-$title")) {
            Icon(Icons.Default.Add, contentDescription = "Add to $title")
        }
    }
}

@Composable
private fun SessionCard(
    state: SessionState,
    socksPort: Int?,
    hasKey: Boolean,
    hasDestination: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onBrowserHelp: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val (label, color) = when (state) {
                SessionState.Stopped -> "Off" to stateColor("idle")
                SessionState.Starting -> "Starting…" to stateColor("connecting")
                is SessionState.Running -> "On" to stateColor("connected")
                is SessionState.Failed -> "Off" to stateColor("failed")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color, 12)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("sessionState"))
                when (state) {
                    SessionState.Stopped, is SessionState.Failed -> Button(
                        onClick = onStart,
                        enabled = hasKey && hasDestination,
                        modifier = Modifier.testTag("start"),
                    ) { Text("Start") }
                    SessionState.Starting, is SessionState.Running -> OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.testTag("stop"),
                    ) { Text("Stop") }
                }
            }
            if (state is SessionState.Failed) {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (!hasKey) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose an auth key to start.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onSettings) { Text("Settings") }
                }
            }
            if (socksPort != null) {
                val address = "127.0.0.1:$socksPort"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SOCKS5 proxy", style = MaterialTheme.typography.labelLarge)
                        Text(address, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = { Clipboard.copy(context, "SOCKS5 proxy", address) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy the SOCKS5 address")
                    }
                }
                Text(
                    "How to use it: remote DNS, other apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onBrowserHelp),
                )
            } else {
                Footnote("The SOCKS5 proxy is off (Settings); only the forwards serve.")
            }
        }
    }
}

@Composable
private fun DestinationRow(destination: Destination, status: DestinationStatus?, onEdit: () -> Unit, onPath: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(stateColor(status?.state ?: "idle"))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(destination.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                status?.let { describe(it) } ?: destination.loopbackHost,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status?.state == "connected") {
            IconButton(onClick = onPath) { Icon(Icons.Default.Route, contentDescription = "Connection path of ${destination.name}") }
        }
    }
}

private fun describe(s: DestinationStatus): String = when (s.state) {
    "connected" -> "Connected" + (s.connectedSecs?.let { " ${it}s" } ?: "") + " · ${s.openStreams} open"
    "connecting" -> "Connecting…"
    "backoff" -> "Retrying" + (s.nextAttemptSecs?.let { " in ${it}s" } ?: "") + (s.lastError?.let { " · $it" } ?: "")
    "failed" -> "Refused" + (s.lastError?.let { ": $it" } ?: "")
    else -> "Idle — connects on first use" + (s.lastError?.let { " · last error: $it" } ?: "")
}

@Composable
private fun ForwardRow(forward: PortForward, status: ForwardStatus?, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(forward.label.ifEmpty { "Port ${forward.localPort}" }, style = MaterialTheme.typography.bodyLarge)
            Text(
                "127.0.0.1:${forward.localPort} → ${forward.remoteHost}:${forward.remotePort}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (forward.enabled && status != null) {
                val text = when (status.state) {
                    "listening" -> "Listening · ${status.active} active" + (status.lastConnError?.let { " · last: $it" } ?: "")
                    "failed" -> "Failed: ${status.error ?: "unknown"}"
                    else -> "Starting…"
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = forward.enabled, onCheckedChange = onToggle, modifier = Modifier.testTag("toggle-${forward.id}"))
    }
}
