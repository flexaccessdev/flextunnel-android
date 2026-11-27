#!/usr/bin/env bash
#
# End-to-end test: the app on the development emulator, against a throwaway
# flextunnel server running on this machine. See docs/e2e.md.
#
#   1. the server is the release binary of the tag the app's pinned
#      libflextunnel comes from (flextunnel.releaseTag in gradle.properties),
#      for this machine's OS and CPU, downloaded once per tag into
#      build/e2e/releases and checked against the sha256 digest GitHub reports
#      for the asset — client and server speak one protocol version and keep
#      no backward compatibility. flexaccess-keys comes the same way, at the
#      tag that release's Cargo.toml names. With FLEXTUNNEL_LOCAL_JNILIBS=1 the
#      app links the local core build instead (scripts/run-device.sh), so the
#      server is built from the sibling ../flextunnel working tree;
#   2. a fresh server identity and client key are made for this run, and the
#      server runs here beside an HTTP server on loopback;
#   3. the instrumented test (app/src/androidTest/…/E2eTest.kt) installs and
#      runs on the device, given the server's node id, the client key and the
#      HTTP port. The emulator finds the server through iroh's n0 discovery and
#      default relays, as a real client would, so the server can be anywhere
#      with internet access;
#   4. both stop, whatever happened.
#
# The device is ADB_SERIAL (default emulator-5554) on the adb server
# ANDROID_ADB_SERVER_PORT points at (the MBP's, through FlexTunnel: 15037).
# Needs uv, curl and internet access; a local server build needs cargo.
#
# Usage: scripts/e2e.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
SIBLING=../flextunnel
WORK=$PWD/build/e2e
RUN=$WORK/run
# Release binaries, apart from local builds: only a checked download lands here.
RELEASES_ROOT=$WORK/releases
TOOLS_ROOT=$WORK/tools
export ANDROID_SERIAL="${ADB_SERIAL:-emulator-5554}"

info() { echo "== $*"; }
die() { echo "e2e: error: $*" >&2; exit 1; }

case "${1:-}" in
  "") ;;
  -h|--help) sed -n '2,29p' "${BASH_SOURCE[0]}"; exit 0 ;;
  *) die "unknown option: $1" ;;
esac
for tool in uv curl; do command -v "$tool" >/dev/null || die "$tool not found"; done

case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) platform=linux-amd64 ;;
  Linux/aarch64|Linux/arm64) platform=linux-arm64 ;;
  Darwin/arm64) platform=macos-arm64 ;;
  *) die "no flextunnel release binary for $(uname -s)/$(uname -m)" ;;
esac

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# Downloads release asset $3 of GitHub repo $1 at tag $2 to $4, once.
download_release_binary() {
  local repo=$1 tag=$2 asset=$3 dest=$4 digest tmp actual
  [ -x "$dest" ] && return
  info "downloading $repo $tag $asset"
  digest=$(curl -fsSL "https://api.github.com/repos/$repo/releases/tags/$tag" |
    uv run --no-project python -c '
import json, sys
assets = [a for a in json.load(sys.stdin)["assets"] if a["name"] == sys.argv[1]]
print(assets[0].get("digest") or "" if assets else "")' "$asset") ||
    die "could not read the $repo $tag release"
  [[ $digest == sha256:* ]] || die "$repo $tag has no sha256 digest for $asset"
  mkdir -p "$RELEASES_ROOT" "$(dirname "$dest")"
  tmp=$(mktemp "$RELEASES_ROOT/.download.XXXXXX")
  curl -fsSL -o "$tmp" "https://github.com/$repo/releases/download/$tag/$asset" ||
    { rm -f "$tmp"; die "could not download $asset from $repo $tag"; }
  actual=$(sha256 "$tmp")
  [ "$actual" = "${digest#sha256:}" ] ||
    { rm -f "$tmp"; die "$asset from $repo $tag: sha256 $actual, release says ${digest#sha256:}"; }
  chmod +x "$tmp"
  mv "$tmp" "$dest"
}

# The flexaccess-keys tag a flextunnel Cargo.toml (on stdin) depends on.
keys_tag_of() { sed -n 's/^flexaccess-keys = .*tag = "\([^"]*\)".*/\1/p'; }

if [ "${FLEXTUNNEL_LOCAL_JNILIBS:-}" = 1 ]; then
  command -v cargo >/dev/null || die 'cargo not found (needed to build the local server)'
  info "building flextunnel from the $SIBLING working tree"
  CARGO_TARGET_DIR=$WORK/cargo-target cargo install --locked --quiet \
    --path "$SIBLING/crates/flextunnel-cli" --root "$TOOLS_ROOT" --force
  flextunnel=$TOOLS_ROOT/bin/flextunnel
  keys_tag=$(keys_tag_of <"$SIBLING/Cargo.toml")
else
  tag=$(sed -n 's/^flextunnel\.releaseTag=//p' gradle.properties)
  [ -n "$tag" ] || die 'no flextunnel.releaseTag in gradle.properties'
  flextunnel=$RELEASES_ROOT/flextunnel-$tag/flextunnel
  download_release_binary flexaccessdev/flextunnel "$tag" "flextunnel-$platform" "$flextunnel"
  keys_tag=$(curl -fsSL "https://raw.githubusercontent.com/flexaccessdev/flextunnel/$tag/Cargo.toml" |
    keys_tag_of) || die "could not read Cargo.toml at flextunnel $tag"
fi
[ -n "$keys_tag" ] || die 'could not find the flexaccess-keys tag in the flextunnel Cargo.toml'
keys=$RELEASES_ROOT/flexaccess-keys-$keys_tag/flexaccess-keys
download_release_binary flexaccessdev/flexaccess-keys "$keys_tag" "flexaccess-keys-$platform" "$keys"

json_field() { uv run --no-project python -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

rm -rf "$RUN"
mkdir -p "$RUN/www"
chmod 700 "$RUN"
echo "flextunnel-android e2e" >"$RUN/www/index.html"
"$flextunnel" generate-iroh-key -o "$RUN/server.key" >/dev/null
node_id=$("$flextunnel" show-iroh-id -s "$RUN/server.key" --json | json_field endpoint_id)
client=$("$keys" generate-auth-key --json flextunnel-android-e2e)
client_secret=$(json_field private_key <<<"$client")
json_field authorized_key <<<"$client" >"$RUN/authorized_keys"
printf 'secret_file = "%s"\nauthorized_keys_file = "%s"\n' "$RUN/server.key" "$RUN/authorized_keys" >"$RUN/server.toml"

# A free port for the HTTP server on loopback.
target_port=$(uv run --no-project python -c '
import socket
s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')

pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup EXIT

info "starting the e2e server ($("$flextunnel" --version))"
uv run --no-project python -m http.server "$target_port" --bind 127.0.0.1 --directory "$RUN/www" \
  >"$RUN/http.log" 2>&1 &
pids+=($!)
"$flextunnel" server start -c "$RUN/server.toml" >"$RUN/server.log" 2>&1 &
pids+=($!)
# Ready once the server prints its id and the HTTP server answers.
ready=0
for _ in $(seq 60); do
  if grep -q "Node ID" "$RUN/server.log" &&
    curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$target_port/"; then
    ready=1
    break
  fi
  kill -0 "${pids[1]}" 2>/dev/null || break
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo 'the e2e server did not come up:' >&2
  tail -n 30 "$RUN/server.log" "$RUN/http.log" >&2
  exit 1
fi
echo "   server node id $node_id"

info "running the instrumented test on $ANDROID_SERIAL"
status=0
./gradlew :app:connectedDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.serverNodeId="$node_id" \
  -Pandroid.testInstrumentationRunnerArguments.authKey="$client_secret" \
  -Pandroid.testInstrumentationRunnerArguments.targetPort="$target_port" || status=$?

if [ "$status" != 0 ]; then
  info "server log"
  tail -n 40 "$RUN/server.log" || true
fi
exit "$status"
