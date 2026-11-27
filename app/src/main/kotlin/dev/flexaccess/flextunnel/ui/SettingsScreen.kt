package dev.flexaccess.flextunnel.ui

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flexaccess.flextunnel.SessionManager
import dev.flexaccess.flextunnel.tunnelcore.RuleException
import dev.flexaccess.flextunnel.tunnelcore.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The session-wide settings: the auth key, the SOCKS5 proxy, relays. A running session restarts on save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(manager: SessionManager, onKeys: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { manager.profiles.settings.value }
    val keys by manager.authKeys.keys.collectAsStateWithLifecycle()
    var keyId by rememberSaveable { mutableStateOf(saved.authKeyId) }
    var socksEnabled by rememberSaveable { mutableStateOf(saved.socksEnabled) }
    var socksPort by rememberSaveable { mutableStateOf(saved.socksPort.toString()) }
    var relays by rememberSaveable { mutableStateOf(saved.relayUrls.joinToString("\n")) }
    var token by rememberSaveable { mutableStateOf(manager.profiles.relayToken() ?: "") }
    var quicAddrDiscovery by rememberSaveable { mutableStateOf(saved.relayQuicAddrDiscovery) }
    var keyMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save() = scope.launch {
        val settings = try {
            saved.copy(
                authKeyId = keyId,
                socksEnabled = socksEnabled,
                socksPort = Rules.port(socksPort, "SOCKS5 port"),
                relayUrls = Rules.splitRules(relays),
                relayQuicAddrDiscovery = quicAddrDiscovery,
            )
        } catch (e: RuleException) {
            error = e.message
            return@launch
        }
        // The restart rides with the write: leaving the screen mid-save cancels
        // this coroutine, never a saved change's restart.
        val message = withContext(Dispatchers.IO) {
            manager.profiles.saveSettings(settings, token).also { if (it == null) manager.restartIfRunning() }
        }
        if (message != null) error = message else onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { save() }, modifier = Modifier.testTag("save")) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Auth key")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { keyMenu = true }, modifier = Modifier.fillMaxWidth().testTag("authKey")) {
                        Text(keys.firstOrNull { it.id == keyId }?.name ?: "Choose a key")
                    }
                    DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                        keys.forEach { key ->
                            DropdownMenuItem(text = { Text(key.name) }, onClick = { keyId = key.id; keyMenu = false })
                        }
                    }
                }
                TextButton(onClick = onKeys) { Text("Manage") }
            }
            Footnote("Every destination's server must list this key's public half in its authorized keys.")

            SectionTitle("SOCKS5 proxy")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("On", modifier = Modifier.weight(1f))
                Switch(checked = socksEnabled, onCheckedChange = { socksEnabled = it }, modifier = Modifier.testTag("socksEnabled"))
            }
            OutlinedTextField(
                value = socksPort,
                onValueChange = { socksPort = it.filter(Char::isDigit) },
                label = { Text("Port on 127.0.0.1") },
                singleLine = true,
                enabled = socksEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("socksPort"),
            )
            Footnote(
                "A fixed port, so a browser set up once keeps working. It has to be free when the session " +
                    "starts. Any app on this phone can use the proxy while the session is on.",
            )

            SectionTitle("Relays")
            OutlinedTextField(
                value = relays,
                onValueChange = { relays = it },
                label = { Text("Custom relay URLs, one per line (empty: defaults)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("relayUrls"),
            )
            Footnote("Two or more for failover; a single relay works, with nothing to fall back on.")
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Relay auth token (custom relays only)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("relayToken"),
            )
            // As save parses it: a field of separators only is the default relays.
            val customRelays = Rules.splitRules(relays).isNotEmpty()
            // The whole row toggles, so TalkBack names the switch by its label.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = quicAddrDiscovery,
                        enabled = customRelays,
                        role = Role.Switch,
                        onValueChange = { quicAddrDiscovery = it },
                    )
                    .testTag("relayQuicAddrDiscovery"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("QUIC address discovery", modifier = Modifier.weight(1f))
                Switch(checked = quicAddrDiscovery, onCheckedChange = null, enabled = customRelays)
            }
            Footnote(
                if (customRelays) {
                    "Whether the custom relays serve it, all of them or none. Turn it off for relays behind a " +
                        "Cloudflare Tunnel or another HTTP-only front, which otherwise delay each start by up to " +
                        "3 s. Set it the same on the server."
                } else {
                    "Custom relays only; the default relays keep their own."
                },
            )

            SectionTitle("Background")
            val power = context.getSystemService(PowerManager::class.java)
            val exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
            Footnote(
                if (exempt) {
                    "Battery optimization is off for Flextunnel."
                } else {
                    "The session runs as a foreground service, which stock Android keeps alive. Some phone " +
                        "makers stop background apps anyway; turning battery optimization off for Flextunnel prevents that."
                },
            )
            if (!exempt) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Battery optimization settings") }
            }
        }
    }
    ErrorDialog(error) { error = null }
}
