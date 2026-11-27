# End-to-end test

`scripts/e2e.sh` runs the app's session for real: on the development emulator,
against a throwaway flextunnel server running on the machine that runs the
script.

| Piece | Where |
| --- | --- |
| The app | the MBP's emulator, `emulator-5554`, through the MBP's adb server (`ANDROID_ADB_SERVER_PORT=15037`, see `../devtools/claude/android.md`) |
| The server | this machine: the release binary of the pinned core tag (`flextunnel.releaseTag` in `gradle.properties`) for its OS and CPU, with a fresh identity and client key per run |
| The target | `python -m http.server` on this machine's loopback |

The server and the app speak one protocol version and keep no backward
compatibility, so the server is the release of the tag the app's
libflextunnel comes from: `flextunnel-<os>-<arch>` (linux-amd64, linux-arm64 or
macos-arm64), and `flexaccess-keys` at the tag that release's `Cargo.toml`
names. Each is downloaded once per tag into `build/e2e/releases` and checked
against the sha256 digest GitHub reports for the asset. With
`FLEXTUNNEL_LOCAL_JNILIBS=1` the app links the local core build, so the server
is built from the sibling `../flextunnel` working tree instead (into
`build/e2e/tools`, which needs cargo).

The emulator finds the server through iroh's n0 discovery and default relays,
as a real client would, so the server needs nothing but internet access: no VM,
and nothing on the MBP but the emulator.

The test (`app/src/androidTest/…/E2eTest.kt`) sets up one destination `e2e`
for that server, turns the SOCKS5 proxy on at `127.0.0.1:1080`, adds a forward
`18080 → e2e.localhost.flextunnel.internal:<port>`, starts the session, and
fetches the page both ways — through the forward, and with a raw SOCKS5
CONNECT by name. A second test holds port 1080 itself and checks that the start
fails. The path is whatever iroh finds: from the emulator that is usually direct,
through the router's public address, with a relay as the fallback.

```sh
export ANDROID_ADB_SERVER_PORT=15037 ADB_SERIAL=emulator-5554
scripts/e2e.sh                   # the pinned release: app and server

# Against a core change in ../flextunnel:
scripts/run-device.sh --no-log   # the local core build, for the device's ABI
FLEXTUNNEL_LOCAL_JNILIBS=1 scripts/e2e.sh
```

The script stops the server and the HTTP server when it ends, whatever
happened, and prints the server log's tail on a failure; the run's files stay
in `build/e2e/run`. Without the arguments the script passes, the instrumented
tests skip, so a plain `./gradlew connectedAndroidTest` passes without a server.
