# End-to-end tests

`scripts/e2e.sh` runs the app's session for real: on the development emulator,
against throwaway flextunnel servers on the machine that runs the script. A full
run takes about two and a half minutes.

```sh
export ANDROID_ADB_SERVER_PORT=15037 ADB_SERIAL=emulator-5554
scripts/e2e.sh                   # every test, against the pinned release

# One class or test: extra arguments go to gradle.
scripts/e2e.sh -Pandroid.testInstrumentationRunnerArguments.class=dev.flexaccess.flextunnel.SocksE2eTest

# Against a core change in ../flextunnel:
scripts/run-device.sh --no-log   # the local core build, for the device's ABI
FLEXTUNNEL_LOCAL_JNILIBS=1 scripts/e2e.sh
```

## What runs where

| Piece | Where |
| --- | --- |
| The app and the tests | the MBP's emulator, `emulator-5554`, through the MBP's adb server (`ANDROID_ADB_SERVER_PORT=15037`, see `../devtools/claude/android.md`) |
| Three servers | this machine, as plain processes: the release binary of the pinned core tag (`flextunnel.releaseTag` in `gradle.properties`) for its OS and CPU, a fresh identity each and one fresh client key |
| The fixture site | `python -m http.server` on this machine's loopback, serving `scripts/e2e/fixtures` |
| The control endpoint | `scripts/e2e/control.py` on this machine's loopback: stops and restarts two of the servers, and serves their logs |

The servers and the app speak one protocol version and keep no backward
compatibility, so the servers are the release of the tag the app's
libflextunnel comes from: `flextunnel-<os>-<arch>` (linux-amd64, linux-arm64 or
macos-arm64), and `flexaccess-keys` at the tag that release's `Cargo.toml`
names. Each is downloaded once per tag into `build/e2e/releases` and checked
against the sha256 digest GitHub reports for the asset. With
`FLEXTUNNEL_LOCAL_JNILIBS=1` the app links the local core build, so the servers
are built from the sibling `../flextunnel` working tree instead (into
`build/e2e/tools`, which needs cargo).

The emulator finds the servers through iroh's n0 discovery and default relays,
as a real client would, so they need nothing but internet access: no VM, and
nothing on the MBP but the emulator. The device cannot see this machine's
loopback; everything there is reached through the tunnel, as a server's own
loopback.

| Server | Routes | Role |
| --- | --- | --- |
| `site` | `e2e.flextunnel.test` (a host alias for `127.0.0.1`), `*.e2e.flextunnel.test`, `localhost` | A name only it resolves: a page that loads came through it. `localhost` is answered on the device too, so a fallback would show. Can be stopped. |
| `plain` | nothing | Reached by its loopback name `plain.localhost.flextunnel.internal`. Never stopped: the tests reach the control endpoint through it. |
| `cidr` | `203.0.113.0/24` (TEST-NET-3) | Logs each stream's target, so a test can see an address in the range arrive. Can be stopped. |

## The tests

They use the app as another app on the phone would — through its SOCKS5
proxy on `127.0.0.1:1080` and its port forwards — and its screens. Each test
sets up the destinations it needs (`E2e.reset`) and starts its own session.
Without the arguments the script passes, every test skips, so a plain
`./gradlew connectedAndroidTest` passes without servers.

`SocksE2eTest`, the SOCKS5 proxy (by name, as `socks5h://` sends it):
- a destination connects on first use, not at start; its loopback name is its
  server's loopback, whatever that server routes; two destinations connect side
  by side;
- `flextunnel.internal` is the client's status, answered without connecting
  anything; `flextunnel.internal/servers/<name>/` is fetched from that server;
- a routed host never falls back to a direct connection while its server is
  down (the device answers `localhost` on the site's port too, and is never
  reached), a host on no route keeps going direct, and the host is reached
  again once its server is back;
- an address in a destination's network is held for it while its server is
  down and arrives in the server's log once it is back; hosts on no route stay
  direct;
- a name on no route that nothing resolves is refused;
- a session started again right after a stop serves.

`SessionE2eTest`, the rest of the session:
- forwards go through the destination that routes their host (a loopback name,
  or a name on its routes); switching one off, or adding one, applies to the
  running session in place;
- a forward whose port is held on both loopbacks fails alone (`port N is in
  use`) while the rest serve;
- a settings change restarts a running session (the proxy moves port);
- a taken SOCKS5 port fails the start;
- a server's routes are fetched with no session;
- custom relays, with QUIC address discovery off or on, reach the session.

`ScreensE2eTest`, the screens (Compose test rule):
- the destination editor refuses a name already taken, then saves;
- "Fetch from server" fills a new destination's routes from its server;
- the forward editor refuses a host no destination routes; a forward to a
  loopback name serves once Start is tapped, the rows showing it listening and
  the destination connected; Stop ends the session;
- the QUIC address discovery switch is off-limits without custom relays, turns
  off with one, and reaches the session.

## When a test fails

The script prints the servers' log tails. The whole run — server, site and
control logs, configs — stays in `build/e2e/run`, and gradle's report is at
`app/build/reports/androidTests/connected/debug/index.html`.
