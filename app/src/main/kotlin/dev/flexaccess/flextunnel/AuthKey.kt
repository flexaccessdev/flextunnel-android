package dev.flexaccess.flextunnel

import org.json.JSONObject

/**
 * Client auth keypair primitives, via the native library. A secret key
 * ("ed25519-sec:…") authenticates this device to a server; its public key
 * ("ed25519-pub:…") is not a secret — it goes on the server's authorized_keys
 * file, and it's re-derived from the secret whenever needed rather than
 * stored. Keys are never generated or parsed in Kotlin: the shared FlexAccess
 * key format is owned by the Rust side.
 */
object AuthKey {
    data class Keypair(val secretKey: String, val publicKey: String)

    /** A fresh ed25519 keypair; null only if the system RNG failed. */
    fun generate(): Keypair? {
        val obj = runCatching { JSONObject(FlextunnelNative.generateClientKey()) }.getOrNull() ?: return null
        val secret = obj.optString("secret_key", "")
        val public = obj.optString("public_key", "")
        if (secret.isEmpty() || public.isEmpty()) return null
        return Keypair(secret, public)
    }

    /** The public key of `secret`, or null when it isn't a valid secret key. */
    fun publicKey(secret: String): String? {
        if (secret.isEmpty()) return null
        return runCatching { FlextunnelNative.clientPublicKey(secret) }.getOrNull()
    }
}
