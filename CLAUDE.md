# Aegis: project context for Claude Code

Aegis is an Android DNS-filtering app. It runs a local `VpnService` that captures
DNS queries, checks each domain against user-managed blocklists (Pi-hole / hosts
format), and returns NXDOMAIN for blocked domains. Everything else is forwarded
upstream. No root required, no traffic leaves the device except normal DNS.

## Architecture

| File | Responsibility |
|---|---|
| `vpn/DnsVpnService.kt` | TUN interface, foreground notification, packet read/write loop |
| `vpn/DnsResolver.kt` | Parses DNS queries, block/forward decision, builds IPv4/UDP response packets |
| `data/BlocklistManager.kt` | Parses hosts/Pi-hole format, imports from URL/file, whitelist ops |
| `data/AppDatabase.kt` | Room DB, DAOs for blocklists / blocked domains / whitelist |
| `config/Config.kt` | Tunables: upstream DNS, social media domain list, timeouts |
| `MainActivity.kt` | Toggle, blocklist list, import dialogs, whitelist manager |

### Key design decision
The VPN routes **only** the fake DNS address (`10.111.222.53/32`) into the tunnel.
All other traffic bypasses the VPN entirely. This means no TCP forwarding to
implement, no throughput penalty, and no battery cost from proxying real traffic.

## Conventions
- Kotlin, ViewBinding available but `findViewById` used in MainActivity
- Room via KSP (not kapt)
- Coroutines via `lifecycleScope`; DB reads inside the VPN thread use `runBlocking`
- Min SDK 24, target 34, JVM target 17

## Build commands
```bash
./gradlew assembleDebug          # debug APK
./gradlew installDebug           # build + install to connected device
./gradlew assembleRelease        # signed release APK (needs env vars, see RELEASE.md)
./gradlew bundleRelease          # AAB for Play Store
./gradlew lint                   # Android lint
```

## Known gaps / good next tasks
- No DNS response cache yet; every query hits upstream, and client retry storms
  repeat the same blocked lookup dozens of times. An LRU keyed on (name, type)
  with TTL respect and negative caching would cut both.
- Blocklist changes require a VPN off/on cycle to take effect. Wiring
  `DnsResolver.reloadBlocklists()` to a broadcast would fix this.
- No per-domain block log; `blockedCount` is a session counter only.
- IPv6 (AAAA over IPv6 transport) is not handled; only IPv4 UDP port 53.
- DoH/DoT bypass: apps using their own encrypted DNS (Chrome's Secure DNS,
  Firefox DoH) will bypass filtering. Documented in README as a known limitation.
- No unit tests. `DnsResolver.buildResponsePacket` and `BlocklistManager.parseBlocklistContent`
  are the highest-value targets.
- Three leftover compiler warnings: unused `id` in MainActivity, redundant null
  check and unnecessary `!!` in BlocklistManager.

## Things to be careful about
- `applicationId` is `uk.co.logicscience.aegis`. It can never be changed after
  first Play Store publish, so confirm this is the final value before that point.
- Packet parsing is index arithmetic on raw bytes. Verify IHL is read from the
  packet rather than assumed to be 20.
- **Concurrency.** DNS handling runs on a worker pool, not the read loop. Three
  invariants hold it together: the reused read buffer must be copied before
  handoff; TUN writes must stay inside `synchronized(writeLock)`; and
  `DnsResolver` must hold no mutable per-query state (results come back via
  `DnsResult`). Breaking any of these produces intermittent, hard-to-reproduce
  DNS corruption rather than a clean failure.
- **Debug logging must never ship.** It is gated behind
  `BuildConfig.DEBUG && Config.LOG_DNS_QUERIES` and stripped by a ProGuard
  `-assumenosideeffects` rule. `buildConfig = true` must stay in `buildFeatures`.
  Verify with the dex strings check in DEVELOPMENT.md section 10 after touching
  the logging path.
- Play Store requires a privacy policy URL for any app requesting VPN permission.
- A release signing key exists outside the repo. See DEVELOPMENT.md section 13a.
