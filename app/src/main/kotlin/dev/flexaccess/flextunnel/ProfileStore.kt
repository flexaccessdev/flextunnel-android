package dev.flexaccess.flextunnel

import android.content.Context
import dev.flexaccess.flextunnel.tunnelcore.Destination
import dev.flexaccess.flextunnel.tunnelcore.PortForward
import dev.flexaccess.flextunnel.tunnelcore.RuleException
import dev.flexaccess.flextunnel.tunnelcore.Rules
import dev.flexaccess.flextunnel.tunnelcore.SessionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the session is made of: the destinations, the port forwards and the
 * session settings, as JSON in a private `SharedPreferences` file — none of it
 * secret. The relay token is, so it lives in the [SecretStore]; the auth key is
 * a reference into [AuthKeyStore].
 *
 * Every change is validated against the rest ([Rules]) and written
 * synchronously; a method returns a user-facing error message, or null once
 * the change is saved.
 */
class ProfileStore(context: Context, private val secrets: SecretStore) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _destinations = MutableStateFlow(readList(KEY_DESTINATIONS) { Destination.fromJson(it) })
    val destinations: StateFlow<List<Destination>> = _destinations.asStateFlow()

    private val _forwards = MutableStateFlow(readList(KEY_FORWARDS) { PortForward.fromJson(it) })
    val forwards: StateFlow<List<PortForward>> = _forwards.asStateFlow()

    private val _settings = MutableStateFlow(
        prefs.getString(KEY_SETTINGS, null)
            ?.let { runCatching { SessionSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: SessionSettings(),
    )
    val settings: StateFlow<SessionSettings> = _settings.asStateFlow()

    fun relayToken(): String? = runCatching { secrets.get(SECRET_RELAY_TOKEN) }.getOrNull()

    @Synchronized
    fun saveDestination(destination: Destination): String? {
        val others = _destinations.value.filter { it.id != destination.id }
        val valid = try {
            Rules.destination(destination, others)
        } catch (e: RuleException) {
            return e.message
        }
        val previous = _destinations.value.firstOrNull { it.id == destination.id }
        val next = if (previous == null) {
            _destinations.value + valid
        } else {
            _destinations.value.map { if (it.id == valid.id) valid else it }
        }
        // A rename or a route change must not strand a forward that used it.
        stranded(next)?.let { return it }
        return writeDestinations(next)
    }

    @Synchronized
    fun deleteDestination(id: String): String? {
        val next = _destinations.value.filter { it.id != id }
        stranded(next)?.let { return it }
        return writeDestinations(next)
    }

    /** The forward that no destination in [destinations] would route any more, as a message. */
    private fun stranded(destinations: List<Destination>): String? {
        val forward = _forwards.value.firstOrNull { !Rules.routes(it.remoteHost, destinations) } ?: return null
        return "Forward ${forward.label.ifEmpty { "to ${forward.remoteHost}" }} goes through it: " +
            "change or delete that forward first."
    }

    @Synchronized
    fun saveForward(forward: PortForward): String? {
        val others = _forwards.value.filter { it.id != forward.id }
        val valid = try {
            Rules.forward(forward, others, _settings.value, _destinations.value)
        } catch (e: RuleException) {
            return e.message
        }
        val next = if (_forwards.value.any { it.id == forward.id }) {
            _forwards.value.map { if (it.id == valid.id) valid else it }
        } else {
            _forwards.value + valid
        }
        return writeForwards(next)
    }

    @Synchronized
    fun setForwardEnabled(id: String, enabled: Boolean): String? {
        val forward = _forwards.value.firstOrNull { it.id == id } ?: return null
        return saveForward(forward.copy(enabled = enabled))
    }

    @Synchronized
    fun deleteForward(id: String): String? = writeForwards(_forwards.value.filter { it.id != id })

    /** Save the session settings and the relay token (empty or null clears it). */
    @Synchronized
    fun saveSettings(settings: SessionSettings, relayToken: String?): String? {
        val valid = try {
            Rules.settings(settings, _forwards.value)
        } catch (e: RuleException) {
            return e.message
        }
        if (!relayToken.isNullOrBlank() && valid.relayUrls.isEmpty()) {
            return "A relay token is only for custom relays: add a relay URL, or clear the token."
        }
        try {
            if (relayToken.isNullOrBlank()) secrets.remove(SECRET_RELAY_TOKEN) else secrets.put(SECRET_RELAY_TOKEN, relayToken.trim())
        } catch (e: SecretStoreException) {
            return "Couldn't save the relay token: ${e.message}"
        }
        return writeSettings(valid)
    }

    /** Select the auth key the session uses; the relay token stays as stored. */
    @Synchronized
    fun selectAuthKey(id: String?): String? {
        val valid = try {
            Rules.settings(_settings.value.copy(authKeyId = id), _forwards.value)
        } catch (e: RuleException) {
            return e.message
        }
        return writeSettings(valid)
    }

    private fun writeSettings(valid: SessionSettings): String? {
        if (!prefs.edit().putString(KEY_SETTINGS, valid.toJson().toString()).commit()) {
            return "Couldn't save the settings."
        }
        _settings.value = valid
        return null
    }

    private fun writeDestinations(next: List<Destination>): String? {
        if (!prefs.edit().putString(KEY_DESTINATIONS, JSONArray(next.map { it.toJson() }).toString()).commit()) {
            return "Couldn't save the destinations."
        }
        _destinations.value = next
        return null
    }

    private fun writeForwards(next: List<PortForward>): String? {
        if (!prefs.edit().putString(KEY_FORWARDS, JSONArray(next.map { it.toJson() }).toString()).commit()) {
            return "Couldn't save the forwards."
        }
        _forwards.value = next
        return null
    }

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> {
        val json = prefs.getString(key, null) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { runCatching { parse(it) }.getOrNull() }
        }
    }

    private companion object {
        const val PREFS = "flextunnel-profiles"
        const val KEY_DESTINATIONS = "destinations"
        const val KEY_FORWARDS = "forwards"
        const val KEY_SETTINGS = "settings"
        const val SECRET_RELAY_TOKEN = "relay-token"
    }
}
