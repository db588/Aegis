# Aegis Privacy Policy

_Last updated: 2026_

Aegis does not collect, transmit, or share any personal data.

## What Aegis stores

Aegis stores the following on your device only, in a local database:

- The blocklists you have added, and the domains they contain
- The domains you have added to your whitelist
- Your enabled/disabled preference for each blocklist

None of this is transmitted anywhere.

## Network activity

Aegis makes two kinds of network request:

1. **DNS queries.** When an app on your device resolves a domain name, Aegis
   either answers locally (if the domain is blocked) or forwards the query to
   the upstream DNS resolver configured in the app. Aegis does not log these
   queries beyond an in-memory count of blocked results, which resets when the
   service stops.

2. **Blocklist downloads.** Only when you explicitly tap Import URL. The request
   goes to the URL you provide.

## VPN permission

Aegis uses Android's VpnService API to observe DNS traffic on the device. This
permission is used solely for local DNS filtering. Aegis does not operate a
remote VPN server, does not proxy your traffic, and does not inspect, log, or
transmit any traffic other than the DNS queries described above.

## Third parties

Aegis contains no analytics SDKs, no advertising SDKs, and no crash reporting
services.

## Contact

Questions about this policy can be raised as an issue on the project's GitHub
repository.
