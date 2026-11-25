#!/usr/bin/env bash
#
# End-to-end test: the app on the development emulator, against a throwaway
# flextunnel server on debian-vm (linux/arm64), built from the sibling
# ../flextunnel working tree. See docs/e2e.md.
#
#   1. ../flextunnel's ci/unix/remote.sh builds flextunnel-cli on debian-vm
#      (its cargo target dir comes from the restic build cache);
#   2. a fresh server identity and client key are made for this run, and the
#      server runs there as the transient systemd unit flextunnel-e2e, beside
#      an HTTP server on its loopback (flextunnel-e2e-http) — the
#      flextunnel-ssh-server service already on the VM is left alone;
#   3. the instrumented test (app/src/androidTest/…/E2eTest.kt) installs and
#      runs on the device, given the server's node id, the client key and the
#      HTTP port;
#   4. both units stop, whatever happened.
#
# The device is ADB_SERIAL (default emulator-5554) on the adb server
# ANDROID_ADB_SERVER_PORT points at (the MBP's, through FlexTunnel: 15037).
# The app links the local core build: run scripts/run-device.sh (or
# ../flextunnel/build-android.sh on a matching host) first.
#
# Usage: scripts/e2e.sh [--no-build]   # --no-build: reuse debian-vm's last binary
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
HOST="${E2E_HOST:-debian-vm}"
TARGET_PORT="${E2E_TARGET_PORT:-18765}"
export ANDROID_SERIAL="${ADB_SERIAL:-emulator-5554}"
BUILD=1
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

if [ "$BUILD" = 1 ]; then
  echo "== building flextunnel-cli on $HOST"
  (cd ../flextunnel && ci/unix/remote.sh -H "$HOST" run cargo build --release -p flextunnel-cli)
fi

key_json="$(flexaccess-keys generate-auth-key --json flextunnel-android-e2e)"
client_secret="$(printf '%s' "$key_json" | sed -n 's/.*"private_key":"\([^"]*\)".*/\1/p')"
client_public="$(printf '%s' "$key_json" | sed -n 's/.*"authorized_key":"\([^"]*\)".*/\1/p')"
[ -n "$client_secret" ] && [ -n "$client_public" ] || { echo "could not generate a client key" >&2; exit 1; }

cleanup() {
  # http.server exits on SIGTERM with an error status: reset-failed, so no unit lingers.
  ssh "$HOST" 'sudo systemctl stop flextunnel-e2e flextunnel-e2e-http 2>/dev/null; sudo systemctl reset-failed flextunnel-e2e flextunnel-e2e-http 2>/dev/null; true' || true
}
trap cleanup EXIT

echo "== starting the e2e server on $HOST"
# ssh joins its arguments into one remote command line: quote them for it.
node_id="$(ssh "$HOST" "bash -s -- $(printf '%q ' "$client_public" "$TARGET_PORT")" <<'REMOTE'
set -euo pipefail
public=$1 port=$2
D="$HOME/codes/staging-area/flextunnel-android-e2e"
sudo systemctl stop flextunnel-e2e flextunnel-e2e-http 2>/dev/null || true
sudo systemctl reset-failed flextunnel-e2e flextunnel-e2e-http 2>/dev/null || true
rm -rf "$D"; mkdir -p "$D/www"; chmod 700 "$D"
cp "$HOME/codes/staging-area/flextunnel-cargo-target/release/flextunnel" "$D/"
echo "flextunnel-android e2e" > "$D/www/index.html"
"$D/flextunnel" generate-iroh-key -o "$D/server.key" --json 2>/dev/null |
  sed -n 's/.*"endpoint_id":"\([^"]*\)".*/\1/p' > "$D/endpoint_id"
printf '%s\n' "$public" > "$D/authorized_keys"
printf 'secret_file = "%s"\nauthorized_keys_file = "%s"\n' "$D/server.key" "$D/authorized_keys" > "$D/server.toml"
sudo systemd-run --quiet --unit flextunnel-e2e --uid "$(id -u)" --gid "$(id -g)" \
  --working-directory "$D" "$D/flextunnel" server start -c "$D/server.toml"
sudo systemd-run --quiet --unit flextunnel-e2e-http --uid "$(id -u)" --gid "$(id -g)" \
  --working-directory "$D" "$HOME/.local/bin/uv" run --no-project --python 3.13 \
  python -m http.server "$port" --bind 127.0.0.1 --directory "$D/www"
# Ready once the server prints its id and the HTTP server answers.
for _ in $(seq 60); do
  if sudo journalctl -u flextunnel-e2e --no-pager -o cat | grep "Node ID" >/dev/null &&
     curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$port/"; then
    cat "$D/endpoint_id"; exit 0
  fi
  sleep 1
done
echo 'the e2e server did not come up:' >&2
sudo journalctl -u flextunnel-e2e -u flextunnel-e2e-http --no-pager -n 30 >&2
exit 1
REMOTE
)"
echo "   server node id $node_id"

echo "== running the instrumented test on $ANDROID_SERIAL"
status=0
FLEXTUNNEL_LOCAL_JNILIBS=1 ./gradlew :app:connectedDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.serverNodeId="$node_id" \
  -Pandroid.testInstrumentationRunnerArguments.authKey="$client_secret" \
  -Pandroid.testInstrumentationRunnerArguments.targetPort="$TARGET_PORT" || status=$?

if [ "$status" != 0 ]; then
  echo "== server log"
  ssh "$HOST" 'sudo journalctl -u flextunnel-e2e --no-pager -n 40 -o cat' || true
fi
exit "$status"
