# Using the SOCKS5 proxy on Android

While the session is on, the app runs a SOCKS5 proxy on the phone at
`127.0.0.1:<port>` (1080 unless changed in **Settings**). It has no password
and listens on loopback only, so only apps on the phone can use it. The in-app
help (the **?** on the home screen) has the short version of this page.

## The one rule: resolve names through the proxy

Point the client at `127.0.0.1:<port>`, type SOCKS5, and have it send hostnames
to the proxy. Clients call this "remote DNS", "proxy DNS", or `socks5h`. Names
like `<name>.localhost.flextunnel.internal` and your destinations' routed
domains only exist on the far side. Looked up on the phone, they fail with
"address not found" before the proxy ever sees them.

Sending everything through the proxy is fine. A host that no destination routes
is connected directly from the phone, the same as without the proxy.

## Firefox + FoxyProxy

Firefox for Android has no proxy setting of its own, but it runs the FoxyProxy
Standard add-on. Tested with Firefox 161 and FoxyProxy 8.1 on Android 17 (the
emulator).

1. In Firefox, open
   [FoxyProxy Standard](https://addons.mozilla.org/android/addon/foxyproxy-standard/)
   on addons.mozilla.org, then **Add to Firefox → Add**.
2. **Menu (⋮) → Extensions → FoxyProxy → ⚙ (Settings) → Proxies → Add**:
   - **Title**: `flextunnel` (anything)
   - **Type**: `SOCKS5`
   - **Hostname**: `127.0.0.1`
   - **Port**: the app's SOCKS5 port, `1080` by default
   - **Proxy DNS**: on (the default). This is the remote DNS above.
   - **Username / Password**: empty

   Then **Save**. The form is wider than the screen; scroll it sideways to
   reach Hostname and Port, and check them after saving.
3. **Menu (⋮) → Extensions → FoxyProxy** (the row, not the ⚙) opens the popup.
   Pick **flextunnel** to send all traffic through the proxy. Pick **Disable**
   to turn it off.
4. Open `http://<name>.localhost.flextunnel.internal:<port>/` for a service on
   a server's own loopback, or a routed host by its usual name.

Notes:

- Firefox needs no Android permission to reach the proxy. The local network
  permission it asks about on Android 17 is not needed for `127.0.0.1`.
- Right after Firefox starts, the first page can load before FoxyProxy has set
  the proxy. A routed name then shows "Address Not Found". Reload it.
- To send only some sites through the proxy, add patterns to the proxy entry
  (**Proxy by Patterns**): for example, wildcard `*.flextunnel.internal` and
  one wildcard per routed domain. Then choose **Proxy by Patterns** in the
  popup. Unmatched sites then skip the proxy. This was not tried on Android;
  sending everything (step 3) works because unrouted hosts go direct anyway.
- Firefox's DNS over HTTPS can resolve names itself even with Proxy DNS on. If
  routed names fail while public sites work, turn it off or add the internal
  suffixes as exceptions (Firefox Settings → DNS over HTTPS).

## Chrome and other Chromium browsers

Chrome for Android has no proxy setting and no extensions. Android's own Wi-Fi
proxy setting takes an HTTP proxy or a PAC URL, not SOCKS5. For Chrome, use
Firefox for internal sites, or a port forward (below) for a single host.

## Other apps

- **Apps with a SOCKS5 setting** (SSH clients, some download managers, Termux
  tools): `127.0.0.1`, the port, no auth, remote DNS on if offered. In Termux:
  `curl -x socks5h://127.0.0.1:1080 http://<name>.localhost.flextunnel.internal:<port>/`
  (`socks5h`, not `socks5`).
- **Apps without a proxy setting** use a port forward: **Port forwards → +**,
  for example local `2222` → `<name>.localhost.flextunnel.internal:22`, then
  connect the app to `127.0.0.1` port `2222`. A forward leads to one host and
  port; the name in it is resolved on the far side.

## Good to know

- The proxy and the forwards serve only while the session is on (the ongoing
  notification). Any app on the phone can use them while it is, since they
  have no password. Stop the session when you don't need it.
- The port is fixed, so a browser configured once keeps working. If another app
  holds the port, the session won't start: pick another port in Settings and
  update the browser.
- How the proxy routes, and recipes for desktop clients, are in flextunnel's
  [proxy usage guide](https://github.com/flexaccessdev/flextunnel/blob/main/docs/proxy-usage.md).
