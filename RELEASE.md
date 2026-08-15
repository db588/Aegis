# Releasing Aegis to the Play Store

## Before you start: two things that are permanent

**1. Change the application ID.** It is currently `com.example.aegis`. Google
will reject `com.example.*`, and the ID can never be changed after your first
publish. Use a domain you control, reversed:

```kotlin
// app/build.gradle.kts
applicationId = "uk.me.yourdomain.aegis"
namespace = "uk.me.yourdomain.aegis"
```

Then rename the package directories and update the `package`/`import` lines
to match. Claude Code can do this in one pass.

**2. Guard your keystore.** If you lose it you cannot ever update the app under
the same listing. Back it up somewhere that is not just your laptop.

## Generate a signing key

```bash
keytool -genkey -v \
  -keystore aegis-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias aegis
```

Keep the `.jks` outside the repo. `.gitignore` already excludes `*.jks`,
`*.keystore`, and `keystore.properties`.

## Build a signed release bundle

```bash
export AEGIS_KEYSTORE_PATH=/absolute/path/to/aegis-release.jks
export AEGIS_KEYSTORE_PASSWORD='...'
export AEGIS_KEY_ALIAS=aegis
export AEGIS_KEY_PASSWORD='...'

./gradlew bundleRelease
# output: app/build/outputs/bundle/release/app-release.aab
```

Play Store wants the `.aab`. Use `assembleRelease` if you want a `.apk` for
sideloading instead.

Test the release build on a real device before uploading, since minification is
enabled and ProGuard can break things that worked in debug:

```bash
./gradlew installRelease
```

## Play Console requirements for this app specifically

A VPN app gets more scrutiny than most. Budget for a longer first review.

**Privacy policy (mandatory).** Any app requesting VPN permission must have a
publicly hosted privacy policy URL. A GitHub Pages page on this repo is fine.
`fastlane/metadata/android/en-GB/` has a starting point you can adapt.

**VPN declaration.** In Play Console under App content, you must declare that the
app uses `VpnService` and explain why. Your answer is: the VPN is used locally to
filter DNS queries against user-configured blocklists; no traffic is proxied to a
remote server and no user data is collected. Be precise here, vague answers get
rejected.

**Data safety form.** Aegis collects nothing, so you declare no data collection
and no data sharing. Do not leave this blank.

**Content rating questionnaire.** Straightforward for a utility app.

**Target API level.** Play requires a reasonably current target SDK. This project
targets 34, which is fine as of writing, but check the current requirement since
it rises every year.

**Store listing assets you will need to produce:**
- App icon, 512x512 PNG
- Feature graphic, 1024x500 PNG
- At least 2 phone screenshots (grab them with `adb shell screencap`)
- Short description, 80 characters
- Full description, 4000 characters

## Screenshots the easy way

```bash
adb shell screencap -p /sdcard/shot.png && adb pull /sdcard/shot.png
```

## A realistic note on review

Apps that block ads or use VPN permission are sometimes rejected on the first
attempt, occasionally for reasons that are not obvious from the rejection text.
If it happens, the appeal process usually resolves it when you can point at an
open-source repo and a clear privacy policy. Having this project public helps.

## Versioning for subsequent releases

Bump both in `app/build.gradle.kts`:

```kotlin
versionCode = 2        // must increase every upload
versionName = "1.0.1"  // what users see
```
