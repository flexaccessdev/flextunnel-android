# flextunnel-android

Android client for [flextunnel](https://github.com/flexaccessdev/flextunnel):
reach your own servers' private ports and networks from the phone, through a
SOCKS5-over-QUIC (iroh) tunnel. No VPN: other apps use it through

- a **SOCKS5 proxy** on a fixed loopback port (`127.0.0.1:1080` by default;
  can be turned off), and
- **port forwards**: local ports that each lead to one host and port.

Both run in one session, a foreground service with an ongoing notification, so
they keep serving while the app is in the background. Alpha; Android 14+
(minSdk 34), arm64-v8a and x86_64.

The iOS client is [flextunnel-ios](https://github.com/flexaccessdev/flextunnel-ios).
Unlike it, this app has no in-app browser.

## Setup

1. **Settings → Auth key → Manage**: generate a key (or paste one from another
   device) and choose it. Put its public key (`ed25519-pub:…`, copy from the key
   list) in each server's authorized keys file.
2. **Destinations → +**: a name (one DNS label, e.g. `machine1`) and the node id
   the server prints. `machine1.localhost.flextunnel.internal:<port>` then
   reaches that port on the server itself. Optional routes (domains, IP ranges)
   send more hosts through it; the server must route them too. *Fetch from
   server* fills in the server's own lists.
3. **Port forwards → +** (optional): e.g. local `2222` →
   `machine1.localhost.flextunnel.internal:22`, then SSH to `127.0.0.1:2222` from
   any app.
4. **Start**. Nothing connects until something is sent to a destination.

## Using the SOCKS5 proxy

Point a client at `127.0.0.1:<port>` (SOCKS5, no password) with names resolved
through the proxy (remote DNS / socks5h): `*.localhost.flextunnel.internal` and
the routed domains exist only on the far side. Hosts no destination routes
connect directly from the phone. Any app on the phone can use the proxy and the
forwards while the session is on. Step-by-step browser setup is on the
[roadmap](docs/roadmap.md).

## Build

Needs JDK 17 and the Android SDK (`local.properties`: `sdk.dir=…`).

```sh
./gradlew :tunnelcore:test :app:testDebugUnitTest assembleDebug
```

The core, `libflextunnel.so`, comes from the pinned flextunnel release in
`gradle.properties` (`flextunnel.releaseTag` + `releaseSha256`; repin with
`scripts/bump-jnilibs.sh <tag>`). For local core work, build it in the sibling
`../flextunnel` and set `FLEXTUNNEL_LOCAL_JNILIBS=1`. Each ABI is built on a host
with its own CPU — arm64-v8a on macvm, x86_64 on an x86 machine — and
`scripts/run-device.sh` does that for the device's ABI (`CORE_BUILD_HOSTS` in
`.devtools.conf`), then installs, launches and tails logcat:

```sh
export ANDROID_ADB_SERVER_PORT=15037 ADB_SERIAL=emulator-5554   # the MBP's emulator, through FlexTunnel
scripts/run-device.sh
scripts/e2e.sh        # end to end against a throwaway server (docs/e2e.md)
```

Release builds: `scripts/build-release-apk.sh` (signed APK, `--bundle` for an AAB).

## Layout

- `tunnelcore/` — pure Kotlin/JVM: the destination, forward and settings model,
  the core's validation rules, the FFI JSON and snapshot decoding. Unit-tested
  on the JVM.
- `app/` — `FlextunnelNative` (the JNI surface of ../flextunnel's
  `crates/flextunnel-ffi/src/android.rs`), `TunnelService` (the session's
  foreground service), the stores (`ProfileStore`, `AuthKeyStore`, secrets
  sealed under an AndroidKeyStore key), and the Compose UI.

## License

MIT
