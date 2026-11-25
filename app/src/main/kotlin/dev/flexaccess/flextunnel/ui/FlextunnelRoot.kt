package dev.flexaccess.flextunnel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flexaccess.flextunnel.SessionManager

/** The app's screens; kept as plain state so no navigation library is needed. */
sealed interface Screen {
    data object Home : Screen
    data class EditDestination(val id: String?) : Screen
    data class EditForward(val id: String?) : Screen
    data object Settings : Screen
    data object Keys : Screen
    data object BrowserHelp : Screen

    companion object {
        fun save(s: Screen): String = when (s) {
            Home -> "home"
            is EditDestination -> "dest:${s.id ?: ""}"
            is EditForward -> "fwd:${s.id ?: ""}"
            Settings -> "settings"
            Keys -> "keys"
            BrowserHelp -> "browser"
        }

        fun restore(s: String): Screen = when {
            s.startsWith("dest:") -> EditDestination(s.removePrefix("dest:").ifEmpty { null })
            s.startsWith("fwd:") -> EditForward(s.removePrefix("fwd:").ifEmpty { null })
            s == "settings" -> Settings
            s == "keys" -> Keys
            s == "browser" -> BrowserHelp
            else -> Home
        }
    }
}

private val stackSaver: Saver<List<Screen>, Any> = Saver(
    save = { list -> ArrayList(list.map { Screen.save(it) }) },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        (saved as List<String>).map { Screen.restore(it) }.ifEmpty { listOf(Screen.Home) }
    },
)

@Composable
fun FlextunnelRoot(manager: SessionManager, onStart: () -> Unit) {
    var stack by rememberSaveable(stateSaver = stackSaver) { mutableStateOf(listOf<Screen>(Screen.Home)) }
    // Each back-stack entry keeps its saved state (a Settings draft while Keys
    // is open) until it is popped; a later visit then starts from the stores.
    val states = rememberSaveableStateHolder()
    fun entryKey(index: Int) = "$index:${Screen.save(stack[index])}"
    fun push(s: Screen) { stack = stack + s }
    fun pop() {
        if (stack.size > 1) {
            states.removeState(entryKey(stack.lastIndex))
            stack = stack.dropLast(1)
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    states.SaveableStateProvider(entryKey(stack.lastIndex)) { Content(manager, stack.last(), onStart, ::push, ::pop) }
}

@Composable
private fun Content(manager: SessionManager, screen: Screen, onStart: () -> Unit, push: (Screen) -> Unit, pop: () -> Unit) {
    when (screen) {
        Screen.Home -> HomeScreen(
            manager = manager,
            onStart = onStart,
            onEditDestination = { push(Screen.EditDestination(it)) },
            onEditForward = { push(Screen.EditForward(it)) },
            onSettings = { push(Screen.Settings) },
            onBrowserHelp = { push(Screen.BrowserHelp) },
        )
        is Screen.EditDestination -> DestinationEditScreen(manager, screen.id, onDone = { pop() })
        is Screen.EditForward -> ForwardEditScreen(manager, screen.id, onDone = { pop() })
        Screen.Settings -> SettingsScreen(manager, onKeys = { push(Screen.Keys) }, onDone = { pop() })
        Screen.Keys -> {
            val keys by manager.authKeys.keys.collectAsStateWithLifecycle()
            KeysScreen(keys = keys, store = manager.authKeys, onBack = { pop() })
        }
        Screen.BrowserHelp -> BrowserHelpScreen(manager, onBack = { pop() })
    }
}
