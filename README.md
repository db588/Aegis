# Aegis

A lightweight, root-free DNS filter for Android. Blocks social media, ads,
trackers and malicious domains using Pi-hole compatible blocklists, entirely
on-device.

## What it does

Aegis runs a local VPN service that intercepts DNS queries. Blocked domains get
an NXDOMAIN response, so the app or website simply cannot connect. Everything
else is forwarded to your upstream DNS resolver untouched.

- **No root required** — uses the standard Android `VpnService` API
- **Nothing leaves your device** — no accounts, no telemetry, no cloud
- **Pi-hole compatible** — import any hosts-format or domain-list blocklist
- **Fast** — only DNS is routed through the tunnel, so your real traffic is unaffected

## Features

- One-tap toggle with persistent status notification
- Pre-loaded social media blocklist (X, Reddit, Instagram, Facebook, TikTok, Discord, and more)
- Import blocklists from a URL (StevenBlack hosts included as a one-tap preset)
- Import blocklists from a local file
- Per-list enable/disable and delete
- Whitelist manager for domains caught by mistake
- Live session counter of blocked queries

## Building

Requires JDK 17 and the Android SDK (API 34).

```bash
git clone https://github.com/YOUR_USERNAME/aegis.git
cd aegis
./gradlew assembleDebug
```

Install to a connected device:

```bash
adb devices          # confirm your phone is listed
./gradlew installDebug
```

If `adb devices` shows nothing, enable Developer Options and USB debugging on
the phone, then re-plug and accept the pairing prompt.

## Usage

1. Open Aegis and flip the toggle. Android will ask for VPN permission — accept it.
2. The Social Media list is created on first launch and is enabled by default.
3. Tap **Import URL** to pull in a Pi-hole list. The StevenBlack preset is a good default.
4. Tap **Whitelist** to unblock anything caught by mistake.

After changing blocklists, toggle Aegis off and on for changes to take effect.

## Known limitations

- **Encrypted DNS bypasses filtering.** Apps and browsers using DNS-over-HTTPS
  or DNS-over-TLS (Chrome's Secure DNS, Firefox's default DoH) resolve names
  without touching the system resolver, so Aegis never sees those queries.
  Disable Secure DNS in Chrome and Private DNS in Android settings for full coverage.
- **Only one VPN can be active at a time on Android.** Aegis cannot run alongside
  another VPN app.
- **DNS-level blocking is not tamper-proof.** Anyone who can open Settings can
  turn the VPN off. This is a friction tool, not a lock.
- IPv4 UDP only; IPv6 DNS transport is not currently filtered.

## Privacy

Aegis collects nothing. Blocklists and whitelists are stored in a local SQLite
database on your device. The only network requests it makes are DNS queries to
your configured upstream resolver, and blocklist downloads that you explicitly
initiate.

## License

MIT — see [LICENSE](LICENSE).
# Aegis
