# End-to-end test

`scripts/e2e.sh` runs the app's session for real: on the development emulator,
against a flextunnel server built from the sibling `../flextunnel` working tree.

| Piece | Where |
| --- | --- |
| The app | the MBP's emulator, `emulator-5554`, through the MBP's adb server (`ANDROID_ADB_SERVER_PORT=15037`, see `../devtools/claude/android.md`) |
| The server | `debian-vm` (linux/arm64, a throwaway VM), transient systemd unit `flextunnel-e2e`, with a fresh identity and client key per run |
| The target | `python -m http.server` on debian-vm's loopback, unit `flextunnel-e2e-http` |

The test (`app/src/androidTest/…/E2eTest.kt`) sets up one destination `e2e`
for that server, turns the SOCKS5 proxy on at `127.0.0.1:1080`, adds a forward
`18080 → e2e.localhost.flextunnel.internal:<port>`, starts the session, and
fetches the page both ways — through the forward, and with a raw SOCKS5
CONNECT by name. A second test holds port 1080 itself and checks that the start
fails. The emulator's networking is relay-only, so the path is always a relay.

```sh
export ANDROID_ADB_SERVER_PORT=15037 ADB_SERIAL=emulator-5554
scripts/run-device.sh --no-log   # the local core build, for the device's ABI
scripts/e2e.sh                   # --no-build reuses debian-vm's last binary
```

The server is built on debian-vm by `../flextunnel/ci/unix/remote.sh -H debian-vm
run cargo build --release -p flextunnel-cli`, whose cargo target dir comes from
the restic build cache. The script stops both units when it ends, whatever
happened; `flextunnel-ssh-server`, which serves `ssh debian-vm`, is left alone.
Without the arguments the script passes, the instrumented tests skip, so a plain
`./gradlew connectedAndroidTest` passes without a server.
