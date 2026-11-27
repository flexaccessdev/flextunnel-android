package dev.flexaccess.flextunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flexaccess.flextunnel.SessionManager
import dev.flexaccess.flextunnel.tunnelcore.Destination
import dev.flexaccess.flextunnel.tunnelcore.PortForward
import dev.flexaccess.flextunnel.tunnelcore.RuleException
import dev.flexaccess.flextunnel.tunnelcore.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A screen with a back arrow, a Save action and a scrolling form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormScaffold(title: String, onBack: () -> Unit, onSave: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = onSave, modifier = Modifier.testTag("save")) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    number: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Add (id null) or edit one destination; a running session restarts with the change. */
@Composable
fun DestinationEditScreen(manager: SessionManager, id: String?, onDone: () -> Unit) {
    val existing = remember(id) { id?.let { i -> manager.profiles.destinations.value.firstOrNull { it.id == i } } }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var nodeId by rememberSaveable { mutableStateOf(existing?.serverNodeId ?: "") }
    var domains by rememberSaveable { mutableStateOf(existing?.routedDomains?.joinToString("\n") ?: "") }
    var cidrs by rememberSaveable { mutableStateOf(existing?.routedCidrs?.joinToString("\n") ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() = scope.launch {
        val destination = Destination(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            serverNodeId = nodeId,
            routedDomains = Rules.splitRules(domains),
            routedCidrs = Rules.splitRules(cidrs),
        )
        // The restart rides with the write: leaving the screen mid-save cancels
        // this coroutine, never a saved change's restart.
        val message = withContext(Dispatchers.IO) {
            manager.profiles.saveDestination(destination).also { if (it == null) manager.restartIfRunning() }
        }
        if (message != null) error = message else onDone()
    }

    FormScaffold(if (existing == null) "Add destination" else "Edit destination", onBack = onDone, onSave = { save() }) {
        Field(name, { name = it }, "Name (one DNS label, e.g. machine1)", "name")
        Footnote("Reach the server's own ports as ${(name.ifBlank { "<name>" }).lowercase()}.localhost.flextunnel.internal.")
        Field(nodeId, { nodeId = it.trim() }, "Server node id", "nodeId")
        SectionTitle("Routes (optional)")
        Footnote(
            "Further hosts the SOCKS5 proxy and the forwards send through this server — the server must route " +
                "them too. One per line. Anything routed nowhere connects directly from the phone (SOCKS5 only).",
        )
        // Locked while the server is asked: its answer replaces them.
        Field(domains, { domains = it }, "Domains: name, *.suffix or *", "domains", singleLine = false, enabled = !fetching)
        Field(cidrs, { cidrs = it }, "IP ranges: 10.0.0.0/8, one address", "cidrs", singleLine = false, enabled = !fetching)
        OutlinedButton(
            modifier = Modifier.testTag("fetchRoutes"),
            enabled = !fetching && nodeId.isNotBlank(),
            onClick = {
                fetching = true
                val requested = nodeId
                scope.launch {
                    val result = withContext(Dispatchers.IO) { manager.fetchServerRoutes(requested) }
                    fetching = false
                    // The answer is for the id asked about; dropped if it was edited since.
                    if (nodeId != requested) return@launch
                    result.onSuccess { (d, c) ->
                        domains = d.joinToString("\n")
                        cidrs = c.joinToString("\n")
                    }.onFailure { error = "Couldn't ask the server: ${it.message}" }
                }
            },
        ) { Text(if (fetching) "Asking the server…" else "Fetch from server") }
        Footnote("Fills the routes with the server's own lists, using the auth key and relays from Settings.")
        if (existing != null) {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete destination", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val message = withContext(Dispatchers.IO) {
                            manager.profiles.deleteDestination(existing.id).also { if (it == null) manager.restartIfRunning() }
                        }
                        if (message != null) error = message else onDone()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    ErrorDialog(error) { error = null }
}

/** Add (id null) or edit one port forward; a running session applies it in place. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ForwardEditScreen(manager: SessionManager, id: String?, onDone: () -> Unit) {
    val existing = remember(id) { id?.let { i -> manager.profiles.forwards.value.firstOrNull { it.id == i } } }
    val destinations by manager.profiles.destinations.collectAsStateWithLifecycle()
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "") }
    var localPort by rememberSaveable { mutableStateOf(existing?.localPort?.toString() ?: "") }
    var remoteHost by rememberSaveable { mutableStateOf(existing?.remoteHost ?: "") }
    var remotePort by rememberSaveable { mutableStateOf(existing?.remotePort?.toString() ?: "") }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save() = scope.launch {
        val forward = try {
            PortForward(
                id = existing?.id ?: UUID.randomUUID().toString(),
                label = label,
                localPort = Rules.port(localPort, "Local port"),
                remoteHost = remoteHost,
                remotePort = Rules.port(remotePort, "Remote port"),
                enabled = enabled,
            )
        } catch (e: RuleException) {
            error = e.message
            return@launch
        }
        val message = withContext(Dispatchers.IO) {
            manager.profiles.saveForward(forward).also { if (it == null) manager.applyForwards() }
        }
        if (message != null) error = message else onDone()
    }

    FormScaffold(if (existing == null) "Add port forward" else "Edit port forward", onBack = onDone, onSave = { save() }) {
        Field(label, { label = it }, "Label (optional)", "label")
        Field(localPort, { localPort = it.filter(Char::isDigit) }, "Local port (127.0.0.1 and ::1)", "localPort", number = true)
        Field(remoteHost, { remoteHost = it.trim() }, "Remote host", "remoteHost")
        if (destinations.isNotEmpty()) {
            Footnote("A server's own ports:")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                destinations.forEach { d -> AssistChip(onClick = { remoteHost = d.loopbackHost }, label = { Text(d.loopbackHost) }) }
            }
        }
        Field(remotePort, { remotePort = it.filter(Char::isDigit) }, "Remote port", "remotePort", number = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it }, modifier = Modifier.testTag("enabled"))
        }
        Footnote("A forward never connects directly: its host must be a server's loopback name or on a destination's routes.")
        if (existing != null) {
            TextButton(onClick = {
                scope.launch {
                    val message = withContext(Dispatchers.IO) {
                        manager.profiles.deleteForward(existing.id).also { if (it == null) manager.applyForwards() }
                    }
                    if (message != null) error = message else onDone()
                }
            }) { Text("Delete forward", color = MaterialTheme.colorScheme.error) }
        }
    }
    ErrorDialog(error) { error = null }
}
