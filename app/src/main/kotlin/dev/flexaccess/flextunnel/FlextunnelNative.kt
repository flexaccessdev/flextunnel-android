package dev.flexaccess.flextunnel

import android.content.Context

/**
 * The JNI surface of libflextunnel.so (../flextunnel's
 * `crates/flextunnel-ffi/src/android.rs`). The Rust symbol names are bound to
 * exactly this class, so it must stay `dev.flexaccess.flextunnel.FlextunnelNative`
 * whatever the applicationId is.
 *
 * Failures throw [IllegalStateException] with the core's message. JSON shapes
 * are the ones flextunnel's `ios/flextunnel.h` documents. One session at a
 * time: [start] returns its handle, [stop] ends it — exactly once, and never
 * while another call on the handle is in flight ([TunnelService] makes every
 * call from its one session thread).
 */
object FlextunnelNative {
    init {
        System.loadLibrary("flextunnel")
    }

    /**
     * One-time process setup: logcat logging (tag `flextunnel`) and the
     * JVM/context registration iroh's Android DNS and interface discovery
     * need. Call from `Application.onCreate` before anything else. Idempotent.
     */
    @JvmStatic external fun init(context: Context)

    /** `{"created":…,"public_key":"ed25519-pub:…","secret_key":"ed25519-sec:…"}`. */
    @JvmStatic external fun generateClientKey(): String

    /** The `ed25519-pub:…` half of a secret key; throws for one that doesn't parse. */
    @JvmStatic external fun clientPublicKey(secret: String): String

    /**
     * Validate the config, bind the SOCKS5 listener if `socks_port` is set (a
     * fixed port: the start fails if it is taken) and start serving. Connects
     * nothing yet: a destination connects on first use.
     */
    @JvmStatic external fun start(configJson: String): Long

    @JvmStatic external fun stop(handle: Long)

    /** Replace the whole forward set; throws for invalid input. */
    @JvmStatic external fun setForwards(handle: Long, forwardsJson: String)

    @JvmStatic external fun forwardStatuses(handle: Long): String

    @JvmStatic external fun status(handle: Long): String

    /** Blocks on the custom relays' health checks: call it off the main thread. */
    @JvmStatic external fun connPath(handle: Long, name: String): String

    /** False once the client has ended (a local listener lost for good). */
    @JvmStatic external fun isServing(handle: Long): Boolean

    @JvmStatic external fun setBackground(handle: Long, background: Boolean)

    @JvmStatic external fun setNetworkAvailable(handle: Long, available: Boolean)

    /** What a server routes, outside any session. Blocks for seconds: off the main thread. */
    @JvmStatic external fun fetchServerRoutes(requestJson: String): String
}
