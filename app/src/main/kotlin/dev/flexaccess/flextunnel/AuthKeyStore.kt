package dev.flexaccess.flextunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The app's named client auth keys — the same model the desktop and iOS apps
 * use: one list of keypairs, of which the session settings select one by id
 * ([dev.flexaccess.flextunnel.tunnelcore.SessionSettings.authKeyId]) to
 * authenticate to every destination.
 *
 * The whole list persists as one JSON document in the [SecretStore] (names
 * ride along with the secrets; public halves are never stored — each is
 * re-derived via the FFI on load).
 */
class AuthKeyStore(private val secrets: SecretStore) {
    /** One named keypair. `publicKey` is derived, not persisted. */
    data class Key(val id: String, val name: String, val secret: String, val publicKey: String)

    private val _keys = MutableStateFlow<List<Key>>(emptyList())
    val keys: StateFlow<List<Key>> = _keys.asStateFlow()

    /**
     * Why the stored list couldn't be read, or null once it was (an absent
     * entry counts: a fresh install genuinely has no keys). While set, every
     * write is refused — persisting would replace the real list with this
     * partial view.
     */
    private var loadError: String? = null

    init {
        val json = try {
            secrets.get(KEY_LIST)
        } catch (e: SecretStoreException) {
            loadError = "Couldn't read the key list: ${e.message}. Keys can't be changed until it can be read."
            null
        }
        if (loadError == null && json != null) {
            val parsed = runCatching { JSONArray(json) }.getOrNull()
            if (parsed == null) {
                // Undecodable JSON is a load failure too, never an empty list.
                loadError = "The stored key list couldn't be decoded. Keys can't be changed until it can be read."
            } else {
                val stored = (0 until parsed.length()).mapNotNull { parsed.optJSONObject(it) }
                // A record whose secret no longer derives a public key is corrupt —
                // drop it from the view rather than carry an entry that can never
                // connect. Nothing is written back here: the derivation goes
                // through the native library, and a transient failure there must
                // not delete stored keys. The pruned list lands on disk with the
                // next user-driven add/rename/delete.
                _keys.value = stored.mapNotNull { obj ->
                    val secret = obj.optString("secret", "")
                    AuthKey.publicKey(secret)?.let {
                        Key(obj.optString("id"), obj.optString("name", "Unnamed"), secret, it)
                    }
                }
            }
        }
    }

    fun key(id: String): Key? = _keys.value.firstOrNull { it.id == id }

    /**
     * Validate and add a key: the name is non-empty and unique (ignoring case)
     * and the secret must parse. The same keypair twice under two names is an
     * accidental re-add, not a use case. Returns the key, or an error message.
     */
    @Synchronized
    fun add(rawName: String, rawSecret: String): Result<Key> {
        val name = when (val r = validated(rawName, excluding = null)) {
            is Validated.Ok -> r.name
            is Validated.Err -> return Result.failure(AuthKeyStoreException(r.message))
        }
        val secret = rawSecret.trim()
        val publicKey = AuthKey.publicKey(secret)
            ?: return Result.failure(AuthKeyStoreException("Not a valid secret key (expected ed25519-sec:…)."))
        _keys.value.firstOrNull { it.publicKey == publicKey }?.let {
            return Result.failure(AuthKeyStoreException("Key \"${it.name}\" already holds this secret."))
        }
        val key = Key(UUID.randomUUID().toString(), name, secret, publicKey)
        val previous = _keys.value
        _keys.value = previous + key
        persist()?.let {
            _keys.value = previous
            return Result.failure(AuthKeyStoreException(it))
        }
        return Result.success(key)
    }

    /** Rename `id`; returns a user-facing error message, or null on success. */
    @Synchronized
    fun rename(id: String, newName: String): String? {
        val previous = _keys.value
        val index = previous.indexOfFirst { it.id == id }
        if (index < 0) return "That key is no longer in the list."
        val name = when (val r = validated(newName, excluding = id)) {
            is Validated.Ok -> r.name
            is Validated.Err -> return r.message
        }
        _keys.value = previous.toMutableList().also { it[index] = it[index].copy(name = name) }
        persist()?.let {
            _keys.value = previous
            return it
        }
        return null
    }

    /**
     * Delete `id`; returns an error message when the removal couldn't be written
     * back. A session already running keeps its key until it stops.
     */
    @Synchronized
    fun delete(id: String): String? {
        val previous = _keys.value
        if (previous.none { it.id == id }) return null
        _keys.value = previous.filter { it.id != id }
        persist()?.let {
            _keys.value = previous
            return it
        }
        return null
    }

    private sealed class Validated {
        class Ok(val name: String) : Validated()
        class Err(val message: String) : Validated()
    }

    private fun validated(raw: String, excluding: String?): Validated {
        val name = raw.trim()
        if (name.isEmpty()) return Validated.Err("Key name is required.")
        val taken = _keys.value.any { it.id != excluding && it.name.equals(name, ignoreCase = true) }
        return if (taken) Validated.Err("Another key is already named that.") else Validated.Ok(name)
    }

    /** Write the whole list back; returns a user-facing error message on failure. */
    private fun persist(): String? {
        loadError?.let { return it }
        val array = JSONArray()
        _keys.value.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("secret", it.secret))
        }
        return try {
            secrets.put(KEY_LIST, array.toString())
            null
        } catch (e: SecretStoreException) {
            "Couldn't save the key list: ${e.message}"
        }
    }

    private companion object {
        const val KEY_LIST = "auth-keys"
    }
}

class AuthKeyStoreException(message: String) : Exception(message)
