# Roadmap

## Browser setup instructions

The in-app help (Using the proxy) covers only what holds for any client: the
proxy's address, that names must resolve through it (remote DNS), and port
forwards for apps without a proxy setting. Which browser to recommend, and how
to set each one up, is deferred until checked on a device. Starting notes from
a web survey (2026-11), **none of it tried yet**:

- Firefox for Android, release channel: no proxy setting and no `about:config`
  (Mozilla bug 1813163). Beta and Nightly have `about:config`: `network.proxy.type=1`,
  `network.proxy.socks=127.0.0.1`, `network.proxy.socks_port=<port>`,
  `network.proxy.socks_version=5`, `network.proxy.socks5_remote_dns=true`
  (the SOCKS5 remote-DNS pref since Firefox 128; `socks_remote_dns` is now SOCKS4 only).
- Proxy add-ons listed as Android-compatible on addons.mozilla.org: FoxyProxy
  Standard (per-proxy "Proxy DNS", on by default; Android support called
  experimental), SmartProxy, ZeroOmega. Its help mentions an Android 17 local
  network permission — whether 127.0.0.1 needs it is unknown.
- Chrome for Android: no extensions on phones; Android's Wi-Fi proxy is HTTP or
  PAC only. Whether a PAC returning `SOCKS5 127.0.0.1:<port>` works is unknown.
- Edge for Android has a curated extension list; no SOCKS5 proxy extension
  confirmed in it. Kiwi Browser is discontinued.
- Cromite has a built-in proxy page (`chrome://proxy`) taking
  `socks5://127.0.0.1:<port>`; Chromium resolves names on the proxy side for SOCKS5.

To do: try the candidates on the emulator against `scripts/e2e.sh`'s server
(e.g. `http://e2e.localhost.flextunnel.internal:<port>/`), then write the
confirmed ones into the help screen (`ui/BrowserHelpScreen.kt`) and the README.
