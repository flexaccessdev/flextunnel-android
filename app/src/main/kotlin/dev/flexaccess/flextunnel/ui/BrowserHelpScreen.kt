package dev.flexaccess.flextunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flexaccess.flextunnel.SessionManager

/**
 * How to point a browser (or another app) at the session. The same content
 * as the repo's docs/socks5-browsers.md — keep the two in step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserHelpScreen(manager: SessionManager, onBack: () -> Unit) {
    val settings by manager.profiles.settings.collectAsStateWithLifecycle()
    val port = settings.socksPort
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Using the proxy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BrowserHelp.sections(port, settings.socksEnabled).forEach { section ->
                SectionTitle(section.title)
                section.lines.forEach { line ->
                    if (line.startsWith("`") && line.endsWith("`")) {
                        Text(line.trim('`'), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** The help text, as data: a line in backticks renders monospaced. */
object BrowserHelp {
    data class Section(val title: String, val lines: List<String>)

    fun sections(port: Int, socksEnabled: Boolean): List<Section> = buildList {
        add(
            Section(
                "The proxy",
                listOfNotNull(
                    "While the session is on, a SOCKS5 proxy (no password) listens on this phone at:",
                    "`127.0.0.1:$port`",
                    if (!socksEnabled) "It is off now: turn it on in Settings." else null,
                    "Set the proxy to resolve names through it (\"remote DNS\", \"proxy DNS\", socks5h). " +
                        "Names like <name>.localhost.flextunnel.internal and your destinations' routed " +
                        "domains only exist on the far side; looked up on the phone they fail.",
                    "Hosts no destination routes connect directly from the phone, as without the proxy.",
                ),
            ),
        )
        add(
            Section(
                "Other apps",
                listOf(
                    "Apps without a proxy setting can use a port forward instead: a local port that leads to one host " +
                        "and port. For example, a forward 2222 → <name>.localhost.flextunnel.internal:22 lets an SSH " +
                        "app connect to 127.0.0.1 port 2222.",
                ),
            ),
        )
        add(
            Section(
                "Good to know",
                listOf(
                    "Any app on this phone can use the proxy and the forwards while the session is on — they " +
                        "have no password. Stop the session when you don't need it.",
                    "The port is fixed so a browser configured once keeps working. If another app holds it, the " +
                        "session won't start: pick another port in Settings and update the browser.",
                ),
            ),
        )
    }
}
