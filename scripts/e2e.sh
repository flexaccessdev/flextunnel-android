#!/usr/bin/env bash
#
# End-to-end tests: the app on the development emulator, against throwaway
# flextunnel servers running on this machine. See docs/e2e.md.
#
#   1. the servers are the release binary of the tag the app's pinned
#      libflextunnel comes from (flextunnel.releaseTag in gradle.properties),
#      for this machine's OS and CPU, downloaded once per tag into
#      build/e2e/releases and checked against the sha256 digest GitHub reports
#      for the asset — client and server speak one protocol version and keep
#      no backward compatibility. flexaccess-keys comes the same way, at the
#      tag that release's Cargo.toml names. With FLEXTUNNEL_LOCAL_JNILIBS=1 the
#      app links the local core build instead (scripts/run-device.sh), so the
#      servers are built from the sibling ../flextunnel working tree;
#   2. three servers (site, plain, cidr: see below), a fixture site
#      (scripts/e2e/fixtures) and a control endpoint that stops and restarts
#      site and cidr (scripts/e2e/control.py) start here on loopback, with a
#      fresh identity per server and one fresh client key;
#   3. the instrumented tests (app/src/androidTest) install and run on the
#      device, given the servers' node ids, the client key, the site's port and
#      the control endpoint's. They drive the session through the app's SOCKS5
#      proxy and forwards, and its screens. The emulator finds the servers
#      through iroh's n0 discovery and default relays, as a real client would,
#      and cannot see this machine's loopback: it reaches the site and the
#      control endpoint through the tunnel, as the servers' own loopback;
#   4. everything stops, whatever happened; the run's logs stay in
#      build/e2e/run.
#
# The device is ADB_SERIAL (default emulator-5554) on the adb server
# ANDROID_ADB_SERVER_PORT points at (the MBP's, through FlexTunnel: 15037).
# Needs uv, curl and internet access; a local server build needs cargo.
#
# Usage: scripts/e2e.sh [gradle arguments]
#   scripts/e2e.sh -Pandroid.testInstrumentationRunnerArguments.class=dev.flexaccess.flextunnel.SocksE2eTest
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
  -h|--help) sed -n '2,34p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# --- servers and site ----------------------------------------------------------
#
# Three servers, each with its own identity and HOME (its lock and
# duplicate-id blocklist stay scratch files), all authorizing one fresh client
# key, and all with this machine's loopback as their own:
#   - site: routes the fixture site's host alias `e2e.flextunnel.test` (a name
#     only it resolves, so a page that loads came through it) and `localhost`
#     (which the device also answers directly, so a fallback would show);
#   - plain: routes nothing; reached by its loopback name. Never stopped, it
#     carries the tests' calls to the control endpoint;
#   - cidr: routes TEST-NET-3 and logs each stream's target, so a test can see
#     an address in the range arrive.
# site and cidr belong to the control endpoint (scripts/e2e/control.py), so a
# test can stop and restart them.

rm -rf "$RUN"
mkdir -p "$RUN/www" "$RUN/site-home" "$RUN/plain-home" "$RUN/cidr-home"
chmod 700 "$RUN"
cp scripts/e2e/fixtures/* "$RUN/www/"

client=$("$keys" generate-auth-key --json flextunnel-android-e2e)
client_secret=$(json_field private_key <<<"$client")
json_field authorized_key <<<"$client" >"$RUN/authorized_keys"
for server in site plain cidr; do
  "$flextunnel" generate-iroh-key -o "$RUN/$server.key" >/dev/null
  printf 'secret_file = "%s"\nauthorized_keys_file = "%s"\n' "$RUN/$server.key" "$RUN/authorized_keys" \
    >"$RUN/$server.toml"
done
node_id() { "$flextunnel" show-iroh-id -s "$RUN/$1.key" --json | json_field endpoint_id; }
site_node_id=$(node_id site)
plain_node_id=$(node_id plain)
cidr_node_id=$(node_id cidr)
cat >>"$RUN/site.toml" <<'TOML'
routed_domains = ["e2e.flextunnel.test", "*.e2e.flextunnel.test", "localhost"]

[host_aliases]
"e2e.flextunnel.test" = "127.0.0.1"
TOML
echo 'routed_cidrs = ["203.0.113.0/24"]' >>"$RUN/cidr.toml"

# Free loopback ports for the site and the control endpoint.
read -r site_port control_port < <(uv run --no-project python -c '
import socket
socks = [socket.socket() for _ in range(2)]
for s in socks: s.bind(("127.0.0.1", 0))
print(*(s.getsockname()[1] for s in socks))')

uv run --no-project python -c '
import json, sys
flextunnel, run = sys.argv[1:]
print(json.dumps({
    name: {
        "argv": [flextunnel, "server", "start", "-c", f"{run}/{name}.toml"],
        "home": f"{run}/{name}-home",
        "log": f"{run}/{name}.log",
        "env": env,
    }
    for name, env in [
        ("site", {}),
        ("cidr", {"RUST_LOG": "info,flextunnel_core::proxy::server=debug"}),
    ]
}))' "$flextunnel" "$RUN" >"$RUN/servers.json"

pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup EXIT

info "starting the e2e servers ($("$flextunnel" --version))"
uv run --no-project python -m http.server "$site_port" --bind 127.0.0.1 --directory "$RUN/www" \
  >"$RUN/http.log" 2>&1 &
pids+=($!)
uv run --no-project python scripts/e2e/control.py "$control_port" "$RUN/servers.json" \
  >"$RUN/control.log" 2>&1 &
pids+=($!)
HOME=$RUN/plain-home "$flextunnel" server start -c "$RUN/plain.toml" >"$RUN/plain.log" 2>&1 &
pids+=($!)

# Ready once every server prints its id, the site answers and the control
# endpoint sees its servers running.
logs=("$RUN/site.log" "$RUN/plain.log" "$RUN/cidr.log" "$RUN/control.log" "$RUN/http.log")
ready=0
for _ in $(seq 60); do
  if grep -q "Node ID" "$RUN/site.log" "$RUN/plain.log" "$RUN/cidr.log" 2>/dev/null &&
    [ "$(grep -l "Node ID" "$RUN/site.log" "$RUN/plain.log" "$RUN/cidr.log" 2>/dev/null | wc -l)" = 3 ] &&
    curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$site_port/" &&
    curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$control_port/health"; then
    ready=1
    break
  fi
  for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || break 2; done
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo 'the e2e servers did not come up:' >&2
  tail -n 20 "${logs[@]}" >&2
  exit 1
fi
echo "   site  $site_node_id, http://e2e.flextunnel.test:$site_port"
echo "   plain $plain_node_id, control on its loopback port $control_port"
echo "   cidr  $cidr_node_id"

# --- tests -----------------------------------------------------------------------

info "running the instrumented tests on $ANDROID_SERIAL"
arg=-Pandroid.testInstrumentationRunnerArguments
status=0
./gradlew :app:connectedDebugAndroidTest --console=plain \
  "$arg.siteNodeId=$site_node_id" \
  "$arg.plainNodeId=$plain_node_id" \
  "$arg.cidrNodeId=$cidr_node_id" \
  "$arg.authKey=$client_secret" \
  "$arg.sitePort=$site_port" \
  "$arg.controlPort=$control_port" \
  "$@" || status=$?

if [ "$status" != 0 ]; then
  for log in "$RUN/site.log" "$RUN/plain.log" "$RUN/cidr.log"; do
    info "$(basename "$log")"
    tail -n 30 "$log" || true
  done
fi
exit "$status"
