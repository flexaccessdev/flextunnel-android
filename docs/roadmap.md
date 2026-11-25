# Roadmap

## More browsers

[socks5-browsers.md](socks5-browsers.md) covers the setup confirmed on a device
(Firefox + FoxyProxy). Candidates from a web survey (2026-11), **not tried yet**:

- Firefox Beta and Nightly have `about:config` (release doesn't, Mozilla bug
  1813163): `network.proxy.type=1`, `network.proxy.socks=127.0.0.1`,
  `network.proxy.socks_port=<port>`, `network.proxy.socks_version=5`,
  `network.proxy.socks5_remote_dns=true` (the SOCKS5 remote-DNS pref since
  Firefox 128; `socks_remote_dns` is now SOCKS4 only).
- FoxyProxy's Proxy by Patterns mode on Android.
- Other Android-compatible proxy add-ons on addons.mozilla.org: SmartProxy,
  ZeroOmega.
- Chrome for Android through a Wi-Fi PAC URL returning `SOCKS5 127.0.0.1:<port>`.
- Cromite's built-in proxy page (`chrome://proxy`) taking
  `socks5://127.0.0.1:<port>`; Chromium resolves names on the proxy side for SOCKS5.
- Edge for Android has a curated extension list; no SOCKS5 proxy extension
  confirmed in it. Kiwi Browser is discontinued.

To do: try them on the emulator against `scripts/e2e.sh`'s server
(e.g. `http://e2e.localhost.flextunnel.internal:<port>/`), then add the ones
that work to socks5-browsers.md and the help screen (`ui/BrowserHelpScreen.kt`).
