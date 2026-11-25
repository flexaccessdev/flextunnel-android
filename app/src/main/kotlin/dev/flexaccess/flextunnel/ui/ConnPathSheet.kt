package dev.flexaccess.flextunnel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.flexaccess.flextunnel.SessionManager
import dev.flexaccess.flextunnel.tunnelcore.ConnPathSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How the session reaches one destination right now: its iroh paths and custom relays' health. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnPathSheet(manager: SessionManager, name: String, onDismiss: () -> Unit) {
    var snapshot by remember { mutableStateOf<ConnPathSnapshot?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(name) {
        snapshot = withContext(Dispatchers.IO) { manager.connPath(name) }
        loaded = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Connection path · $name", style = MaterialTheme.typography.titleMedium)
            val s = snapshot
            when {
                !loaded -> Text("Checking…")
                s == null || s.paths.isEmpty() -> Text("Not connected.")
                else -> {
                    s.paths.forEach { p ->
                        Text(
                            (if (p.selected) "▶ " else "   ") + p.display,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (p.selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    Footnote("UDP datagrams sent ${s.udpTxDatagrams} · received ${s.udpRxDatagrams}")
                }
            }
            s?.customRelays?.takeIf { it.isNotEmpty() }?.let { relays ->
                SectionTitle("Custom relays")
                relays.forEach { r ->
                    val state = when (r.working) {
                        true -> "up"
                        false -> "down" + (r.error?.let { ": $it" } ?: "")
                        null -> "unchecked"
                    }
                    Text("${r.url} — $state", style = MaterialTheme.typography.bodySmall)
                }
            }
            Footnote(" ")
        }
    }
}
