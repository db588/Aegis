# Aegis: Technical Documentation

_Status as of 15 August 2026. Covers the app as built, the environment it was built in, every fix applied along the way, and the known issues still outstanding._

---

## 1. What Aegis is

An Android app that blocks domains by filtering DNS locally. It runs a `VpnService`, captures DNS queries, checks each requested domain against user-managed blocklists, and returns NXDOMAIN for anything blocked. Everything else is forwarded to an upstream resolver untouched.

The effect: blocked domains fail to resolve, so both apps and browsers cannot reach them. No root required. Nothing is sent to any server that the user did not initiate.

**Current state:** working. Installed and verified on a Moto g06 (Android 15, API 35). Blocking confirmed for social media domains, normal browsing unaffected.

---

## 2. The central design decision: partial routing

This is the most important thing to understand about the app, and the thing most likely to be accidentally broken by a future change.

The VPN routes **only** a single fake DNS address into the tunnel:

```kotlin
.addAddress("10.111.222.1", 24)     // the TUN interface itself
.addDnsServer("10.111.222.53")      // tell Android to send DNS here
.addRoute("10.111.222.53", 32)      // route ONLY that address into the tunnel
```

Android is told the system DNS server is `10.111.222.53`, an address that exists nowhere. The only route into the tunnel is a `/32` for that exact address. So:

- DNS queries go into the tunnel, where Aegis reads and answers them
- All other traffic (HTTP, HTTPS, everything) bypasses the tunnel entirely and takes the normal network path

**Why this matters.** The obvious alternative is `addRoute("0.0.0.0", 0)`, which pulls all traffic through the tunnel. That would require Aegis to implement TCP forwarding, proxying every connection on the device through userspace. That is a large amount of work, a meaningful throughput penalty, and a constant battery cost. The partial route avoids all of it.

The tradeoff: Aegis can only see DNS. It cannot filter by IP, inspect SNI, or block anything that resolves without using the system resolver. That limitation is acceptable for the intended use, but it is a design boundary rather than an implementation gap.

Confirmed working, from the device log:

```
lp{{InterfaceName: tun0 LinkAddresses: [ 10.111.222.1/24 ]
DnsAddresses: [ /10.111.222.53 ]
Routes: [ 10.111.222.53/32 -> 0.0.0.0 tun0, ::/0 unreachable ]}}
```

---

## 3. Packet flow

```
App resolves example.com
        |
        v
Android sends UDP to 10.111.222.53:53
        |
        v
Route matches /32, packet enters tun0
        |
        v
DnsVpnService read loop
   reads raw IPv4 packet from FileInputStream on the TUN fd
   checks version == 4, protocol == 17 (UDP), dst port == 53
        |
        v
DnsResolver.handlePacket(packet, length, ihl)
   extracts DNS payload at offset ihl + 8
   DnsPacket.parseQueryName() reads the QNAME labels
        |
        +-- whitelisted?  -> forwardQuery()
        +-- blocked?      -> createNxDomain()
        +-- otherwise     -> forwardQuery()
        |
        v
buildResponsePacket()
   copies the IPv4 header, swaps src/dst addresses
   swaps UDP src/dst ports
   sets total length, TTL, recomputes IP checksum
   appends the DNS payload
        |
        v
FileOutputStream on the TUN fd, back to the app
```

### Packet construction details

The part most likely to break under future edits. `buildResponsePacket` must produce a complete, valid IPv4 packet, not just a DNS payload. Specifically:

- **IHL is read from the packet**, not assumed to be 20. `val ihl = (packet[0].toInt() and 0x0F) * 4`. IPv4 headers can carry options.
- **Addresses swap**: bytes 12 to 15 (source) and 16 to 19 (destination) exchange places.
- **Ports swap**: at offset `ihl`, the UDP source and destination 16-bit values exchange.
- **Total length** at bytes 2 to 3 must be `ihl + 8 + dnsPayloadSize`.
- **IP checksum** at bytes 10 to 11 must be zeroed before computing, then written back. The `checksum()` helper does the standard ones-complement sum with carry folding.
- **UDP checksum** is left as zero. This is legal for IPv4 (it means "not computed") and saves implementing the pseudo-header sum.

An error anywhere here presents as total DNS failure rather than a partial one, because the OS silently discards malformed packets. If all resolution breaks after a change to this function, that is the first place to look.

### NXDOMAIN construction

`createNxDomain` mutates a copy of the query rather than building a response from scratch:

```kotlin
response[2] = (response[2].toInt() or 0x80).toByte()          // QR = 1 (response)
response[3] = ((response[3].toInt() and 0x70) or 0x83).toByte() // RA = 1, RCODE = 3
response[6..11] = 0                                            // zero AN/NS/AR counts
```

The question section is preserved, which is what resolvers expect. Returning NXDOMAIN rather than `0.0.0.0` means the client sees "no such host" rather than attempting a connection to a dead address, which fails faster and more cleanly.

---

## 4. File-by-file

| File | Responsibility |
|---|---|
| `vpn/DnsVpnService.kt` | TUN setup, foreground notification, read/write loop, session counter |
| `vpn/DnsResolver.kt` | Query parsing, block decision, upstream forwarding, response packet building |
| `data/BlocklistManager.kt` | Hosts/Pi-hole format parsing, import from URL/file/list, whitelist operations |
| `data/AppDatabase.kt` | Room database, three DAOs |
| `data/Blocklist.kt` | Entity definitions |
| `config/Config.kt` | Upstream DNS, social media domain list, timeouts, feature flags |
| `MainActivity.kt` | Toggle, blocklist list, import dialogs, whitelist manager, stats polling |
| `BlocklistAdapter.kt` | RecyclerView adapter with enable toggle and delete |

### DnsVpnService

Runs as a **foreground service** with a persistent notification. This is not optional: Android kills background services that hold a VPN, and from API 34 the `foregroundServiceType` must be declared. The manifest uses `systemExempted` with matching permissions.

`isRunning` and `blockedCount` are `companion object` statics so `MainActivity` can read them without binding to the service. `blockedCount` is an `AtomicLong` because it is written from the VPN thread and read from the UI thread.

`addDisallowedApplication(packageName)` excludes Aegis itself from the tunnel, preventing a loop where the resolver's own upstream lookups get captured by the resolver.

`onRevoke()` handles the user turning the VPN off from Android settings or another VPN app displacing Aegis.

### DnsResolver

`reloadBlocklists()` uses `runBlocking` to read from Room synchronously. This is acceptable only because it runs once at service start, off the main thread. Do not call it from the packet loop.

Blocklists are held as in-memory `Set<String>` for O(1) lookup. With a large imported list (StevenBlack is roughly 150,000 entries) this is a few megabytes of heap, which is fine.

Subdomain matching walks the label list upward:

```kotlin
val parts = d.split(".")
for (i in 1 until parts.size) {
    if (blockedDomains.contains(parts.subList(i, parts.size).joinToString("."))) return true
}
```

So `reddit.com` in the blocklist matches `www.reddit.com`, `oauth.reddit.com`, and so on. This is why the blocklist entries are bare domains without `www.`.

### BlocklistManager

`parseBlocklistContent` handles the three formats found in the wild:

- Comment lines beginning with `#` or `!`, skipped
- Hosts format: `0.0.0.0 domain.com` or `127.0.0.1 domain.com`, second field taken
- Bare domain lines, taken whole

Each candidate is validated against a domain regex before being stored, which discards malformed lines and the occasional IP address.

---

## 5. Data model

```
blocklists
  id (PK, autogenerate)
  name, description
  isEnabled, isCustom
  url, lastUpdated, domainCount

blocked_domains
  domain     \  composite primary key
  blocklistId /
  addedAt

whitelist
  domain (PK)
  addedAt
```

**The composite key on `blocked_domains` matters.** It was originally `domain` alone, which crashed on import whenever the same domain appeared in two enabled lists (extremely common: `doubleclick.net` is in almost every ad blocklist). The composite key plus `OnConflictStrategy.IGNORE` on all inserts makes imports idempotent and overlap-safe.

---

## 6. Environment setup

Everything below was needed on a fresh Linux Mint 21.3 machine. Recording it because none of it is in the repo and all of it will be needed again on another machine.

### JDK 17

AGP 8.5 requires 17. The system had 11.

```bash
sudo apt install openjdk-17-jdk
sudo update-alternatives --config java   # select 17
java -version                            # verify
```

### Android SDK on PATH

The SDK existed at `~/Android/Sdk` but `adb` was not on PATH and `ANDROID_HOME` was unset.

```bash
cat >> ~/.bashrc << 'EOF'

export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
EOF
source ~/.bashrc
```

### local.properties

Gitignored, so it must be created per machine:

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

### Gradle wrapper

**Do not `sudo apt install gradle`.** Ubuntu 22.04 and Mint 21.3 ship Gradle 4.4.1, roughly seven years stale, and it fails in confusing ways. Use SDKMAN:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install gradle 8.7
gradle wrapper --gradle-version 8.7
```

The system Gradle is only a bootstrap to generate the wrapper. After that, `./gradlew` uses its own downloaded distribution and the system copy is never needed again.

### Build commands

```bash
./gradlew assembleDebug     # build APK only
./gradlew installDebug      # build and install to connected device
./gradlew assembleRelease   # signed release APK (needs env vars, see RELEASE.md)
./gradlew bundleRelease     # AAB for Play Store
./gradlew lint
```

Debug installs as `com.example.aegis.debug` (via `applicationIdSuffix`), so debug and release builds can coexist on one device.

---

## 7. Git setup: two GitHub accounts on one machine

The work account (`dbrand-dataops`) is authenticated through `gh` and must keep working. The personal account (`db588`) owns this repo. Pushing failed with a 403 because `gh`'s HTTPS credential helper supplied the work token.

The solution avoids touching `gh` at all: a dedicated SSH key plus a host alias, used only by this repo.

```bash
ssh-keygen -t ed25519 -C "db588" -f ~/.ssh/id_db588
cat ~/.ssh/id_db588.pub      # add at github.com/settings/keys while signed in as db588
```

```
# ~/.ssh/config
Host github-db588
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_db588
  IdentitiesOnly yes
```

```bash
git remote set-url origin git@github-db588:db588/Aegis.git
ssh -T git@github-db588      # should greet you as db588
```

`IdentitiesOnly yes` is essential. Without it, SSH offers every key it holds, GitHub accepts the first valid one, and authentication silently reverts to the work account.

Two failure modes hit during setup, both worth remembering:

1. The `Host` block was never written to `~/.ssh/config`, producing `Could not resolve hostname github-db588`. The alias is not a real hostname; if the config block is missing, SSH tries to resolve it on the internet.
2. `git remote set-url` was skipped, so the push still used HTTPS and still failed with 403. The giveaway is the error message showing an `https://` URL.

Per-repo identity, so commits are not attributed to the work email:

```bash
git config user.name "db588"
git config user.email "<personal email>"
```

---

## 8. adb quirks

### The root server trap

`adb devices` returned an empty list despite `lsusb` clearly showing `22b8:2e81 Motorola PCS moto g06`, and despite adb having worked previously on this machine.

**Cause:** an `adb` server had been started under `sudo` while diagnosing. The root-owned server on port 5037 held the USB device and the user-level client could not use it.

**Fix:**

```bash
sudo $(which adb) kill-server
adb kill-server
adb start-server
adb devices
```

**Lesson:** never run `adb` under `sudo`. If a permissions problem is suspected, fix udev rules instead. Running it once as root creates a problem that outlives the command and looks nothing like its cause.

### Diagnostic order for an empty device list

1. `lsusb`. Is the kernel seeing the phone at all? If not, it is the cable (many are charge-only) or the port.
2. Is a root adb server running? Kill it as above.
3. `flutter devices` or any other adb consumer. If another tool sees the device, the problem is the shell environment, not the phone.
4. udev rules and `plugdev` group membership, then log out and back in.
5. On the phone: USB mode set to File Transfer rather than charging only, and check for the "Allow USB debugging?" prompt.

### Wireless debugging as an escape hatch

Android 11+ supports it and it bypasses every USB issue:

```bash
adb pair 192.168.1.x:PORT      # pairing port, with the code shown on device
adb connect 192.168.1.x:PORT   # the other port from the main screen
```

The two ports are different. Pairing uses one, connecting uses the one on the main Wireless debugging screen.

---

## 9. Runtime quirks

### Blocklist changes need a VPN restart

`DnsResolver` loads its blocklists into memory once, when the service starts. Adding, removing, enabling, or disabling a list does nothing until the VPN is toggled off and on. `MainActivity` shows a toast reminding the user, which is a workaround rather than a fix.

Fixing this properly means wiring `reloadBlocklists()` to a broadcast or binding the service. It is the highest-value item on the roadmap.

### Config.SOCIAL_MEDIA_DOMAINS is only read at list creation

The domain list in `Config.kt` is copied into the database when a blocklist is created. Editing the file and rebuilding does **not** update a list that already exists on the device. The list must be deleted in the app and recreated.

This surprises people every time. Consider it a design smell worth fixing.

### Encrypted DNS bypasses Aegis entirely

Chrome's Secure DNS and Android's Private DNS both resolve names over HTTPS or TLS directly, never touching the system resolver. Aegis never sees those queries and cannot block them.

Both must be turned off for filtering to be effective:

- Chrome: Settings, Privacy and security, Use secure DNS, off
- Android: Settings, Network and internet, Private DNS, Off

This is a fundamental limitation of the DNS-filtering approach, not a bug, and it is documented in the README for users.

### Only one VPN at a time

Android permits exactly one active `VpnService`. Starting Aegis displaced RethinkDNS on the test device, visible in the log as:

```
Vpn: Switched from com.celzero.bravedns to com.example.aegis.debug
```

Worth remembering when comparing behaviour against another blocker: they cannot run simultaneously.

### DNS-level blocking is friction, not enforcement

Anyone who can open Settings can turn the VPN off. This is a tool for reducing casual access, not for preventing determined access.

---

## 10. Reading the logcat

The app logs nothing by default. Debug logging was added to `DnsResolver.handlePacket`:

```kotlin
Log.d("Aegis", "BLOCK $domainName")
Log.d("Aegis", "FORWARD $domainName")
Log.d("Aegis", "ALLOW (whitelist) $domainName")
```

```bash
adb logcat -c && adb logcat -s Aegis:D
```

**This logs every domain the device resolves.** Fine for debugging, but it must be gated behind `Config.DEBUG_MODE` or removed before any release build.

### Noise to ignore

Filtering logcat on the package name pulls in a great deal of unrelated system chatter. None of the following indicates a problem:

| Log line | What it actually is |
|---|---|
| `AppsFilter: interaction ... BLOCKED` | Android 11+ package visibility filtering. Unrelated to DNS blocking despite the wording. Appears for Garmin, Motorola Security Hub, and others. |
| `ApkAssets: Deleting an ApkAssets object ... weak references` | Routine cleanup after a reinstall. |
| `PowerHalWrapper`, `MTK_APPList`, `mtkpower_client` | MediaTek power management telemetry. |
| `Finsky: VerifyApps ...` | Play Protect scanning the sideloaded APK. Verdict 0 means clean. |
| `AlarmManager: lost permission to set exact alarms` | The app does not use alarms. Harmless. |
| `SQLiteLog: double-quoted string literal` | Emitted by Google Play Services, not by Aegis. Check the pid. |
| `AndroidRuntime: VM exiting with result code 0` | A clean exit of some other process. A crash would show a stack trace. |

### Interpreting duplicates

The same domain logged twice within a few milliseconds is the A and AAAA query pair. Normal.

Long runs of the same blocked domain are client retry loops. Observed in testing: Chrome produced roughly forty `BLOCK www.instagram.com` lines in under two seconds after a failed load.

### The Facebook heartbeat

`z-m-gateway.facebook.com` appeared on a metronome, initially every 1.5 seconds, backing off to every 30 seconds. `graph.facebook.com` appeared occasionally too.

The Facebook app is not installed. The source is Motorola's preinstalled Meta stubs:

```
com.facebook.appmanager
com.facebook.services
com.facebook.system
```

These ship with the device and phone home regardless of whether any Meta app is used. They are unrelated to WhatsApp, which uses `whatsapp.com` and `whatsapp.net`.

To silence them without root:

```bash
adb shell pm disable-user --user 0 com.facebook.appmanager
adb shell pm disable-user --user 0 com.facebook.services
adb shell pm disable-user --user 0 com.facebook.system
```

Reversible with `adb shell pm enable <package>`, and a factory reset restores them. Alternatively `pm uninstall -k --user 0 <package>` removes them from the user profile while leaving the system image untouched.

---

## 11. Fixes applied during bring-up

Recorded because several were non-obvious and could regress.

**Room cannot return a `Set` from a query.** `getWhitelistedDomains(): Set<String>` failed KSP with "Not sure how to convert a Cursor to this method's return type". Changed the DAO to return `List<String>` and moved the `.toSet()` to the call site in `DnsResolver`.

**Duplicate domain crash.** `BlockedDomain` originally had `domain` as sole primary key. Changed to a composite key of `(domain, blocklistId)` and added `OnConflictStrategy.IGNORE` to every insert.

**VPN packet I/O.** The first implementation called a non-existent `readPacket` method on `ParcelFileDescriptor`. Rewritten to use `FileInputStream` and `FileOutputStream` on the TUN file descriptor.

**Incomplete response packets.** `DnsResolver` originally returned a bare DNS payload where the TUN interface requires a full IPv4 packet. Added `buildResponsePacket` with address swap, port swap, length, TTL, and checksum.

**Foreground service.** Added the persistent notification, channel creation, `foregroundServiceType="systemExempted"`, and the matching manifest permissions. Without this the service is killed shortly after start on modern Android.

**Missing launcher icon.** The build referenced `@mipmap/ic_launcher` which did not exist. Added an adaptive icon with a vector foreground.

**Editing quirks.** Several syntax errors came from pasting multi-line replacements in `nano`: a lost closing brace, a missing `catch` block, and two statements concatenated onto one line (`}buildResponsePacket(...)`). Kotlin is not indentation-sensitive, so formatting is free, but brace balance is not. For anything larger than a one-line change, use an editor that shows brace matching, or hand the edit to a tool that reads the whole file.

**Do not use `sudo` to edit files in the home directory.** It leaves root-owned files in the working tree and git then behaves strangely. If it has already happened: `sudo chown -R $USER:$USER <repo>`.

---

## 12. Known issues, in priority order

1. **Single-threaded resolver.** `forwardQuery` blocks the VPN thread for up to five seconds waiting on upstream. One slow lookup stalls every other DNS query on the device. Not visible on a fast network; on flaky mobile data it would present as the phone intermittently losing DNS. Fix: hand queries to a small thread pool.

2. **No DNS cache.** Every query, including repeats of a just-blocked domain, is handled from scratch. The Instagram retry storm above would collapse to a single lookup with a negative cache. An LRU keyed on (name, type) respecting TTL would cut latency noticeably on the forward path too.

3. **Blocklists require a VPN restart to apply.** See section 9.

4. **Debug logging records every resolved domain.** Must be gated or removed before release.

5. **`applicationId` is still `com.example.aegis`.** Google rejects `com.example.*`, and the ID can never be changed after first publish. Must be renamed to an owned domain before any store upload.

6. **No tests.** `parseBlocklistContent` and `buildResponsePacket` are the highest-value targets, both being pure functions with clear inputs and outputs.

7. **No IPv6.** Only IPv4 UDP port 53 is handled. AAAA queries over IPv4 transport work fine; DNS over IPv6 transport is not intercepted.

8. **No per-domain block log.** `blockedCount` is a session counter that resets when the service stops.

---

## 13. Blocklist configuration as it stands

`Config.SOCIAL_MEDIA_DOMAINS`, 16 entries after adjustment:

X and Twitter: `x.com`, `twitter.com`, `t.co`
Reddit: `reddit.com`, `redd.it`
Meta: `facebook.com`, `fb.com`, `instagram.com`
TikTok: `tiktok.com`, `douyin.com`
Telegram: `telegram.org`, `telegram.me`
Others: `snapchat.com`, `linkedin.com`, `threads.net`, `substack.com`

Deliberately excluded, commented out rather than deleted so the decision stays visible:

- `whatsapp.com`, in active use
- `discord.com`, `discord.gg`, in active use
- `youtube.com`, `youtu.be`, left commented in the original list

Note on Substack: `substack.com` catches `*.substack.com` subdomains, but many established publications run on custom domains and will not be caught. Those need adding individually.

Note on Instagram: `static.cdninstagram.com` is deliberately not blocked. It is the CDN, and blocking it would break image loading on unrelated sites that embed Instagram content. This is correct behaviour, not an oversight.

---

## 14. Roadmap

Ordered by value, suitable as individual tasks:

1. Move `forwardQuery` off the VPN thread onto a small pool
2. Add an LRU DNS cache with TTL handling, including negative caching
3. Wire `reloadBlocklists()` to a broadcast so blocklist changes apply live
4. Gate debug logging behind `Config.DEBUG_MODE`
5. Unit tests for `parseBlocklistContent` and `buildResponsePacket`
6. Rename `applicationId` and package off `com.example.*`
7. Per-domain block statistics with a simple history view
8. Scheduled blocking, for example social media blocked during working hours

Play Store preparation is documented separately in `RELEASE.md`, including keystore generation, the VPN declaration, the privacy policy requirement, and the realistic expectation that VPN-permission apps face extra review scrutiny.

---

## 15. Quick reference

```bash
# Environment, once per machine
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# Build and install
./gradlew installDebug

# Watch decisions
adb logcat -c && adb logcat -s Aegis:D

# adb not seeing the device
sudo $(which adb) kill-server && adb kill-server && adb start-server && adb devices

# Push to the personal account
git remote -v                # must show git@github-db588:...
ssh -T git@github-db588      # must greet db588
```

**After changing blocklists:** delete and recreate the list in the app, then toggle the VPN off and on.

**Before testing blocking:** Chrome Secure DNS off, Android Private DNS off.
